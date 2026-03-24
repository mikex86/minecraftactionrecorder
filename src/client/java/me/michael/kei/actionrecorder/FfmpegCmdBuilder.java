package me.michael.kei.actionrecorder;

import org.lwjgl.opengl.GL11C;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FfmpegCmdBuilder {

    public enum Vendor { NVIDIA, AMD, INTEL, APPLE, OTHER }
    public enum Os { WINDOWS, LINUX, MAC, OTHER }

    private record StreamingProfile(
            int targetBitrateKbps,
            int maxBitrateKbps,
            int bufferSizeKbps,
            int gopSize,
            int nvencCq,
            int x264Crf,
            int rgbCrf,
            String nvencPreset
    ) {
    }

    public static Vendor detectGpuVendor() {
        String vendor = GL11C.glGetString(GL11C.GL_VENDOR);
        if (vendor == null) return Vendor.OTHER;
        vendor = vendor.toLowerCase();
        if (vendor.contains("nvidia")) return Vendor.NVIDIA;
        if (vendor.contains("advanced micro devices") || vendor.contains("amd") || vendor.contains("ati")) return Vendor.AMD;
        if (vendor.contains("intel")) return Vendor.INTEL;
        if (vendor.contains("apple")) return Vendor.APPLE;
        return Vendor.OTHER;
    }

    public static Os detectOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return Os.WINDOWS;
        if (os.contains("mac") || os.contains("darwin")) return Os.MAC;
        if (os.contains("nux") || os.contains("nix")) return Os.LINUX;
        return Os.OTHER;
    }

    public static List<String> buildCmd(String ffmpegPath, int width, int height, int fps, Path outputFile) {
        return buildCmd(ffmpegPath, width, height, fps, outputFile, /*forceRgbSoftware*/ false, /*vaapiDevice*/ "/dev/dri/renderD128");
    }

    public static List<String> buildCmd(String ffmpegPath, int width, int height, int fps, Path outputFile,
                                        boolean forceRgbSoftware, String vaapiDevice) {
        Vendor vendor = detectGpuVendor();
        Os os = detectOs();
        StreamingProfile profile = selectStreamingProfile(width, height, fps);

        List<String> cmd = new ArrayList<>(Arrays.asList(
                ffmpegPath,
                "-y",
                "-f", "rawvideo",
                "-pix_fmt", "rgb24",            // input is RGB24 from your capture
                "-video_size", width + "x" + height,
                "-framerate", String.valueOf(fps),
                "-i", "-"                       // stdin
        ));

        // Filter chain: vflip is commonly needed for OpenGL FBOs
        // For VAAPI we need to format->hwupload after CPU-side filters.
        String vaapiFilterChain = "vflip,format=nv12,hwupload";
        String cpuFilterChain   = "vflip";

        if (forceRgbSoftware) {
            addSoftwareRgbEncoder(cmd, cpuFilterChain, profile);
        } else {
            switch (vendor) {
                case NVIDIA -> addNvenc(cmd, cpuFilterChain, profile);
                case AMD -> {
                    if (os == Os.WINDOWS) addAmf(cmd, cpuFilterChain, profile);
                    else                  addVaapi(cmd, vaapiDevice, vaapiFilterChain, profile);
                }
                case INTEL -> {
                    if (os == Os.WINDOWS) addQsv(cmd, cpuFilterChain, profile);
                    else                  addVaapi(cmd, vaapiDevice, vaapiFilterChain, profile);
                }
                case APPLE -> addVideoToolbox(cmd, cpuFilterChain, profile);
                default -> addLibx264(cmd, cpuFilterChain, profile);
            }
        }

        // Common muxer nicety for MP4/MOV: enables progressive playback
        if (isLikelyMp4(outputFile)) {
            cmd.addAll(Arrays.asList("-movflags", "+faststart"));
        }

        // Output
        cmd.addAll(Arrays.asList(
                outputFile.toAbsolutePath().toString()
        ));
        return cmd;
    }

    private static void addNvenc(List<String> cmd, String cpuFilterChain, StreamingProfile profile) {
        // NVIDIA NVENC HEVC tuned for stream-friendly quality and keyframe cadence.
        cmd.addAll(Arrays.asList(
                "-an",
                "-vf", cpuFilterChain,
                "-c:v", "hevc_nvenc",
                "-preset", profile.nvencPreset(), // p1 (fast) ... p7 (slow/best)
                "-tune", "hq",
                "-profile:v", "main",
                "-rc", "vbr",
                "-cq", String.valueOf(profile.nvencCq()),
                "-b:v", kbps(profile.targetBitrateKbps()),
                "-maxrate", kbps(profile.maxBitrateKbps()),
                "-bufsize", kbps(profile.bufferSizeKbps()),
                "-rc-lookahead", "32",
                "-spatial_aq", "1",
                "-temporal_aq", "1",
                "-aq-strength", "10",
                "-bf", "3",
                "-g", String.valueOf(profile.gopSize()),
                "-pix_fmt", "yuv420p"     // broad decode compatibility
        ));
    }

    private static void addAmf(List<String> cmd, String cpuFilterChain, StreamingProfile profile) {
        // AMD AMF (Windows)
        cmd.addAll(Arrays.asList(
                "-an",
                "-vf", cpuFilterChain,
                "-c:v", "h264_amf",
                "-quality", "quality",    // options: speed/balanced/quality
                "-rc", "vbr_peak",
                "-b:v", kbps(profile.targetBitrateKbps()),
                "-maxrate", kbps(profile.maxBitrateKbps()),
                "-bufsize", kbps(profile.bufferSizeKbps()),
                "-g", String.valueOf(profile.gopSize()),
                "-pix_fmt", "yuv420p"
        ));
    }

    private static void addQsv(List<String> cmd, String cpuFilterChain, StreamingProfile profile) {
        // Intel Quick Sync (Windows; Linux QSV needs extra device flags; we use VAAPI on Linux)
        cmd.addAll(Arrays.asList(
                "-an",
                "-vf", cpuFilterChain,
                "-c:v", "h264_qsv",
                "-look_ahead", "1",
                "-b:v", kbps(profile.targetBitrateKbps()),
                "-maxrate", kbps(profile.maxBitrateKbps()),
                "-bufsize", kbps(profile.bufferSizeKbps()),
                "-g", String.valueOf(profile.gopSize()),
                "-pix_fmt", "yuv420p"
        ));
    }

    private static void addVideoToolbox(List<String> cmd, String cpuFilterChain, StreamingProfile profile) {
        // Apple VideoToolbox (macOS)
        cmd.addAll(Arrays.asList(
                "-an",
                "-vf", cpuFilterChain,
                "-c:v", "h264_videotoolbox",
                "-b:v", kbps(profile.targetBitrateKbps()),
                "-maxrate", kbps(profile.maxBitrateKbps()),
                "-bufsize", kbps(profile.bufferSizeKbps()),
                "-g", String.valueOf(profile.gopSize()),
                "-pix_fmt", "yuv420p"
        ));
    }

    private static void addVaapi(List<String> cmd, String vaapiDevice, String vaapiFilterChain, StreamingProfile profile) {
        // VAAPI (Linux) for AMD/Intel iGPUs
        // Requires a working /dev/dri/renderD* device; adjust if necessary.
        cmd.addAll(Arrays.asList(
                "-an",
                "-vaapi_device", vaapiDevice,
                "-vf", vaapiFilterChain,
                "-c:v", "h264_vaapi",
                "-b:v", kbps(profile.targetBitrateKbps()),
                "-maxrate", kbps(profile.maxBitrateKbps()),
                "-bufsize", kbps(profile.bufferSizeKbps()),
                "-g", String.valueOf(profile.gopSize()),
                "-pix_fmt", "nv12"
        ));
    }

    private static void addLibx264(List<String> cmd, String cpuFilterChain, StreamingProfile profile) {
        // CPU fallback (YUV). Good compatibility, smaller files than rawvideo.
        cmd.addAll(Arrays.asList(
                "-an",
                "-vf", cpuFilterChain,
                "-c:v", "libx264",
                "-preset", "medium",
                "-crf", String.valueOf(profile.x264Crf()),
                "-maxrate", kbps(profile.maxBitrateKbps()),
                "-bufsize", kbps(profile.bufferSizeKbps()),
                "-g", String.valueOf(profile.gopSize()),
                "-keyint_min", String.valueOf(profile.gopSize()),
                "-sc_threshold", "0",
                "-pix_fmt", "yuv420p"
        ));
    }

    private static void addSoftwareRgbEncoder(List<String> cmd, String cpuFilterChain, StreamingProfile profile) {
        // CPU software path that PRESERVES RGB data (NOT hardware-accelerated).
        // Container tip: MKV works well with libx264rgb.
        cmd.addAll(Arrays.asList(
                "-an",
                "-vf", cpuFilterChain,
                "-c:v", "libx264rgb",
                "-preset", "medium",
                "-crf", String.valueOf(profile.rgbCrf()),
                "-g", String.valueOf(profile.gopSize()),
                "-keyint_min", String.valueOf(profile.gopSize()),
                "-sc_threshold", "0",
                "-pix_fmt", "rgb24"
        ));
    }

    private static StreamingProfile selectStreamingProfile(int width, int height, int fps) {
        long pixels = (long) width * (long) height;
        int safeFps = Math.max(fps, 1);

        int baseTargetKbps;
        int baseMaxKbps;
        int nvencCq;
        int x264Crf;
        int rgbCrf;
        String nvencPreset;

        // Bitrates are tuned toward broadly stream-friendly ranges and scaled by FPS below.
        if (pixels >= 3840L * 2160L) {
            baseTargetKbps = 28000;
            baseMaxKbps = 42000;
            nvencCq = 20;
            x264Crf = 18;
            rgbCrf = 16;
            nvencPreset = "p1";
        } else if (pixels >= 2560L * 1440L) {
            baseTargetKbps = 16000;
            baseMaxKbps = 24000;
            nvencCq = 22;
            x264Crf = 19;
            rgbCrf = 17;
            nvencPreset = "p1";
        } else if (pixels >= 1920L * 1080L) {
            baseTargetKbps = 9000;
            baseMaxKbps = 13500;
            nvencCq = 24;
            x264Crf = 20;
            rgbCrf = 18;
            nvencPreset = "p1";
        } else if (pixels >= 1280L * 720L) {
            baseTargetKbps = 4500;
            baseMaxKbps = 6750;
            nvencCq = 26;
            x264Crf = 22;
            rgbCrf = 19;
            nvencPreset = "p2";
        } else {
            baseTargetKbps = 2500;
            baseMaxKbps = 3750;
            nvencCq = 28;
            x264Crf = 23;
            rgbCrf = 20;
            nvencPreset = "p2";
        }

        double fpsScale;
        if (safeFps >= 120) {
            fpsScale = 1.90;
        } else if (safeFps >= 90) {
            fpsScale = 1.60;
        } else if (safeFps >= 60) {
            fpsScale = 1.35;
        } else if (safeFps >= 45) {
            fpsScale = 1.20;
        } else if (safeFps >= 30) {
            fpsScale = 1.00;
        } else {
            fpsScale = Math.max(0.70, safeFps / 30.0);
        }

        int targetBitrateKbps = Math.max(1000, (int) Math.round(baseTargetKbps * fpsScale));
        int maxBitrateKbps = Math.max(targetBitrateKbps + 1000, (int) Math.round(baseMaxKbps * fpsScale));
        int bufferSizeKbps = maxBitrateKbps * 2;
        int gopSize = Math.max(60, safeFps * 2); // ~2 second keyframe interval for streaming ingest.

        return new StreamingProfile(
                targetBitrateKbps,
                maxBitrateKbps,
                bufferSizeKbps,
                gopSize,
                nvencCq,
                x264Crf,
                rgbCrf,
                nvencPreset
        );
    }

    private static String kbps(int value) {
        return value + "k";
    }

    private static boolean isLikelyMp4(Path outputFile) {
        String name = outputFile.getFileName().toString().toLowerCase();
        return name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".m4v");
    }
}
