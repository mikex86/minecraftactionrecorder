package me.michael.kei.actionrecorder;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;

public final class RecordingUploadDaemon {

    private static final RecordingUploadDaemon INSTANCE = new RecordingUploadDaemon();

    public static RecordingUploadDaemon getInstance() {
        return INSTANCE;
    }

    public static final String UPLOAD_SERVER_BASE_URL = "http://localhost:8081";
    private static final int CHUNK_SIZE_BYTES = 1_048_576;
    private static final int CONNECT_TIMEOUT_SECONDS = 6;
    private static final int SYNC_REQUEST_TIMEOUT_SECONDS = 12;
    private static final int CHUNK_REQUEST_TIMEOUT_SECONDS = 20;
    private static final int SYNC_REQUEST_MAX_ATTEMPTS = 3;
    private static final int CHUNK_REQUEST_MAX_ATTEMPTS = 4;
    private static final long RETRY_BASE_DELAY_MS = 350L;
    private static final long RETRY_MAX_DELAY_MS = 4000L;
    private static final int BACKGROUND_LOOP_DELAY_MS = 2000;
    private static final int SHUTDOWN_POLL_INTERVAL_MS = 200;
    private static final int SHUTDOWN_STABLE_NO_WORK_PASSES = 3;
    private static final String CHUNK_UPLOAD_PARALLELISM_PROPERTY = "actionrecorder.upload.parallelChunks";
    private static final int DEFAULT_CHUNK_UPLOAD_PARALLELISM = 16;
    private static final int MAX_CHUNK_UPLOAD_PARALLELISM = 128;
    private static final Path CAPTURES_DIR = Path.of("captures");

    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build();
    private final Set<Path> activeFiles = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Path, Integer> activeFilesFullySyncedChunkCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, Long> completedFilesFullySyncedSizes = new ConcurrentHashMap<>();
    private final String clientId = HardwareIdentity.computeHardwareDerivedId();

    private final Object lifecycleLock = new Object();
    private final Object uploadPassLock = new Object();
    private final Object chunkProgressLogLock = new Object();

    private volatile boolean running;
    private volatile boolean terminateRequested;
    private volatile boolean startupSyncLogged;
    private volatile RecordingUploadWindow uploadWindow;
    private volatile int chunkUploadParallelism = resolveConfiguredChunkUploadParallelism();
    private Thread workerThread;

    private long lastLoggedTotalChunks = -1L;
    private long lastLoggedUploadedChunks = -1L;
    private long lastLoggedPendingChunks = -1L;
    private String lastLoggedPhase;
    private String lastLoggedFile;
    private String lastLoggedError;
    private final AtomicInteger chunkUploadThreadCounter = new AtomicInteger();

