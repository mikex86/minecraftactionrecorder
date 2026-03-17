package me.michael.kei.actionrecorder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicBoolean;

public class MinecraftActionRecorderClient implements ClientModInitializer {
	private static final AtomicBoolean SHUTDOWN_FLOW_STARTED = new AtomicBoolean(false);
	private static volatile Thread shutdownFlowThread;
	static {
		forceAwtUiMode();
	}

	@Override
	public void onInitializeClient() {
		System.out.println("[UploadDaemon] AWT property java.awt.headless=" + System.getProperty("java.awt.headless"));
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> client.execute(() -> {
			// Run after client start so OpenGL context is available for GPU vendor fallback detection.
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
		}));

		RecordingUploadDaemon uploadDaemon = RecordingUploadDaemon.getInstance();
		uploadDaemon.start();

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> startShutdownFlowAsync(uploadDaemon, "client_stopping"));

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			// Fallback only; UI may not be available during JVM shutdown.
			startShutdownFlowAsync(uploadDaemon, "shutdown_hook");
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
