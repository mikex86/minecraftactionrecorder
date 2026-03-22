package me.michael.kei.actionrecorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicBoolean;

public final class MinecraftActionRecorderClient {
    private static final AtomicBoolean CLIENT_BOOTSTRAPPED = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTDOWN_FLOW_STARTED = new AtomicBoolean(false);

    private static volatile Thread shutdownFlowThread;
    private static volatile RecordingUploadDaemon uploadDaemon;

    private MinecraftActionRecorderClient() {
    }

    public static void bootstrapClient(Minecraft client) {
        if (!CLIENT_BOOTSTRAPPED.compareAndSet(false, true)) {
            return;
        }

        forceAwtUiMode();
        System.out.println("[UploadDaemon] AWT property java.awt.headless=" + System.getProperty("java.awt.headless"));

        // Run once the first time the client tick loop executes so OpenGL is available.
        FfmpegRuntimeBootstrap.initializeAtStartup();
        if (FfmpegRuntimeBootstrap.shouldShowLinuxInstallErrorScreen()) {
            String details = FfmpegRuntimeBootstrap.getStartupError();
            AlertScreen screen = new AlertScreen(
                    client::stop,
                    Component.literal("FFmpeg Setup Required"),
                    Component.literal("Linux requires a system FFmpeg install (with NVENC on NVIDIA hosts).\n" + details)
            );
            client.setScreen(screen);
        }

        RecordingUploadDaemon daemon = RecordingUploadDaemon.getInstance();
        daemon.start();
        uploadDaemon = daemon;

        installShutdownHook();
    }

    public static void beginShutdownFlow(String source) {
        RecordingUploadDaemon daemon = uploadDaemon;
        if (daemon == null) {
            daemon = RecordingUploadDaemon.getInstance();
            uploadDaemon = daemon;
        }
        startShutdownFlowAsync(daemon, source);
    }

    private static void installShutdownHook() {
        if (!SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Fallback only; UI may not be available during JVM shutdown.
            beginShutdownFlow("shutdown_hook");
            awaitShutdownFlowCompletion();
        }, "recording-upload-shutdown"));
    }

    private static void startShutdownFlowAsync(RecordingUploadDaemon uploadDaemon, String source) {
        if (!SHUTDOWN_FLOW_STARTED.compareAndSet(false, true)) {
            return;
        }

        ActionRecorder.requestShutdownSignal();
        Thread shutdownThread = new Thread(() -> {
            System.out.println("[UploadDaemon] Starting shutdown flow from " + source);
            System.out.println("[UploadDaemon] Calling ActionRecorder.shutdownRecording()");
            ActionRecorder.shutdownRecording();
            System.out.println("[UploadDaemon] ActionRecorder.shutdownRecording() returned; starting drain UI");
            uploadDaemon.shutdownAndDrainWithUi();
        }, "recording-upload-shutdown-worker");
        shutdownThread.setDaemon(false);
        shutdownFlowThread = shutdownThread;
        shutdownThread.start();
    }

    private static void awaitShutdownFlowCompletion() {
        Thread worker = shutdownFlowThread;
        if (worker == null) {
            return;
        }

        System.out.println("[UploadDaemon] Waiting for shutdown flow to finish...");
        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join(1000L);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        System.out.println("[UploadDaemon] Shutdown flow finished");
    }

    private static void forceAwtUiMode() {
        String raw = System.getProperty("java.awt.headless");
        if ("true".equalsIgnoreCase(raw)) {
            System.setProperty("java.awt.headless", "false");
            System.out.println("[UploadDaemon] Overrode java.awt.headless=true to false for Swing initialization");
        }
    }
}
