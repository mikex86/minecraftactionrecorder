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
    private static final Path CAPTURES_DIR = Path.of("captures");

    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final Set<Path> activeFiles = ConcurrentHashMap.newKeySet();
    private final String clientId = HardwareIdentity.computeHardwareDerivedId();

    private volatile boolean running;
    private volatile boolean terminateRequested;
    private volatile UploadSummary lastSummary = new UploadSummary();
    private volatile boolean startupSyncLogged;
    private volatile RecordingUploadShutdownUi preparedShutdownUi;
    private Thread workerThread;

    private RecordingUploadDaemon() {
    }

    public synchronized void start() {
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
        stopBackgroundThreadNonBlocking();
        System.out.println("[UploadDaemon] shutdownAndDrainWithUi continuing after background stop request");
        terminateRequested = false;
        System.out.println("[UploadDaemon] Exiting game; starting upload completion flow...");
        System.out.println("[UploadDaemon] UI env property java.awt.headless=" + System.getProperty("java.awt.headless"));
        RecordingUploadShutdownUi window = preparedShutdownUi;
        if (window == null) {
            window = new RecordingUploadShutdownUi(this::requestTerminate, false);
        }
        window.showWindow();
        UploadSummary snapshot = lastSummary;
        if (snapshot != null && snapshot.hasDisplayableProgress()) {
            pushSummaryToUi(window, snapshot);
            System.out.println("[UploadDaemon] Reusing latest upload progress in shutdown window");
        } else {
            window.showStartingState();
        }
        System.out.println("[UploadDaemon] Upload progress window started");

        try {
            while (!terminateRequested) {
                UploadSummary summary = uploadPass(false);
                pushSummaryToUi(window, summary);
                logShutdownPass(summary);
                if (!summary.hasPendingChunks()) {
                    System.out.println("[UploadDaemon] Exiting game; All upload tasks have finished, shutdown complete");
                    break;
                }
                sleepQuietly(1000L);
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

    private synchronized void stopBackgroundThreadNonBlocking() {
        running = false;
        Thread thread = workerThread;
        if (thread == null) {
            return;
        }
        if (thread != Thread.currentThread()) {
            thread.interrupt();
        }
        workerThread = null;
        System.out.println("[UploadDaemon] Background upload thread stop signaled (non-blocking)");
    }

    private void requestTerminate() {
        terminateRequested = true;
    }

    private void backgroundLoop() {
        while (running) {
            try {
                lastSummary = uploadPass(true);
                if (!startupSyncLogged) {
                    startupSyncLogged = true;
                    if (lastSummary.initialPendingChunks > 0L) {
                        System.out.println("[UploadDaemon] Retrieved pending upload task list from server; Resuming "
                                + lastSummary.initialPendingChunks + " upload tasks...");
                    } else {
                        System.out.println("[UploadDaemon] Retrieved pending upload task list from server; No pending file part uploads required");
                    }
                }
            } catch (Exception e) {
                System.err.println("[UploadDaemon] upload pass failed: " + throwableSummary(e));
            }
            sleepQuietly(2000L);
        }
    }

    private UploadSummary uploadPass(boolean includeActiveFiles) {
        UploadSummary summary = new UploadSummary();
        if (!Files.isDirectory(CAPTURES_DIR)) {
            return summary;
        }

        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(CAPTURES_DIR)) {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        } catch (IOException e) {
            String error = throwableSummary(e);
            System.err.println("[UploadDaemon] failed to scan captures: " + error);
            summary.lastError = error;
            return summary;
        }

        for (Path file : files) {
            if (terminateRequested) {
                break;
            }
            try {
                syncAndUploadFile(file, includeActiveFiles, summary);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                summary.lastError = "Interrupted during upload pass";
                System.out.println("[UploadDaemon] upload pass interrupted; stopping current pass");
                break;
            } catch (Exception e) {
                summary.failedFiles++;
                String error = throwableSummary(e);
                summary.lastError = error;
                try {
                    long size = Files.size(file);
                    int estimatedChunks = chunkCountForSize(size);
                    summary.totalChunks += estimatedChunks;
                    summary.pendingChunks += estimatedChunks;
                } catch (IOException ignored) {
                }
                System.err.println("[UploadDaemon] failed for " + file + ": " + error);
            }
        }

        lastSummary = summary;
        return summary;
    }

    private void syncAndUploadFile(Path file, boolean includeActiveFiles, UploadSummary summary) throws Exception {
        Path normalized = normalize(file);
        boolean active = includeActiveFiles && activeFiles.contains(normalized);

        long size = Files.size(normalized);
        if (size <= 0) {
            return;
        }

        int knownChunkCount = active ? (int) (size / CHUNK_SIZE_BYTES) : chunkCountForSize(size);
        if (knownChunkCount <= 0) {
            return;
        }

        List<String> chunkHashes = computeChunkHashes(normalized, knownChunkCount, size);
        if (chunkHashes.size() != knownChunkCount) {
            return;
        }

        boolean complete = !active;
        String fullHash = complete ? sha256Hex(normalized) : null;
        String relativeName = toRelativeCaptureName(normalized);

        SyncResponse firstSync = sync(relativeName, size, complete, fullHash, chunkHashes);
        summary.initialPendingChunks += firstSync.missingChunks.size();
        for (Integer chunkIndex : firstSync.missingChunks) {
            if (chunkIndex == null || chunkIndex < 0 || chunkIndex >= knownChunkCount) {
                continue;
            }
            byte[] chunk = readChunk(normalized, chunkIndex, size);
            if (chunk.length == 0) {
                continue;
            }
            uploadChunk(relativeName, chunkIndex, chunk);
        }

        SyncResponse secondSync = sync(relativeName, size, complete, fullHash, chunkHashes);
        summary.totalChunks += knownChunkCount;
        summary.pendingChunks += secondSync.missingChunks.size();
        if (secondSync.completeOnServer) {
            summary.completedFiles++;
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

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String toRelativeCaptureName(Path file) {
        Path capturesRoot = CAPTURES_DIR.toAbsolutePath().normalize();
        Path relative = capturesRoot.relativize(file.toAbsolutePath().normalize());
        return relative.toString().replace('\\', '/');
    }

    private static int chunkCountForSize(long fileSize) {
        if (fileSize <= 0) {
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

    private static void logShutdownPass(UploadSummary summary) {
        String extra = (summary.lastError == null || summary.lastError.isBlank())
                ? ""
                : ", lastError=" + summary.lastError;
        System.out.println("[UploadDaemon] shutdown pass: pendingChunks=" + summary.pendingChunks
                + ", totalChunks=" + summary.totalChunks
                + ", failedFiles=" + summary.failedFiles
                + extra);
    }

    private static void pushSummaryToUi(RecordingUploadShutdownUi ui, UploadSummary summary) {
        ui.showProgress(
                summary.completionPercent(),
                summary.totalChunks,
                summary.pendingChunks,
                summary.failedFiles,
                summary.lastError
        );
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

    static final class UploadSummary {
        long totalChunks;
        long pendingChunks;
        long initialPendingChunks;
        int failedFiles;
        int completedFiles;
        String lastError;

        boolean hasPendingChunks() {
            return pendingChunks > 0;
        }

        boolean hasDisplayableProgress() {
            return totalChunks > 0
                    || pendingChunks > 0
                    || initialPendingChunks > 0
                    || failedFiles > 0
                    || completedFiles > 0;
        }

        int completionPercent() {
            if (totalChunks <= 0) {
                return 100;
            }
            long uploaded = Math.max(0L, totalChunks - pendingChunks);
            return (int) Math.max(0L, Math.min(100L, (uploaded * 100L) / totalChunks));
        }
    }
}