    private RecordingUploadDaemon() {
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            running = true;
            terminateRequested = false;
            startupSyncLogged = false;
            activeFilesFullySyncedChunkCounts.clear();
            completedFilesFullySyncedSizes.clear();
            resetChunkProgressLogState();
            ensureUploadWindow();
            if (uploadWindow != null) {
                uploadWindow.showWindow();
                uploadWindow.updateProgress(UploadProgress.idle());
            }
            logInfo("Chunk upload parallelism configured to " + chunkUploadParallelism
                    + " (set via -D" + CHUNK_UPLOAD_PARALLELISM_PROPERTY + "=N)");
            logInfo("HTTP upload tuning: connectTimeout=" + CONNECT_TIMEOUT_SECONDS + "s, syncTimeout="
                    + SYNC_REQUEST_TIMEOUT_SECONDS + "s x" + SYNC_REQUEST_MAX_ATTEMPTS
                    + ", chunkTimeout=" + CHUNK_REQUEST_TIMEOUT_SECONDS + "s x" + CHUNK_REQUEST_MAX_ATTEMPTS);

            Thread thread = new Thread(this::backgroundLoop, "recording-upload-daemon");
            thread.setDaemon(true);
            thread.start();
            workerThread = thread;
        }
    }

    public void notifyRecordingStarted(Path path) {
        if (path == null) {
            return;
        }
        Path normalized = normalize(path);
        activeFiles.add(normalized);
        activeFilesFullySyncedChunkCounts.remove(normalized);
        completedFilesFullySyncedSizes.remove(normalized);
    }

    public void notifyRecordingFinished(Path path) {
        if (path == null) {
            return;
        }
        Path normalized = normalize(path);
        activeFiles.remove(normalized);
        activeFilesFullySyncedChunkCounts.remove(normalized);
        completedFilesFullySyncedSizes.remove(normalized);
    }

    public int getChunkUploadParallelism() {
        return chunkUploadParallelism;
    }

    public void setChunkUploadParallelism(int parallelism) {
        int sanitized = sanitizeChunkUploadParallelism(parallelism);
        if (chunkUploadParallelism == sanitized) {
            return;
        }
        chunkUploadParallelism = sanitized;
        logInfo("Chunk upload parallelism changed to " + sanitized);
    }

    public void blockUntilCurrentUploadsComplete() {
        logInfo("Shutdown drain start; waiting for current uploads to finish");
        stopBackgroundThreadAndJoin();
        terminateRequested = false;

        int stableNoWorkPasses = 0;
        boolean interrupted = false;
        while (true) {
            UploadProgress progress;
            try {
                progress = runUploadCycle(false, this::onCycleProgress);
            } catch (Exception e) {
                logError("shutdown drain upload pass failed: " + throwableSummary(e));
                progress = new UploadProgress(
                        0L, 0L, 0L,
                        0, 0, 0, 1,
                        "Shutdown drain failed",
                        null,
                        throwableSummary(e)
                );
            }

            logShutdownPass(progress);
            if (progress.hasRemainingWork()) {
                stableNoWorkPasses = 0;
            } else {
                stableNoWorkPasses++;
                if (stableNoWorkPasses >= SHUTDOWN_STABLE_NO_WORK_PASSES) {
                    logInfo("Shutdown drain complete; uploads stabilized with no remaining work");
                    return;
                }
                logInfo("Shutdown drain pass reports no remaining work; verifying stability ("
                        + stableNoWorkPasses + "/" + SHUTDOWN_STABLE_NO_WORK_PASSES + ")");
            }

            try {
                Thread.sleep(SHUTDOWN_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
            logError("Shutdown drain interrupted before completion");
        }
    }

    private void stopBackgroundThreadAndJoin() {
        Thread thread;
        synchronized (lifecycleLock) {
            running = false;
            thread = workerThread;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }

        if (thread == null) {
            return;
        }

        logInfo("Background upload thread stop signaled; waiting for it to finish");
        if (thread != Thread.currentThread()) {
            boolean interrupted = false;
            while (thread.isAlive()) {
                try {
                    thread.join(1000L);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized (lifecycleLock) {
            if (workerThread == thread) {
                workerThread = null;
            }
        }
        logInfo("Background upload thread stopped");
    }

    private void requestTerminate() {
        terminateRequested = true;
    }

    private void backgroundLoop() {
        while (running) {
            try {
                UploadProgress progress = runUploadCycle(true, this::onCycleProgress);

                if (!startupSyncLogged) {
                    startupSyncLogged = true;
                    if (progress.pendingChunks > 0L || progress.finalizingFiles > 0 || progress.failedFiles > 0) {
                        logInfo("Retrieved pending upload task list from server; Resuming uploads (pendingChunks="
                                + progress.pendingChunks + ", finalizingFiles=" + progress.finalizingFiles + ")");
                    } else {
                        logInfo("Retrieved pending upload task list from server; No pending file part uploads required");
                    }
                }
            } catch (Exception e) {
                logError("upload pass failed: " + throwableSummary(e));
            }

            if (!running) {
                break;
            }
            sleepQuietly(BACKGROUND_LOOP_DELAY_MS);
        }
    }

    private UploadProgress runUploadCycle(boolean includeActiveFiles, ProgressListener listener) {
        synchronized (uploadPassLock) {
            MutableProgress progress = new MutableProgress();
            progress.phase = "Scanning captures...";
            emitProgress(progress, listener);

            if (!Files.isDirectory(CAPTURES_DIR)) {
                progress.phase = "No capture directory found";
                emitProgress(progress, listener);
                return progress.snapshot();
            }

            List<Path> files = new ArrayList<>();
            try (var stream = Files.walk(CAPTURES_DIR)) {
                stream.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(files::add);
            } catch (IOException e) {
                progress.lastError = throwableSummary(e);
                progress.failedFiles++;
                progress.phase = "Failed to scan captures";
                emitProgress(progress, listener);
                return progress.snapshot();
            }

            for (Path file : files) {
                if (terminateRequested) {
                    progress.phase = "Upload termination requested";
                    emitProgress(progress, listener);
                    break;
                }
                processFile(file, includeActiveFiles, progress, listener);
            }

            if (progress.hasRemainingWork()) {
                progress.phase = "Waiting for remaining upload work...";
            } else {
                progress.phase = "Uploads are up to date";
            }
            emitProgress(progress, listener);
            return progress.snapshot();
        }
    }

    private void processFile(Path file, boolean includeActiveFiles, MutableProgress progress, ProgressListener listener) {
        Path normalized = normalize(file);

        long size;
        try {
            size = Files.size(normalized);
        } catch (IOException e) {
            progress.failedFiles++;
            progress.lastError = throwableSummary(e);
            progress.phase = "Failed to read file size";
            emitProgress(progress, listener);
            return;
        }

        if (size <= 0L) {
            return;
        }

        boolean active = includeActiveFiles && activeFiles.contains(normalized);
        int chunkCount = active ? (int) (size / CHUNK_SIZE_BYTES) : chunkCountForSize(size);
        if (chunkCount <= 0) {
            return;
        }
        if (active) {
            completedFilesFullySyncedSizes.remove(normalized);
            Integer lastSyncedChunkCount = activeFilesFullySyncedChunkCounts.get(normalized);
            if (lastSyncedChunkCount != null && lastSyncedChunkCount == chunkCount) {
                return;
            }
        } else {
            activeFilesFullySyncedChunkCounts.remove(normalized);
            Long lastCompletedSyncedSize = completedFilesFullySyncedSizes.get(normalized);
            if (lastCompletedSyncedSize != null && lastCompletedSyncedSize == size) {
                return;
            }
        }

        String relativeName = toRelativeCaptureName(normalized);
        boolean complete = !active;

        progress.totalFiles++;
        progress.currentFile = relativeName;
        progress.phase = "Syncing " + relativeName;

        try {
            List<String> chunkHashes = computeChunkHashes(normalized, chunkCount, size);
            if (chunkHashes.size() != chunkCount) {
                progress.failedFiles++;
                progress.lastError = "Chunk hash count mismatch for " + relativeName;
                progress.phase = "Failed hashing " + relativeName;
                emitProgress(progress, listener);
                return;
            }

            String fullHash = complete ? sha256Hex(normalized) : null;
            SyncResponse firstSync = sync(relativeName, size, complete, fullHash, chunkHashes);

            long firstPending = firstSync.missingChunks.size();
            long fileUploaded = Math.max(0L, chunkCount - firstPending);
            long filePending = firstPending;

            progress.totalChunks += chunkCount;
            progress.uploadedChunks += fileUploaded;
            progress.pendingChunks += filePending;
            emitProgress(progress, listener);

            // If there is no missing work after the first sync, avoid a redundant second sync call.
            if (firstSync.missingChunks.isEmpty()) {
                if (active) {
                    activeFilesFullySyncedChunkCounts.put(normalized, chunkCount);
                    progress.phase = "Live chunks synced " + relativeName;
                } else if (firstSync.completeOnServer) {
                    completedFilesFullySyncedSizes.put(normalized, size);
                    progress.completedFiles++;
                    progress.phase = "Completed " + relativeName;
                } else {
                    completedFilesFullySyncedSizes.remove(normalized);
                    progress.finalizingFiles++;
                    progress.phase = "Finalizing " + relativeName;
                }
                emitProgress(progress, listener);
                return;
            }

            progress.phase = "Uploading " + relativeName;
            emitProgress(progress, listener);

            UploadBatchResult uploadResult = uploadMissingChunks(relativeName, normalized, size, chunkCount,
                    firstSync.missingChunks, progress, listener);
            fileUploaded += uploadResult.uploadedChunks;
            filePending = Math.max(0L, filePending - uploadResult.uploadedChunks);
            if (uploadResult.terminationRequested) {
                return;
            }

            SyncResponse secondSync = sync(relativeName, size, complete, fullHash, chunkHashes);
            long secondPending = secondSync.missingChunks.size();
            long secondUploaded = Math.max(0L, chunkCount - secondPending);

            progress.uploadedChunks += (secondUploaded - fileUploaded);
            progress.pendingChunks += (secondPending - filePending);
            if (progress.uploadedChunks < 0L) {
                progress.uploadedChunks = 0L;
            }
            if (progress.pendingChunks < 0L) {
                progress.pendingChunks = 0L;
            }

            if (secondSync.completeOnServer) {
                completedFilesFullySyncedSizes.put(normalized, size);
                progress.completedFiles++;
                progress.phase = "Completed " + relativeName;
            } else {
                completedFilesFullySyncedSizes.remove(normalized);
                if (complete) {
                    progress.finalizingFiles++;
                    progress.phase = "Finalizing " + relativeName;
                } else {
                    progress.phase = "Live chunks synced " + relativeName;
                }
            }
            if (active && secondPending == 0L) {
                activeFilesFullySyncedChunkCounts.put(normalized, chunkCount);
            } else if (active) {
                activeFilesFullySyncedChunkCounts.remove(normalized);
            }
            emitProgress(progress, listener);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            progress.failedFiles++;
            progress.lastError = "Interrupted during upload";
            progress.phase = "Interrupted while uploading " + relativeName;
            activeFilesFullySyncedChunkCounts.remove(normalized);
            completedFilesFullySyncedSizes.remove(normalized);
            emitProgress(progress, listener);
        } catch (Exception e) {
            progress.failedFiles++;
            progress.lastError = throwableSummary(e);
            progress.phase = "Error uploading " + relativeName;
            activeFilesFullySyncedChunkCounts.remove(normalized);
            completedFilesFullySyncedSizes.remove(normalized);
            emitProgress(progress, listener);
            logError("failed for " + normalized + ": " + throwableSummary(e));
        }
    }

    private UploadBatchResult uploadMissingChunks(String relativeName,
                                                  Path file,
                                                  long fileSize,
                                                  int chunkCount,
                                                  List<Integer> missingChunks,
                                                  MutableProgress progress,
                                                  ProgressListener listener) throws IOException, InterruptedException {
        int parallelism = sanitizeChunkUploadParallelism(chunkUploadParallelism);
        if (parallelism <= 1 || missingChunks.size() <= 1) {
            return uploadMissingChunksSequential(relativeName, file, fileSize, chunkCount, missingChunks, progress, listener);
        }
        return uploadMissingChunksParallel(relativeName, file, fileSize, chunkCount, missingChunks, parallelism, progress, listener);
    }

    private UploadBatchResult uploadMissingChunksSequential(String relativeName,
                                                            Path file,
                                                            long fileSize,
                                                            int chunkCount,
                                                            List<Integer> missingChunks,
                                                            MutableProgress progress,
                                                            ProgressListener listener) throws IOException, InterruptedException {
        long uploaded = 0L;
        for (Integer chunkIndex : missingChunks) {
            if (terminateRequested) {
                progress.phase = "Upload termination requested";
                emitProgress(progress, listener);
                return new UploadBatchResult(uploaded, true);
            }
            if (!isValidChunkIndex(chunkIndex, chunkCount)) {
                continue;
            }

            if (uploadSingleChunk(relativeName, file, fileSize, chunkIndex)) {
                uploaded++;
                markChunkUploaded(progress, listener);
            }
        }
        return new UploadBatchResult(uploaded, false);
    }

    private UploadBatchResult uploadMissingChunksParallel(String relativeName,
                                                          Path file,
                                                          long fileSize,
                                                          int chunkCount,
                                                          List<Integer> missingChunks,
                                                          int parallelism,
                                                          MutableProgress progress,
                                                          ProgressListener listener) throws IOException, InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "upload-chunk-" + chunkUploadThreadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        CompletionService<Boolean> completionService = new ExecutorCompletionService<>(executor);

        int nextMissingChunkIndex = 0;
        int inFlight = 0;
        long uploaded = 0L;
        boolean sawTerminationRequest = false;

        try {
            while (!terminateRequested && nextMissingChunkIndex < missingChunks.size() && inFlight < parallelism) {
                Integer chunkIndex = missingChunks.get(nextMissingChunkIndex++);
                if (!isValidChunkIndex(chunkIndex, chunkCount)) {
                    continue;
                }
                final int taskChunkIndex = chunkIndex;
                completionService.submit(() -> uploadSingleChunk(relativeName, file, fileSize, taskChunkIndex));
                inFlight++;
            }

            while (inFlight > 0) {
                if (terminateRequested) {
                    sawTerminationRequest = true;
                    break;
                }

                Future<Boolean> finished = completionService.take();
                inFlight--;

                boolean chunkUploaded;
                try {
                    chunkUploaded = Boolean.TRUE.equals(finished.get());
                } catch (ExecutionException e) {
                    throw unwrapChunkUploadException(e);
                }

                if (chunkUploaded) {
                    uploaded++;
                    markChunkUploaded(progress, listener);
                }

                while (!terminateRequested && nextMissingChunkIndex < missingChunks.size() && inFlight < parallelism) {
                    Integer chunkIndex = missingChunks.get(nextMissingChunkIndex++);
                    if (!isValidChunkIndex(chunkIndex, chunkCount)) {
                        continue;
                    }
                    final int taskChunkIndex = chunkIndex;
                    completionService.submit(() -> uploadSingleChunk(relativeName, file, fileSize, taskChunkIndex));
                    inFlight++;
                }
            }
        } finally {
            executor.shutdownNow();
        }

        if (sawTerminationRequest || terminateRequested) {
            progress.phase = "Upload termination requested";
            emitProgress(progress, listener);
            return new UploadBatchResult(uploaded, true);
        }
        return new UploadBatchResult(uploaded, false);
    }

    private static boolean isValidChunkIndex(Integer chunkIndex, int chunkCount) {
        return chunkIndex != null && chunkIndex >= 0 && chunkIndex < chunkCount;
    }

    private boolean uploadSingleChunk(String relativeName, Path file, long fileSize, int chunkIndex) throws IOException, InterruptedException {
        byte[] chunk = readChunk(file, chunkIndex, fileSize);
        if (chunk.length == 0) {
            return false;
        }
        uploadChunk(relativeName, chunkIndex, chunk);
        return true;
    }

    private static void markChunkUploaded(MutableProgress progress, ProgressListener listener) {
        progress.uploadedChunks++;
        progress.pendingChunks = Math.max(0L, progress.pendingChunks - 1L);
        emitProgress(progress, listener);
    }

    private static int resolveConfiguredChunkUploadParallelism() {
        String raw = System.getProperty(CHUNK_UPLOAD_PARALLELISM_PROPERTY, "");
        if (raw.isBlank()) {
            return DEFAULT_CHUNK_UPLOAD_PARALLELISM;
        }
        try {
            return sanitizeChunkUploadParallelism(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ignored) {
            return DEFAULT_CHUNK_UPLOAD_PARALLELISM;
        }
    }

    private static int sanitizeChunkUploadParallelism(int parallelism) {
        if (parallelism <= 0) {
            return 1;
        }
        return Math.min(parallelism, MAX_CHUNK_UPLOAD_PARALLELISM);
    }

    private static IOException unwrapChunkUploadException(ExecutionException e) throws InterruptedException {
        Throwable cause = e.getCause();
        if (cause instanceof IOException ioException) {
            return ioException;
        }
        if (cause instanceof InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw interruptedException;
        }
        if (cause instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        return new IOException("chunk upload failed", cause == null ? e : cause);
    }

    private SyncResponse sync(String relativeName, long totalSize, boolean complete, String fullHash,
                              List<String> chunkHashes) throws IOException, InterruptedException {
        SyncRequest request = new SyncRequest();
        request.clientId = clientId;
        request.fileName = relativeName;
        request.chunkSize = CHUNK_SIZE_BYTES;
        request.totalSize = totalSize;
        request.complete = complete;
        request.fullHash = fullHash;
        request.chunkHashes = chunkHashes;

        String requestJson = gson.toJson(request);
        HttpResponse<String> response = sendStringRequestWithRetry(
                () -> HttpRequest.newBuilder()
                        .uri(URI.create(UPLOAD_SERVER_BASE_URL + "/api/sync"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(SYNC_REQUEST_TIMEOUT_SECONDS))
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                        .build(),
                "sync " + relativeName,
                SYNC_REQUEST_MAX_ATTEMPTS
        );
        if (response.statusCode() != 200) {
            throw new IOException("sync failed with status " + response.statusCode() + ": " + response.body());
        }

        SyncResponse parsed = gson.fromJson(response.body(), SyncResponse.class);
        if (parsed == null) {
            parsed = new SyncResponse();
        }
        if (parsed.missingChunks == null) {
            parsed.missingChunks = new ArrayList<>();
        }
        return parsed;
    }

    private void uploadChunk(String relativeName, int chunkIndex, byte[] chunk) throws IOException, InterruptedException {
        String chunkHash = sha256Hex(chunk);
        String query = "clientId=" + encode(clientId)
                + "&fileName=" + encode(relativeName)
                + "&index=" + chunkIndex
                + "&chunkSize=" + CHUNK_SIZE_BYTES
                + "&chunkHash=" + encode(chunkHash);
        HttpResponse<String> response = sendStringRequestWithRetry(
                () -> HttpRequest.newBuilder()
                        .uri(URI.create(UPLOAD_SERVER_BASE_URL + "/api/chunk?" + query))
                        .timeout(Duration.ofSeconds(CHUNK_REQUEST_TIMEOUT_SECONDS))
                        .header("Content-Type", "application/octet-stream")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk))
                        .build(),
                "chunk " + relativeName + "#" + chunkIndex,
                CHUNK_REQUEST_MAX_ATTEMPTS
        );
        if (response.statusCode() != 200) {
            throw new IOException("chunk upload failed with status " + response.statusCode() + ": " + response.body());
        }
    }

    private void logShutdownPass(UploadProgress progress) {
        String error = (progress.lastError == null || progress.lastError.isBlank())
                ? ""
                : ", lastError=" + progress.lastError;
        logInfo("shutdown pass: pendingChunks=" + progress.pendingChunks
                + ", totalChunks=" + progress.totalChunks
                + ", finalizingFiles=" + progress.finalizingFiles
                + ", failedFiles=" + progress.failedFiles
                + error);
    }

    private void onCycleProgress(UploadProgress progress) {
        updateUploadUi(progress);
        logChunkProgressIfChanged(progress);
    }

    private void updateUploadUi(UploadProgress progress) {
        RecordingUploadWindow window = uploadWindow;
        if (window != null) {
            window.updateProgress(progress);
        }
    }

    private void logChunkProgressIfChanged(UploadProgress progress) {
        boolean changed;
        synchronized (chunkProgressLogLock) {
            changed = progress.totalChunks != lastLoggedTotalChunks
                    || progress.uploadedChunks != lastLoggedUploadedChunks
                    || progress.pendingChunks != lastLoggedPendingChunks
                    || !Objects.equals(progress.phase, lastLoggedPhase)
                    || !Objects.equals(progress.currentFile, lastLoggedFile);
            if (!changed) {
                if (progress.lastError != null
                        && !progress.lastError.isBlank()
                        && !Objects.equals(progress.lastError, lastLoggedError)) {
                    lastLoggedError = progress.lastError;
                    logError("chunk progress error: " + progress.lastError);
                }
                return;
            }
            lastLoggedTotalChunks = progress.totalChunks;
            lastLoggedUploadedChunks = progress.uploadedChunks;
            lastLoggedPendingChunks = progress.pendingChunks;
            lastLoggedPhase = progress.phase;
            lastLoggedFile = progress.currentFile;
            lastLoggedError = progress.lastError;
        }

        String filePart = (progress.currentFile == null || progress.currentFile.isBlank())
                ? ""
                : ", file=" + progress.currentFile;
        String phasePart = (progress.phase == null || progress.phase.isBlank())
                ? "unknown"
                : progress.phase;
        logInfo("chunk progress: uploaded=" + progress.uploadedChunks + "/" + progress.totalChunks
                + ", pending=" + progress.pendingChunks
                + ", completion=" + progress.completionPercent() + "%"
                + ", phase=" + phasePart
                + filePart);
        if (progress.lastError != null && !progress.lastError.isBlank()) {
            logError("chunk progress error: " + progress.lastError);
        }
    }

    private void ensureUploadWindow() {
        if (uploadWindow != null) {
            return;
        }
        uploadWindow = RecordingUploadWindow.createIfSupported();
    }

    private void resetChunkProgressLogState() {
        synchronized (chunkProgressLogLock) {
            lastLoggedTotalChunks = -1L;
            lastLoggedUploadedChunks = -1L;
            lastLoggedPendingChunks = -1L;
            lastLoggedPhase = null;
            lastLoggedFile = null;
            lastLoggedError = null;
        }
    }

    private void logInfo(String message) {
        String line = "[UploadDaemon] " + message;
        System.out.println(line);
        appendUiLog(line);
    }

    private void logError(String message) {
        String line = "[UploadDaemon] " + message;
        System.err.println(line);
        appendUiLog(line);
    }

    private void appendUiLog(String line) {
        RecordingUploadWindow window = uploadWindow;
        if (window != null) {
            window.appendLog(line);
        }
    }

    private HttpResponse<String> sendStringRequestWithRetry(RequestFactory factory,
                                                            String operation,
                                                            int maxAttempts) throws IOException, InterruptedException {
        IOException lastIoException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(factory.createRequest(), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (!isRetryableStatus(status) || attempt >= maxAttempts) {
                    return response;
                }
                long delayMs = computeRetryDelayMs(attempt);
                logInfo(operation + " attempt " + attempt + "/" + maxAttempts
                        + " returned HTTP " + status + "; retrying in " + delayMs + "ms");
                Thread.sleep(delayMs);
            } catch (IOException e) {
                lastIoException = e;
                if (!isRetryableIOException(e) || attempt >= maxAttempts) {
                    throw e;
                }
                long delayMs = computeRetryDelayMs(attempt);
                logInfo(operation + " attempt " + attempt + "/" + maxAttempts
                        + " failed: " + throwableSummary(e) + "; retrying in " + delayMs + "ms");
                Thread.sleep(delayMs);
            }
        }
        if (lastIoException != null) {
            throw lastIoException;
        }
        throw new IOException("request failed after retries: " + operation);
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500;
    }

    private static boolean isRetryableIOException(IOException e) {
        if (e instanceof HttpTimeoutException) {
            return true;
        }
        if (e instanceof InterruptedIOException) {
            return false;
        }
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof HttpTimeoutException) {
                return true;
            }
            if (cause instanceof InterruptedException || cause instanceof InterruptedIOException) {
                return false;
            }
            cause = cause.getCause();
        }
        return true;
    }

    private static long computeRetryDelayMs(int attempt) {
        long base = RETRY_BASE_DELAY_MS * (1L << Math.max(0, attempt - 1));
        long capped = Math.min(base, RETRY_MAX_DELAY_MS);
        long jitter = ThreadLocalRandom.current().nextLong(80L, 260L);
        return capped + jitter;
    }

    private static void emitProgress(MutableProgress progress, ProgressListener listener) {
        listener.onProgress(progress.snapshot());
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String toRelativeCaptureName(Path file) {
        Path capturesRoot = CAPTURES_DIR.toAbsolutePath().normalize();
        Path relative = capturesRoot.relativize(file.toAbsolutePath().normalize());
        return relative.toString().replace('\\', '/');
    }

    private static int chunkCountForSize(long fileSize) {
        if (fileSize <= 0L) {
            return 0;
        }
        return (int) ((fileSize + CHUNK_SIZE_BYTES - 1L) / CHUNK_SIZE_BYTES);
    }

    private static List<String> computeChunkHashes(Path file, int chunkCount, long fileSize) throws IOException {
        List<String> hashes = new ArrayList<>(chunkCount);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            for (int i = 0; i < chunkCount; i++) {
                long offset = (long) i * CHUNK_SIZE_BYTES;
                if (offset >= fileSize) {
                    break;
                }
                int expectedLength = (int) Math.min(CHUNK_SIZE_BYTES, fileSize - offset);
                byte[] chunk = new byte[expectedLength];
                raf.seek(offset);
                raf.readFully(chunk);
                hashes.add(sha256Hex(chunk));
            }
        }
        return hashes;
    }

    private static byte[] readChunk(Path file, int chunkIndex, long fileSize) throws IOException {
        long offset = (long) chunkIndex * CHUNK_SIZE_BYTES;
        if (offset >= fileSize) {
            return new byte[0];
        }
        int expectedLength = (int) Math.min(CHUNK_SIZE_BYTES, fileSize - offset);
        byte[] chunk = new byte[expectedLength];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);
            raf.readFully(chunk);
        }
        return chunk;
    }

    private static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[8192];
        try (var in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private static String sha256Hex(byte[] bytes) {
        MessageDigest digest = newSha256();
        digest.update(bytes);
        return toHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String throwableSummary(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName());

        String message = t.getMessage();
        if (message != null && !message.isBlank()) {
            sb.append(": ").append(message);
        }

        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append(" (cause=").append(cause.getClass().getSimpleName());
            String causeMessage = cause.getMessage();
            if (causeMessage != null && !causeMessage.isBlank()) {
                sb.append(": ").append(causeMessage);
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private record UploadBatchResult(long uploadedChunks, boolean terminationRequested) {
    }

    @FunctionalInterface
    private interface RequestFactory {
        HttpRequest createRequest();
    }

    private interface ProgressListener {
        void onProgress(UploadProgress progress);
    }

    private static final class MutableProgress {
        long totalChunks;
        long uploadedChunks;
        long pendingChunks;
        int totalFiles;
        int completedFiles;
        int finalizingFiles;
        int failedFiles;
        String phase;
        String currentFile;
        String lastError;

        boolean hasRemainingWork() {
            return pendingChunks > 0 || finalizingFiles > 0 || failedFiles > 0;
        }

        UploadProgress snapshot() {
            return new UploadProgress(
                    totalChunks,
                    uploadedChunks,
                    pendingChunks,
                    totalFiles,
                    completedFiles,
                    finalizingFiles,
                    failedFiles,
                    phase,
                    currentFile,
                    lastError
            );
        }
    }

    static final class UploadProgress {
        final long totalChunks;
        final long uploadedChunks;
        final long pendingChunks;
        final int totalFiles;
        final int completedFiles;
        final int finalizingFiles;
        final int failedFiles;
        final String phase;
        final String currentFile;
        final String lastError;

        UploadProgress(long totalChunks,
                       long uploadedChunks,
                       long pendingChunks,
                       int totalFiles,
                       int completedFiles,
                       int finalizingFiles,
                       int failedFiles,
                       String phase,
                       String currentFile,
                       String lastError) {
            this.totalChunks = Math.max(0L, totalChunks);
            this.uploadedChunks = Math.max(0L, uploadedChunks);
            this.pendingChunks = Math.max(0L, pendingChunks);
            this.totalFiles = Math.max(0, totalFiles);
            this.completedFiles = Math.max(0, completedFiles);
            this.finalizingFiles = Math.max(0, finalizingFiles);
            this.failedFiles = Math.max(0, failedFiles);
            this.phase = phase;
            this.currentFile = currentFile;
            this.lastError = lastError;
        }

        static UploadProgress idle() {
            return new UploadProgress(0L, 0L, 0L, 0, 0, 0, 0,
                    "Idle", null, null);
        }

        boolean hasRemainingWork() {
            return pendingChunks > 0 || finalizingFiles > 0 || failedFiles > 0;
        }

        int completionPercent() {
            if (totalChunks <= 0L) {
                return hasRemainingWork() ? 0 : 100;
            }
            int pct = (int) Math.max(0L, Math.min(100L, (uploadedChunks * 100L) / totalChunks));
            if (hasRemainingWork() && pct >= 100) {
                return 99;
            }
            return pct;
        }
    }

    private static final class SyncRequest {
        @SerializedName("clientId")
        String clientId;
        @SerializedName("fileName")
        String fileName;
        @SerializedName("chunkSize")
        int chunkSize;
        @SerializedName("totalSize")
        long totalSize;
        @SerializedName("complete")
        boolean complete;
        @SerializedName("fullHash")
        String fullHash;
        @SerializedName("chunkHashes")
        List<String> chunkHashes;
    }

    private static final class SyncResponse {
        @SerializedName("missingChunks")
        List<Integer> missingChunks = new ArrayList<>();
        @SerializedName("completeOnServer")
        boolean completeOnServer;
    }
}
