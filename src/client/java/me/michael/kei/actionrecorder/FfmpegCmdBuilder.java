package me.michael.kei.actionrecorder;

import org.lwjgl.opengl.GL11C;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FfmpegCmdBuilder {

    public enum Vendor { NVIDIA, AMD, INTEL, APPLE, OTHER }
    public enum Os { WINDOWS, LINUX, MAC, OTHER }

    /** Must be called on the render thread with a current GL context. */
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

    /**
     * Build an ffmpeg command that reads raw RGB24 frames from stdin and uses a GPU encoder when possible.
     * Pass the same width/height/fps you capture with.
     *
     * @param ffmpegPath path to ffmpeg executable
     * @param width  frame width
     * @param height frame height
     * @param fps    frames per second
     * @param outputFile output file (container determined by extension; .mp4 recommended)
     * @return argv list suitable for ProcessBuilder
     *
     * Note: Hardware encoders don't output RGB; they encode YUV (typically yuv420p).
     * If you require RGB-preserving compression, set forceRgbSoftware=true to use libx264rgb (CPU).
     */
    public static List<String> buildCmd(String ffmpegPath, int width, int height, int fps, Path outputFile) {
        return buildCmd(ffmpegPath, width, height, fps, outputFile, /*forceRgbSoftware*/ false, /*vaapiDevice*/ "/dev/dri/renderD128");
    }

    public static List<String> buildCmd(String ffmpegPath, int width, int height, int fps, Path outputFile,
                                        boolean forceRgbSoftware, String vaapiDevice) {
        Vendor vendor = detectGpuVendor();
        Os os = detectOs();

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
            addSoftwareRgbEncoder(cmd, cpuFilterChain);
        } else {
            switch (vendor) {
                case NVIDIA -> addNvenc(cmd, cpuFilterChain);
                case AMD -> {
                    if (os == Os.WINDOWS) addAmf(cmd, cpuFilterChain);
                    else                  addVaapi(cmd, vaapiDevice, vaapiFilterChain);
                }
                case INTEL -> {
                    if (os == Os.WINDOWS) addQsv(cmd, cpuFilterChain);
                    else                  addVaapi(cmd, vaapiDevice, vaapiFilterChain);
                }
                case APPLE -> addVideoToolbox(cmd, cpuFilterChain);
                default -> addLibx264(cmd, cpuFilterChain);
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

    /* ===========================
       Encoder choices + flags
       =========================== */

    private static void addNvenc(List<String> cmd, String cpuFilterChain) {
        // NVIDIA NVENC H.264
        cmd.addAll(Arrays.asList(
                "-an",
                "-vf", cpuFilterChain,
                "-c:v", "h264_nvenc",
                // Quality/latency trade-offs; adjust as needed:
                "-preset", "p4",          // p1 (fast) ... p7 (slow/best)
                "-tune", "hq",
                "-rc", "vbr",
                "-cq", "19",
                "-pix_fmt", "yuv420p"     // required by most players/hw decoders
        ));
    }

    private static void addAmf(List<String> cmd, String cpuFilterChain) {
        // AMD AMF (Windows)
        cmd.addAll(Arrays.asList(
                "-an",
                "-vf", cpuFilterChain,
                "-c:v", "h264_amf",
                "-quality", "quality",    // options: speed/balanced/quality
                "-rc", "vbr_latency",
                "-pix_fmt", "yuv420p"
        ));
    }

    private static void addQsv(List<String> cmd, String cpuFilterChain) {
        // Intel Quick Sync (Windows; Linux QSV needs extra device flags; we use VAAPI on Linux)
        cmd.addAll(Arrays.asList(
                "-an",
                "-vf", cpuFilterChain,
                "-c:v", "h264_qsv",
                "-global_quality", "23",
                "-look_ahead", "1",
                "-pix_fmt", "yuv420p"
        ));
    }

    private static void addVideoToolbox(List<String> cmd, String cpuFilterChain) {
        // Apple VideoToolbox (macOS)
        cmd.addAll(Arrays.asList(
                "-an",
                "-vf", cpuFilterChain,
                "-c:v", "h264_videotoolbox",
                "-b:v", "0",              // enable quality-based mode with -crf-like control via -q:v
                "-q:v", "70",
                "-pix_fmt", "yuv420p"
        ));
    }

    private static void addVaapi(List<String> cmd, String vaapiDevice, String vaapiFilterChain) {
        // VAAPI (Linux) for AMD/Intel iGPUs
        // Requires a working /dev/dri/renderD* device; adjust if necessary.
        cmd.addAll(Arrays.asList(
                "-an",
                "-vaapi_device", vaapiDevice,
                "-vf", vaapiFilterChain,
                "-c:v", "h264_vaapi",
                "-b:v", "0",              // use quality mode via global_quality
                "-global_quality", "24",
                "-pix_fmt", "nv12"
        ));
    }

    private static void addLibx264(List<String> cmd, String cpuFilterChain) {
        // CPU fallback (YUV). Good compatibility, smaller files than rawvideo.
        cmd.addAll(Arrays.asList(
                "-an",
                "-vf", cpuFilterChain,
                "-c:v", "libx264",
                "-preset", "medium",
                "-crf", "20",
                "-pix_fmt", "yuv420p"
        ));
    }

    private static void addSoftwareRgbEncoder(List<String> cmd, String cpuFilterChain) {
        // CPU software path that PRESERVES RGB data (NOT hardware-accelerated).
        // Container tip: MKV works well with libx264rgb.
        cmd.addAll(Arrays.asList(
                "-an",
                "-vf", cpuFilterChain,
                "-c:v", "libx264rgb",
                "-preset", "medium",
                "-crf", "18",
                "-pix_fmt", "rgb24"
        ));
    }

    private static boolean isLikelyMp4(Path outputFile) {
        String name = outputFile.getFileName().toString().toLowerCase();
        return name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".m4v");
    }
}
