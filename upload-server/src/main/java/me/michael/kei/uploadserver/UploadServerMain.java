package me.michael.kei.uploadserver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class UploadServerMain {

    private static final String HOST = "0.0.0.0";
    private static final int PORT = 8081;
    private static final Path WORKING_DIR = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    private static final Path DATA_ROOT = WORKING_DIR.resolve("upload-server-data").normalize();
    private static final int MAX_REQUEST_BODY_BYTES = 64 * 1024 * 1024;

    private final Gson gson;
    private final UploadRepository repository;
    private final UploadThrottle uploadThrottle;
    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;
    private Channel serverChannel;

    private UploadServerMain(long throttleBitsPerSecond) throws IOException {
        this.gson = new GsonBuilder().disableHtmlEscaping().create();
        this.repository = new UploadRepository(DATA_ROOT, gson);
        this.uploadThrottle = new UploadThrottle(throttleBitsPerSecond);
    }

    public static void main(String[] args) throws Exception {
        long throttleBitsPerSecond = parseThrottleBitsPerSecond(args);
        UploadServerMain server = new UploadServerMain(throttleBitsPerSecond);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "upload-server-shutdown"));

        server.start();
        System.out.printf("Upload server (Netty) listening on http://%s:%d%n", HOST, PORT);
        System.out.printf("Working directory: %s%n", WORKING_DIR);
        System.out.printf("Data root: %s%n", DATA_ROOT);
        if (throttleBitsPerSecond > 0) {
            System.out.printf("Upload throttle mode: enabled at %.3f mbit/s%n", throttleBitsPerSecond / 1_000_000.0);
        } else {
            System.out.println("Upload throttle mode: disabled");
        }

        try {
            server.serverChannel.closeFuture().sync();
        } finally {
            server.stop();
        }
    }

    private void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(Math.max(2, Runtime.getRuntime().availableProcessors()));

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(MAX_REQUEST_BODY_BYTES));
                        ch.pipeline().addLast(new HttpServerExpectContinueHandler());
                        ch.pipeline().addLast(new UploadHttpHandler(gson, repository, uploadThrottle));
                    }
                });

        serverChannel = bootstrap.bind(HOST, PORT).sync().channel();
    }

    private void stop() {
        Channel channel = serverChannel;
        if (channel != null) {
            channel.close();
            serverChannel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
    }

    private static final class UploadHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final Gson gson;
        private final UploadRepository repository;
        private final UploadThrottle uploadThrottle;

        private UploadHttpHandler(Gson gson, UploadRepository repository, UploadThrottle uploadThrottle) {
            this.gson = gson;
            this.repository = repository;
            this.uploadThrottle = uploadThrottle;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            if (!request.decoderResult().isSuccess()) {
                sendJson(ctx, request, HttpResponseStatus.BAD_REQUEST, gson.toJson(Map.of("error", "malformed_request")));
                return;
            }

            QueryStringDecoder decoder = new QueryStringDecoder(request.uri(), StandardCharsets.UTF_8);
            String path = decoder.path();

            try {
                if ("/api/health".equals(path)) {
                    handleHealth(ctx, request);
                    return;
                }
                if ("/api/sync".equals(path)) {
                    handleSync(ctx, request);
                    return;
                }
                if ("/api/chunk".equals(path)) {
                    handleChunk(ctx, request, decoder.parameters());
                    return;
                }
                sendJson(ctx, request, HttpResponseStatus.NOT_FOUND, gson.toJson(Map.of("error", "not_found")));
            } catch (IllegalArgumentException e) {
                sendJson(ctx, request, HttpResponseStatus.BAD_REQUEST, gson.toJson(Map.of("error", e.getMessage())));
            } catch (Exception e) {
                e.printStackTrace(System.err);
                sendJson(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, gson.toJson(Map.of("error", "server_error")));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace(System.err);
            ctx.close();
        }

        private void handleHealth(ChannelHandlerContext ctx, FullHttpRequest request) {
            if (!HttpMethod.GET.equals(request.method())) {
                sendJson(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, gson.toJson(Map.of("error", "method_not_allowed")));
                return;
            }
            sendJson(ctx, request, HttpResponseStatus.OK, gson.toJson(Map.of("status", "ok")));
        }

        private void handleSync(ChannelHandlerContext ctx, FullHttpRequest request) throws IOException {
            if (!HttpMethod.POST.equals(request.method())) {
                sendJson(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, gson.toJson(Map.of("error", "method_not_allowed")));
                return;
            }

            String body = request.content().toString(StandardCharsets.UTF_8);
            SyncRequest syncRequest = gson.fromJson(body, SyncRequest.class);
            SyncResponse syncResponse = repository.sync(syncRequest);
            sendJson(ctx, request, HttpResponseStatus.OK, gson.toJson(syncResponse));
        }

        private void handleChunk(ChannelHandlerContext ctx, FullHttpRequest request, Map<String, List<String>> query) throws IOException {
            if (!HttpMethod.POST.equals(request.method()) && !HttpMethod.PUT.equals(request.method())) {
                sendJson(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, gson.toJson(Map.of("error", "method_not_allowed")));
                return;
            }
            long startedNanos = System.nanoTime();

            String clientId = requireParam(query, "clientId");
            String fileName = requireParam(query, "fileName");
            int index = Integer.parseInt(requireParam(query, "index"));
            int chunkSize = Integer.parseInt(requireParam(query, "chunkSize"));
            String chunkHash = requireParam(query, "chunkHash");
            if (index < 0) {
                throw new IllegalArgumentException("index must be >= 0");
            }
            if (chunkSize <= 0) {
                throw new IllegalArgumentException("chunkSize must be > 0");
            }

            byte[] data = ByteBufUtil.getBytes(request.content());
            uploadThrottle.throttleBytes(data.length);
            ChunkWriteResult result = repository.putChunk(clientId, fileName, index, chunkSize, chunkHash, data);
            long elapsedNanos = Math.max(1L, System.nanoTime() - startedNanos);
            String speed = formatBytesPerSecond(result.bytesStored, elapsedNanos);
            System.out.printf("[UploadServer] chunk received client=%s file=%s index=%d bytes=%d avgSpeed=%s hash=%s storedChunks=%d%n",
                    clientId,
                    result.normalizedFileName,
                    index,
                    result.bytesStored,
                    speed,
                    abbreviateHash(result.actualHash),
                    result.storedChunks);
            sendJson(ctx, request, HttpResponseStatus.OK, gson.toJson(Map.of("accepted", true)));
        }

        private static String requireParam(Map<String, List<String>> query, String key) {
            List<String> values = query.get(key);
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("missing query parameter: " + key);
            }
            String value = values.getFirst();
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing query parameter: " + key);
            }
            return value;
        }

        private static void sendJson(ChannelHandlerContext ctx, FullHttpRequest request,
                                     HttpResponseStatus status, String body) {
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    status,
                    Unpooled.wrappedBuffer(bodyBytes)
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bodyBytes.length);

            boolean keepAlive = HttpUtil.isKeepAlive(request);
            if (keepAlive) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                ctx.writeAndFlush(response);
            } else {
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    private static long parseThrottleBitsPerSecond(String[] args) {
        for (String arg : args) {
            if ("--throttle-1mbit".equals(arg)) {
                return 1_000_000L;
            }
            if (arg.startsWith("--throttle-mbit=")) {
                String raw = arg.substring("--throttle-mbit=".length()).trim();
                try {
                    double mbit = Double.parseDouble(raw);
                    if (mbit > 0.0d) {
                        return Math.max(1L, Math.round(mbit * 1_000_000d));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        String fromProperty = System.getProperty("upload.throttle.mbit", "").trim();
        if (!fromProperty.isBlank()) {
            try {
                double mbit = Double.parseDouble(fromProperty);
                if (mbit > 0.0d) {
                    return Math.max(1L, Math.round(mbit * 1_000_000d));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private static final class UploadThrottle {
        private final long bitsPerSecond;
        private long nextFreeNanos;

        private UploadThrottle(long bitsPerSecond) {
            this.bitsPerSecond = Math.max(0L, bitsPerSecond);
        }

        private void throttleBytes(int byteCount) {
            if (bitsPerSecond <= 0L || byteCount <= 0) {
                return;
            }

            long sleepNanos;
            synchronized (this) {
                long now = System.nanoTime();
                if (nextFreeNanos < now) {
                    nextFreeNanos = now;
                }
                long transmitNanos = (long) byteCount * 8_000_000_000L / bitsPerSecond;
                long scheduledStart = nextFreeNanos;
                nextFreeNanos = nextFreeNanos + Math.max(1L, transmitNanos);
                sleepNanos = scheduledStart - now;
            }

            if (sleepNanos > 0L) {
                long millis = sleepNanos / 1_000_000L;
                int nanos = (int) (sleepNanos % 1_000_000L);
                try {
                    Thread.sleep(millis, nanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public static final class SyncRequest {
        public String clientId;
        public String fileName;
        public int chunkSize;
        public long totalSize;
        public boolean complete;
        public String fullHash;
        public List<String> chunkHashes;
    }

    public static final class SyncResponse {
        public List<Integer> missingChunks = new ArrayList<>();
        public boolean completeOnServer;
    }

    private static final class ChunkWriteResult {
        final String normalizedFileName;
        final String actualHash;
        final int storedChunks;
        final int bytesStored;

        private ChunkWriteResult(String normalizedFileName, String actualHash, int storedChunks, int bytesStored) {
            this.normalizedFileName = normalizedFileName;
            this.actualHash = actualHash;
            this.storedChunks = storedChunks;
            this.bytesStored = bytesStored;
        }
    }

    private static final class UploadRepository {
        private final Path dataRoot;
        private final Gson gson;
        private final Map<String, FileUploadState> stateCache = new TreeMap<>();

        private UploadRepository(Path dataRoot, Gson gson) throws IOException {
            this.dataRoot = dataRoot;
            this.gson = gson;
            Files.createDirectories(dataRoot);
        }

        public synchronized SyncResponse sync(SyncRequest request) throws IOException {
            validateSyncRequest(request);
            String normalizedFileName = normalizeFileName(request.fileName);
            FileUploadState state = loadState(request.clientId, normalizedFileName);

            state.chunkSize = request.chunkSize;
            state.totalSize = Math.max(0L, request.totalSize);
            state.complete = request.complete;
            state.fullHash = request.fullHash == null ? "" : request.fullHash;

            List<String> hashes = request.chunkHashes == null ? List.of() : request.chunkHashes;
            for (int i = 0; i < hashes.size(); i++) {
                String chunkHash = hashes.get(i);
                if (chunkHash != null && !chunkHash.isBlank()) {
                    state.expectedChunkHashes.put(i, chunkHash);
                }
            }

            int knownChunkCount = hashes.size();
            int expectedChunkCount = request.complete
                    ? chunkCountForSize(state.totalSize, state.chunkSize)
                    : knownChunkCount;

            List<Integer> missing = computeMissing(state, expectedChunkCount);
            boolean fullHashMatches = true;

            if (request.complete && missing.isEmpty() && request.fullHash != null && !request.fullHash.isBlank()) {
                Path filePath = fileDataPath(request.clientId, normalizedFileName);
                if (!Files.exists(filePath)) {
                    missing = allIndexes(expectedChunkCount);
                    fullHashMatches = false;
                } else {
                    String actualFullHash = sha256Hex(filePath, state.totalSize);
                    if (!request.fullHash.equals(actualFullHash)) {
                        fullHashMatches = false;
                        List<Integer> mismatching = findMismatchingChunks(filePath, state, expectedChunkCount);
                        if (!mismatching.isEmpty()) {
                            for (Integer index : mismatching) {
                                state.receivedChunkHashes.remove(index);
                            }
                            missing = new ArrayList<>(mismatching);
                        }
                    }
                }
            }

            SyncResponse response = new SyncResponse();
            response.missingChunks = missing;
            response.completeOnServer = request.complete && missing.isEmpty() && fullHashMatches;

            if (response.completeOnServer) {
                if (!state.serverCompleteLogged) {
                    state.serverCompleteLogged = true;
                    System.out.printf("[UploadServer] file complete client=%s file=%s size=%d chunks=%d fullHash=%s%n",
                            request.clientId,
                            normalizedFileName,
                            state.totalSize,
                            chunkCountForSize(state.totalSize, state.chunkSize),
                            abbreviateHash(state.fullHash));
                }
            } else if (state.serverCompleteLogged) {
                state.serverCompleteLogged = false;
            }

            saveState(state);
            return response;
        }

        public synchronized ChunkWriteResult putChunk(String clientId, String fileName, int index,
                                                      int chunkSize, String chunkHash, byte[] data) throws IOException {
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalArgumentException("clientId is required");
            }
            if (data.length == 0) {
                throw new IllegalArgumentException("chunk data is empty");
            }
            String normalizedFileName = normalizeFileName(fileName);
            FileUploadState state = loadState(clientId, normalizedFileName);
            if (state.chunkSize > 0 && state.chunkSize != chunkSize) {
                throw new IllegalArgumentException("chunkSize mismatch for existing file state");
            }
            state.chunkSize = chunkSize;

            String actualHash = sha256Hex(data);
            if (!chunkHash.equals(actualHash)) {
                throw new IllegalArgumentException("chunk checksum mismatch");
            }

            Path filePath = fileDataPath(clientId, normalizedFileName);
            Files.createDirectories(filePath.getParent());
            try (var raf = new java.io.RandomAccessFile(filePath.toFile(), "rw")) {
                long offset = (long) index * (long) chunkSize;
                raf.seek(offset);
                raf.write(data);
            }

            state.receivedChunkHashes.put(index, actualHash);
            saveState(state);
            return new ChunkWriteResult(normalizedFileName, actualHash, state.receivedChunkHashes.size(), data.length);
        }

        private void validateSyncRequest(SyncRequest request) {
            if (request == null) {
                throw new IllegalArgumentException("empty request");
            }
            if (request.clientId == null || request.clientId.isBlank()) {
                throw new IllegalArgumentException("clientId is required");
            }
            if (request.fileName == null || request.fileName.isBlank()) {
                throw new IllegalArgumentException("fileName is required");
            }
            if (request.chunkSize <= 0) {
                throw new IllegalArgumentException("chunkSize must be > 0");
            }
            if (request.totalSize < 0) {
                throw new IllegalArgumentException("totalSize must be >= 0");
            }
        }

        private FileUploadState loadState(String clientId, String normalizedFileName) throws IOException {
            String key = clientId + "|" + normalizedFileName;
            FileUploadState cached = stateCache.get(key);
            if (cached != null) {
                return cached;
            }

            Path statePath = statePath(clientId, normalizedFileName);
            FileUploadState state;
            if (Files.exists(statePath)) {
                String json = Files.readString(statePath, StandardCharsets.UTF_8);
                state = gson.fromJson(json, FileUploadState.class);
                if (state == null) {
                    state = new FileUploadState();
                }
            } else {
                state = new FileUploadState();
            }

            if (state.expectedChunkHashes == null) {
                state.expectedChunkHashes = new TreeMap<>();
            }
            if (state.receivedChunkHashes == null) {
                state.receivedChunkHashes = new TreeMap<>();
            }

            state.clientId = clientId;
            state.fileName = normalizedFileName;
            stateCache.put(key, state);
            return state;
        }

        private void saveState(FileUploadState state) throws IOException {
            Objects.requireNonNull(state.clientId, "state.clientId");
            Objects.requireNonNull(state.fileName, "state.fileName");

            Path statePath = statePath(state.clientId, state.fileName);
            Files.createDirectories(statePath.getParent());
            Path tmpPath = statePath.resolveSibling(statePath.getFileName().toString() + ".tmp");
            String json = gson.toJson(state);
            Files.writeString(tmpPath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmpPath, statePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tmpPath, statePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private Path statePath(String clientId, String normalizedFileName) {
            String safeClient = sanitizePathSegment(clientId);
            String stateName = sha256Hex(normalizedFileName.getBytes(StandardCharsets.UTF_8)) + ".json";
            return dataRoot.resolve(safeClient).resolve("meta").resolve(stateName);
        }

        private Path fileDataPath(String clientId, String normalizedFileName) {
            String safeClient = sanitizePathSegment(clientId);
            Path filesRoot = dataRoot.resolve(safeClient).resolve("files").toAbsolutePath().normalize();
            Path resolved = filesRoot.resolve(normalizedFileName).normalize();
            if (!resolved.startsWith(filesRoot)) {
                throw new IllegalArgumentException("invalid fileName");
            }
            return resolved;
        }

        private static String normalizeFileName(String fileName) {
            Path path = Path.of(fileName).normalize();
            if (path.isAbsolute()) {
                throw new IllegalArgumentException("fileName must be relative");
            }
            String normalized = path.toString().replace('\\', '/');
            if (normalized.isBlank() || normalized.equals(".") || normalized.equals("..") || normalized.startsWith("../")) {
                throw new IllegalArgumentException("invalid fileName");
            }
            return normalized;
        }

        private static String sanitizePathSegment(String input) {
            return input.replaceAll("[^a-zA-Z0-9._-]", "_");
        }

        private static int chunkCountForSize(long size, int chunkSize) {
            if (size <= 0) {
                return 0;
            }
            return (int) ((size + chunkSize - 1L) / chunkSize);
        }

        private static List<Integer> computeMissing(FileUploadState state, int expectedChunkCount) {
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < expectedChunkCount; i++) {
                String expected = state.expectedChunkHashes.get(i);
                String received = state.receivedChunkHashes.get(i);
                if (received == null) {
                    missing.add(i);
                    continue;
                }
                if (expected != null && !expected.equals(received)) {
                    state.receivedChunkHashes.remove(i);
                    missing.add(i);
                }
            }
            return missing;
        }

        private static List<Integer> allIndexes(int count) {
            List<Integer> list = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                list.add(i);
            }
            return list;
        }

        private static List<Integer> findMismatchingChunks(Path filePath, FileUploadState state, int chunkCount) throws IOException {
            List<Integer> mismatching = new ArrayList<>();
            for (int i = 0; i < chunkCount; i++) {
                String expected = state.expectedChunkHashes.get(i);
                if (expected == null || expected.isBlank()) {
                    continue;
                }
                String actual = chunkHashFromFile(filePath, i, state.chunkSize, state.totalSize);
                if (actual == null || !expected.equals(actual)) {
                    mismatching.add(i);
                }
            }
            return mismatching;
        }

        private static String chunkHashFromFile(Path filePath, int index, int chunkSize, long totalSize) throws IOException {
            if (!Files.exists(filePath)) {
                return null;
            }
            long offset = (long) index * (long) chunkSize;
            if (offset >= totalSize) {
                return null;
            }
            int expectedLength = (int) Math.min(chunkSize, totalSize - offset);
            byte[] buffer = new byte[expectedLength];
            try (var raf = new java.io.RandomAccessFile(filePath.toFile(), "r")) {
                if (raf.length() < offset + expectedLength) {
                    return null;
                }
                raf.seek(offset);
                raf.readFully(buffer);
            }
            return sha256Hex(buffer);
        }

        private static String sha256Hex(Path filePath, long maxBytes) throws IOException {
            MessageDigest digest = newSha256Digest();
            byte[] buffer = new byte[8192];
            long remaining = maxBytes;
            try (var in = Files.newInputStream(filePath, StandardOpenOption.READ)) {
                while (remaining > 0) {
                    int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) {
                        break;
                    }
                    digest.update(buffer, 0, read);
                    remaining -= read;
                }
            }
            return toHex(digest.digest());
        }

        private static String sha256Hex(byte[] data) {
            MessageDigest digest = newSha256Digest();
            digest.update(data);
            return toHex(digest.digest());
        }

        private static MessageDigest newSha256Digest() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }

        private static String toHex(byte[] data) {
            StringBuilder sb = new StringBuilder(data.length * 2);
            for (byte b : data) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }

    }

    private static String formatBytesPerSecond(long bytes, long elapsedNanos) {
        double bytesPerSecond = bytes <= 0L ? 0d : (bytes * 1_000_000_000d) / Math.max(1L, elapsedNanos);
        double value = bytesPerSecond;
        String[] units = {"B/s", "KiB/s", "MiB/s", "GiB/s", "TiB/s"};
        int unit = 0;
        while (value >= 1024d && unit < units.length - 1) {
            value /= 1024d;
            unit++;
        }
        if (unit == 0) {
            return String.format(Locale.US, "%.0f %s", value, units[unit]);
        }
        return String.format(Locale.US, "%.2f %s (%.0f B/s)", value, units[unit], bytesPerSecond);
    }

    private static String abbreviateHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "n/a";
        }
        if (hash.length() <= 16) {
            return hash;
        }
        return hash.substring(0, 16) + "...";
    }

    private static final class FileUploadState {
        String clientId;
        String fileName;
        int chunkSize;
        long totalSize;
        boolean complete;
        String fullHash;
        boolean serverCompleteLogged;
        Map<Integer, String> expectedChunkHashes = new TreeMap<>();
        Map<Integer, String> receivedChunkHashes = new TreeMap<>();
    }
}
