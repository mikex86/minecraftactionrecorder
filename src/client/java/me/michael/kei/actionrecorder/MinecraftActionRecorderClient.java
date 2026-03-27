package me.michael.kei.actionrecorder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.atomic.AtomicBoolean;

public class MinecraftActionRecorderClient implements ClientModInitializer {

	private static final AtomicBoolean SHUTDOWN_FLOW_STARTED = new AtomicBoolean(false);

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

		// Run synchronously during client stopping so there is one authoritative shutdown path.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> runShutdownFlow(client, uploadDaemon, "client_stopping"));
	}

	private static void runShutdownFlow(Minecraft client, RecordingUploadDaemon uploadDaemon, String source) {
		if (!SHUTDOWN_FLOW_STARTED.compareAndSet(false, true)) {
			System.out.println("[UploadDaemon] Shutdown flow already running; ignoring source=" + source);
			return;
		}
		try {
			long windowHandle = client.getWindow().handle();
			if (windowHandle != 0L) {
				GLFW.glfwHideWindow(windowHandle);
				System.out.println("[UploadDaemon] GLFW window hidden for shutdown upload drain");
			}
		} catch (Throwable t) {
			System.err.println("[UploadDaemon] Failed to hide GLFW window during shutdown: " + t);
		}

		try {
			ActionRecorder.shutdownRecording();
		} catch (Throwable t) {
			System.err.println("[UploadDaemon] Failed to finalize recording before upload drain: " + t);
		}
		uploadDaemon.blockUntilCurrentUploadsComplete();
	}

	private static void forceAwtUiMode() {
		String raw = System.getProperty("java.awt.headless");
		if ("true".equalsIgnoreCase(raw)) {
			System.setProperty("java.awt.headless", "false");
			System.out.println("[UploadDaemon] Overrode java.awt.headless=true to false for Swing initialization");
		}
	}
}
