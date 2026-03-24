package me.michael.kei.actionrecorder;

import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.opengl.GL11C;
import oshi.SystemInfo;
import oshi.hardware.GraphicsCard;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class FfmpegRuntimeBootstrap {

    private static final String WINDOWS_FFMPEG_ZIP_URL =
            "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";
    private static final String WINDOWS_FFMPEG_ZIP_FILE = "ffmpeg-release-essentials.zip";
    private static final String WINDOWS_FFMPEG_EXTRACT_DIR = "ffmpeg-release-essentials";
    private static final String WINDOWS_FFMPEG_EXE_RELATIVE = "bin/ffmpeg.exe";
    private static final String MACOS_HOMEBREW_FFMPEG = "/opt/homebrew/bin/ffmpeg";

    private static volatile boolean initialized;
    private static volatile String ffmpegExecutable = "ffmpeg";
    private static volatile String startupError;

    private FfmpegRuntimeBootstrap() {
    }

    public static synchronized void initializeAtStartup() {
        if (initialized) {
            return;
        }
        initialized = true;
        startupError = null;
        ffmpegExecutable = "ffmpeg";

        FfmpegCmdBuilder.Os os = FfmpegCmdBuilder.detectOs();
        boolean nvidiaHost = hostHasNvidiaGpu();
        System.out.println("[FFmpegSetup] Startup check: os=" + os + ", nvidiaHost=" + nvidiaHost);

        if (os == FfmpegCmdBuilder.Os.WINDOWS) {
            try {
                Path bundled = ensureBundledWindowsFfmpeg(nvidiaHost);
                ProbeResult bundledProbe = probeFfmpeg(bundled.toString(), nvidiaHost);
                if (!bundledProbe.success) {
                    startupError = "Bundled FFmpeg check failed: " + bundledProbe.reason;
                    System.err.println("[FFmpegSetup] " + startupError);
                    return;
                }
                ffmpegExecutable = bundled.toString();
                System.out.println("[FFmpegSetup] Using bundled FFmpeg: " + ffmpegExecutable);
            } catch (Exception e) {
                startupError = "Failed to install bundled FFmpeg: " + e.getMessage();
                System.err.println("[FFmpegSetup] " + startupError);
            }
            return;
        }

        ProbeResult pathProbe = probeFfmpeg("ffmpeg", nvidiaHost);
        if (pathProbe.success) {
            System.out.println("[FFmpegSetup] Using ffmpeg from PATH");
            return;
        }

        if (os == FfmpegCmdBuilder.Os.MAC) {
            ProbeResult homebrewProbe = probeFfmpeg(MACOS_HOMEBREW_FFMPEG, nvidiaHost);
            if (homebrewProbe.success) {
                ffmpegExecutable = MACOS_HOMEBREW_FFMPEG;
                System.out.println("[FFmpegSetup] Using Homebrew FFmpeg: " + ffmpegExecutable);
                return;
            }
            startupError = "FFmpeg setup failed: PATH probe: " + pathProbe.reason
                    + "; Homebrew probe (" + MACOS_HOMEBREW_FFMPEG + "): " + homebrewProbe.reason;
            System.err.println("[FFmpegSetup] " + startupError);
            return;
        }

        // On Linux (and other non-Windows hosts), user is responsible for ffmpeg in PATH.
        startupError = "FFmpeg setup failed: " + pathProbe.reason;
        System.err.println("[FFmpegSetup] " + startupError);
    }

    public static String getFfmpegExecutable() {
        return ffmpegExecutable;
    }

    public static String getStartupError() {
        return startupError;
    }

    public static boolean shouldShowLinuxInstallErrorScreen() {
        return FfmpegCmdBuilder.detectOs() == FfmpegCmdBuilder.Os.LINUX && startupError != null;
    }

    public static boolean isReady() {
        return startupError == null;
    }

    private static boolean hostHasNvidiaGpu() {
        // 1) Prefer OSHI hardware inventory first.
        try {
            SystemInfo info = new SystemInfo();
            List<GraphicsCard> cards = info.getHardware().getGraphicsCards();
            for (GraphicsCard card : cards) {
                String name = card.getName() == null ? "" : card.getName().toLowerCase(Locale.ROOT);
                String vendor = card.getVendor() == null ? "" : card.getVendor().toLowerCase(Locale.ROOT);
                String deviceId = card.getDeviceId() == null ? "" : card.getDeviceId().toLowerCase(Locale.ROOT);
                if (name.contains("nvidia") || vendor.contains("nvidia") || deviceId.contains("10de")) {
                    System.out.println("[FFmpegSetup] NVIDIA detected via OSHI: " + card.getName() + " / " + card.getVendor());
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        // 2) Fallback: inspect OpenGL strings from the current context.
        try {
            String glVendor = GL11C.glGetString(GL11C.GL_VENDOR);
            String glRenderer = GL11C.glGetString(GL11C.GL_RENDERER);
            String vendorLower = glVendor == null ? "" : glVendor.toLowerCase(Locale.ROOT);
            String rendererLower = glRenderer == null ? "" : glRenderer.toLowerCase(Locale.ROOT);
            if (vendorLower.contains("nvidia") || rendererLower.contains("nvidia")) {
                System.out.println("[FFmpegSetup] NVIDIA detected via OpenGL: vendor='" + glVendor
                        + "', renderer='" + glRenderer + "'");
                return true;
            }
        } catch (Throwable ignored) {
        }

        System.out.println("[FFmpegSetup] NVIDIA not detected via OSHI or OpenGL");
        return false;
    }

    private static ProbeResult probeFfmpeg(String executable, boolean requireNvenc) {
        List<String> outputLines = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder(executable, "-hide_banner", "-encoders");
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            try (InputStream in = process.getInputStream()) {
                String text = new String(in.readAllBytes());
                for (String line : text.split("\\R")) {
                    outputLines.add(line);
                }
            }
            boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ProbeResult.fail("ffmpeg probe timed out");
            }
            if (process.exitValue() != 0) {
                return ProbeResult.fail("ffmpeg -encoders exited with " + process.exitValue());
            }
        } catch (IOException e) {
            return ProbeResult.fail("ffmpeg not found in PATH");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeResult.fail("ffmpeg probe interrupted");
        }

        if (!requireNvenc) {
            return ProbeResult.ok();
        }
        boolean nvencPresent = outputLines.stream()
                .map(line -> line.toLowerCase(Locale.ROOT))
                .anyMatch(line -> line.contains("h264_nvenc") || line.contains("hevc_nvenc"));
        if (!nvencPresent) {
            return ProbeResult.fail("host has NVIDIA GPU but ffmpeg does not expose NVENC encoders");
        }
        return ProbeResult.ok();
    }

    private static Path ensureBundledWindowsFfmpeg(boolean requireNvenc) throws IOException, InterruptedException {
        Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path runtimeRoot = gameDir.resolve("mcactionrec");
        Path zipPath = runtimeRoot.resolve(WINDOWS_FFMPEG_ZIP_FILE);
        Path extractedRoot = runtimeRoot.resolve(WINDOWS_FFMPEG_EXTRACT_DIR);
        Path ffmpegExe = extractedRoot.resolve(WINDOWS_FFMPEG_EXE_RELATIVE);

        Files.createDirectories(runtimeRoot);

        ProbeResult existingProbe = probeFfmpeg(ffmpegExe.toString(), requireNvenc);
        if (Files.isRegularFile(ffmpegExe) && existingProbe.success) {
            System.out.println("[FFmpegSetup] Resolved ffmpeg executable path: " + ffmpegExe);
            return ffmpegExe;
        }

        System.out.println("[FFmpegSetup] Downloading FFmpeg for Windows: " + WINDOWS_FFMPEG_ZIP_URL);
        downloadFile(WINDOWS_FFMPEG_ZIP_URL, zipPath);
        extractWindowsZipLayout(zipPath, extractedRoot);

        if (!Files.isRegularFile(ffmpegExe)) {
            throw new IOException("Expected FFmpeg binary missing after extraction: " + ffmpegExe);
        }
        System.out.println("[FFmpegSetup] Resolved ffmpeg executable path: " + ffmpegExe);
        return ffmpegExe;
    }

    private static void downloadFile(String url, Path destination) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        HttpResponse<Path> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofFile(destination,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.WRITE));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("download failed with HTTP " + response.statusCode());
        }
    }

    private static void extractWindowsZipLayout(Path zipPath, Path destinationRoot) throws IOException {
        ZipLayout layout = inspectZipLayout(zipPath);
        if (!layout.binDirectoryEntryPresent) {
            throw new IOException("Zip is missing an explicit '" + layout.rootPrefix + "/bin/' entry");
        }
        if (!layout.ffmpegEntryPresent) {
            throw new IOException("Zip is missing '" + layout.rootPrefix + "/bin/ffmpeg.exe' entry");
        }
        System.out.println("[FFmpegSetup] FFmpeg zip entry path: " + layout.rootPrefix + "/bin/ffmpeg.exe");

        deleteDirectoryIfExists(destinationRoot);
        Files.createDirectories(destinationRoot);

        String rootPrefix = layout.rootPrefix + "/";
        try (InputStream in = Files.newInputStream(zipPath); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = normalizeZipEntry(entry.getName());
                if (!name.startsWith(rootPrefix)) {
                    zip.closeEntry();
                    continue;
                }

                String relativeName = name.substring(rootPrefix.length());
                if (relativeName.isEmpty()) {
                    zip.closeEntry();
                    continue;
                }

                Path target = destinationRoot.resolve(relativeName).normalize();
                if (!target.startsWith(destinationRoot)) {
                    throw new IOException("Refusing zip-slip path: " + name);
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    zip.closeEntry();
                    continue;
                }

                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    zip.transferTo(out);
                }
                zip.closeEntry();
            }
        }
    }

    private static ZipLayout inspectZipLayout(Path zipPath) throws IOException {
        String rootPrefix = null;
        Set<String> names = new HashSet<>();

        try (InputStream in = Files.newInputStream(zipPath); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = normalizeZipEntry(entry.getName());
                if (name.isBlank()) {
                    continue;
                }
                names.add(name);

                String segment = firstSegment(name);
                if (rootPrefix == null) {
                    rootPrefix = segment;
                } else if (!rootPrefix.equals(segment)) {
                    throw new IOException("Zip contains multiple top-level roots; expected one");
                }
            }
        }

        if (rootPrefix == null || rootPrefix.isBlank()) {
            throw new IOException("Zip is empty");
        }

        String binDirEntry = rootPrefix + "/bin/";
        String ffmpegEntry = rootPrefix + "/bin/ffmpeg.exe";
        return new ZipLayout(rootPrefix, names.contains(binDirEntry), names.contains(ffmpegEntry));
    }

    private static String normalizeZipEntry(String name) {
        return name.replace('\\', '/');
    }

    private static String firstSegment(String name) {
        int slash = name.indexOf('/');
        if (slash < 0) {
            return name;
        }
        return name.substring(0, slash);
    }

    private static void deleteDirectoryIfExists(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    public record ProbeResult(boolean success, String reason) {
        static ProbeResult ok() {
            return new ProbeResult(true, "");
        }

        static ProbeResult fail(String reason) {
            return new ProbeResult(false, reason);
        }
    }

    private record ZipLayout(String rootPrefix, boolean binDirectoryEntryPresent, boolean ffmpegEntryPresent) {
    }
}
