package me.michael.kei.actionrecorder;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RecordingUploadDaemon {

    private static final RecordingUploadDaemon INSTANCE = new RecordingUploadDaemon();

    public static RecordingUploadDaemon getInstance() {
        return INSTANCE;
    }

    public static final String UPLOAD_SERVER_BASE_URL = "http://localhost:8081";
    private static final int CHUNK_SIZE_BYTES = 1_048_576;
    private static final int REQUEST_TIMEOUT_SECONDS = 8;
    private static final int BACKGROUND_LOOP_DELAY_MS = 2000;
    private static final int SHUTDOWN_POLL_INTERVAL_MS = 200;
    private static final int SHUTDOWN_STABLE_NO_WORK_PASSES = 3;
    private static final Path CAPTURES_DIR = Path.of("captures");
    private static final ProgressListener NO_OP_PROGRESS_LISTENER = progress -> { };

    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final Set<Path> activeFiles = ConcurrentHashMap.newKeySet();
    private final String clientId = HardwareIdentity.computeHardwareDerivedId();

    private final Object lifecycleLock = new Object();
    private final Object uploadPassLock = new Object();

    private volatile boolean running;
    private volatile boolean terminateRequested;
    private volatile boolean startupSyncLogged;
    private volatile RecordingUploadShutdownUi preparedShutdownUi;
    private Thread workerThread;

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

            Thread thread = new Thread(this::backgroundLoop, "recording-upload-daemon");
            thread.setDaemon(true);
            thread.start();
            workerThread = thread;
        }

        preInitializeShutdownUi();
    }

    public void notifyRecordingStarted(Path path) {
        if (path == null) {
            return;
        }
        activeFiles.add(normalize(path));
    }

    public void notifyRecordingFinished(Path path) {
        if (path == null) {
            return;
        }
        activeFiles.remove(normalize(path));
    }

    public void shutdownAndDrainWithUi() {
        System.out.println("[UploadDaemon] shutdownAndDrainWithUi entered");
        stopBackgroundThreadAndJoin();
        terminateRequested = false;

        RecordingUploadShutdownUi existingWindow = preparedShutdownUi;
        if (existingWindow == null) {
            existingWindow = new RecordingUploadShutdownUi(this::requestTerminate, false);
        }
        final RecordingUploadShutdownUi window = existingWindow;

        window.showWindow();
        window.showStartingState();
        System.out.println("[UploadDaemon] Upload progress window started");

        try {
            int stableNoWorkPasses = 0;
            while (!terminateRequested) {
                UploadProgress progress = runUploadCycle(false, snapshot -> pushProgressToUi(window, snapshot));

                logShutdownPass(progress);

                if (progress.hasRemainingWork()) {
                    stableNoWorkPasses = 0;
                } else {
                    stableNoWorkPasses++;
                    if (stableNoWorkPasses >= SHUTDOWN_STABLE_NO_WORK_PASSES) {
                        System.out.println("[UploadDaemon] Exiting game; All upload tasks have finished, shutdown complete");
                        break;
                    }
                    System.out.println("[UploadDaemon] shutdown pass reports no remaining work; verifying stability ("
                            + stableNoWorkPasses + "/" + SHUTDOWN_STABLE_NO_WORK_PASSES + ")");
                }

                sleepQuietly(SHUTDOWN_POLL_INTERVAL_MS);
            }
        } finally {
            window.dispose();
            preparedShutdownUi = null;
        }
    }

    private void preInitializeShutdownUi() {
        Thread warmupThread = new Thread(() -> {
            if (preparedShutdownUi == null) {
                preparedShutdownUi = new RecordingUploadShutdownUi(this::requestTerminate, false);
                System.out.println("[UploadDaemon] UI pre-init successful");
            }
        }, "upload-ui-preinit");
        warmupThread.setDaemon(true);
        warmupThread.start();
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

        System.out.println("[UploadDaemon] Background upload thread stop signaled; waiting for it to finish");
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
        System.out.println("[UploadDaemon] Background upload thread stopped");
    }

    private void requestTerminate() {
        terminateRequested = true;
    }

    private void backgroundLoop() {
        while (running) {
            try {
                UploadProgress progress = runUploadCycle(true, NO_OP_PROGRESS_LISTENER);

                if (!startupSyncLogged) {
                    startupSyncLogged = true;
                    if (progress.pendingChunks > 0L || progress.finalizingFiles > 0 || progress.failedFiles > 0) {
                        System.out.println("[UploadDaemon] Retrieved pending upload task list from server; Resuming uploads (pendingChunks="
                                + progress.pendingChunks + ", finalizingFiles=" + progress.finalizingFiles + ")");
                    } else {
                        System.out.println("[UploadDaemon] Retrieved pending upload task list from server; No pending file part uploads required");
                    }
                }
            } catch (Exception e) {
                System.err.println("[UploadDaemon] upload pass failed: " + throwableSummary(e));
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

            if (!firstSync.missingChunks.isEmpty()) {
                progress.phase = "Uploading " + relativeName;
                emitProgress(progress, listener);

                for (Integer chunkIndex : firstSync.missingChunks) {
                    if (terminateRequested) {
                        progress.phase = "Upload termination requested";
                        emitProgress(progress, listener);
                        return;
                    }
                    if (chunkIndex == null || chunkIndex < 0 || chunkIndex >= chunkCount) {
                        continue;
                    }

                    byte[] chunk = readChunk(normalized, chunkIndex, size);
                    if (chunk.length == 0) {
                        continue;
                    }

                    uploadChunk(relativeName, chunkIndex, chunk);
                    fileUploaded++;
                    filePending = Math.max(0L, filePending - 1L);
                    progress.uploadedChunks++;
                    progress.pendingChunks = Math.max(0L, progress.pendingChunks - 1L);
                    emitProgress(progress, listener);
                }
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
                progress.completedFiles++;
                progress.phase = "Completed " + relativeName;
            } else {
                progress.finalizingFiles++;
                progress.phase = "Finalizing " + relativeName;
            }
            emitProgress(progress, listener);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            progress.failedFiles++;
            progress.lastError = "Interrupted during upload";
            progress.phase = "Interrupted while uploading " + relativeName;
            emitProgress(progress, listener);
        } catch (Exception e) {
            progress.failedFiles++;
            progress.lastError = throwableSummary(e);
            progress.phase = "Error uploading " + relativeName;
            emitProgress(progress, listener);
            System.err.println("[UploadDaemon] failed for " + normalized + ": " + throwableSummary(e));
        }
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

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(UPLOAD_SERVER_BASE_URL + "/api/sync"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(request)))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(UPLOAD_SERVER_BASE_URL + "/api/chunk?" + query))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("chunk upload failed with status " + response.statusCode() + ": " + response.body());
        }
    }

    private static void pushProgressToUi(RecordingUploadShutdownUi ui, UploadProgress progress) {
        ui.showProgress(
                progress.completionPercent(),
                progress.totalChunks,
                progress.pendingChunks,
                progress.finalizingFiles,
                progress.failedFiles,
                progress.phase,
                progress.lastError
        );
    }

    private static void logShutdownPass(UploadProgress progress) {
        String error = (progress.lastError == null || progress.lastError.isBlank())
                ? ""
                : ", lastError=" + progress.lastError;
        System.out.println("[UploadDaemon] shutdown pass: pendingChunks=" + progress.pendingChunks
                + ", totalChunks=" + progress.totalChunks
                + ", finalizingFiles=" + progress.finalizingFiles
                + ", failedFiles=" + progress.failedFiles
                + error);
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
