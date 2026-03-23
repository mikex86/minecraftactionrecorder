package me.michael.kei.actionrecorder;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ActionRecorder {
    private static volatile boolean shutdownRequested = false;

    private static float lastYaw = 0;
    private static float lastPitch = 0;
    private static boolean lastScreenOpenState = false;
    private static boolean lastForwardState = false;
    private static boolean lastBackwardState = false;
    private static boolean lastLeftState = false;
    private static boolean lastRightState = false;
    private static boolean lastCrouchState = false;
    private static boolean lastSprintState = false;
    private static boolean lastJumpState = false;
    private static double lastCursorX = 0;
    private static double lastCursorY = 0;

    private static boolean lastLeftClickPressed = false;
    private static boolean lastLeftClickDown = false;
    private static boolean lastRightClickPressed = false;
    private static boolean lastRightClickDown = false;

    public static boolean guiLeftMouseClicked = false;
    public static boolean guiRightMouseClicked = false;

    public static boolean blockInteracted = false;
    public static boolean attackPerformed = false;

    private static boolean dropItemPressed = false;
    private static boolean dropItemDown = false;

    private static int hotbarIndex = 0;
    private static boolean hotbarMoveLeft = false;
    private static boolean hotbarMoveRight = false;

    private static boolean moveToOffhandPressed = false;
    private static boolean moveToOffhandDown = false;

    private static boolean playerMenuOpen = false;

    private static boolean openChatPressed = false;
    private static boolean openChatDown = false;

    /**
     * Last right click active has a very specific meaning.
     * Active means that the click is in fact currently performing some action within the game.
     * That means e.g. blocking with a shield, or drawing a bow, or the instant of a block interaction (including block place),
     * or an inventory right click.
     */
    private static boolean lastRightClickActive = false;

    /**
     * Last left click active has a very specific meaning.
     * Active means that the click is in fact currently performing some action within the game.
     * That means e.g. breaking a block, or the instant of a hit, or the instant of an inventory click.
     */
    private static boolean lastLeftClickActive = false;

    private static boolean isNormalPerspective = true;
    private static boolean isFrontF5Perspective = false;
    private static boolean isBackF5Perspective = false;

    // Per-frame typed characters when a Screen is open
    public static final List<String> pressedScreenKeys = new ArrayList<>();

    private static float yawDelta = 0;
    private static float pitchDelta = 0;
    private static double cursorXDelta = 0;
    private static double cursorYDelta = 0;

    private static void trackYaw(float yaw) {
        if (lastYaw == yaw) {
            yawDelta = 0;
            return;
        }
        yawDelta = (float) Math.toRadians(yaw - lastYaw); // log in units radians
        lastYaw = yaw;
    }

    private static void trackPitch(float pitch) {
        if (lastPitch == pitch) {
            pitchDelta = 0;
            return;
        }
        pitchDelta = (float) Math.toRadians(pitch - lastPitch);
        lastPitch = pitch;
    }

    private static void trackScreenOpen(boolean screenOpenState) {
        if (lastScreenOpenState == screenOpenState) {
            return;
        }
        lastScreenOpenState = screenOpenState;
    }

    private static void trackMoveForward(boolean forwardState) {
        if (lastForwardState == forwardState) {
            return;
        }
        lastForwardState = forwardState;
    }

    private static void trackMoveBackward(boolean backwardState) {
        if (lastBackwardState == backwardState) {
            return;
        }
        lastBackwardState = backwardState;
    }

    private static void trackMoveLeft(boolean leftState) {
        if (lastLeftState == leftState) {
            return;
        }
        lastLeftState = leftState;
    }

    private static void trackMoveRight(boolean rightState) {
        if (lastRightState == rightState) {
            return;
        }
        lastRightState = rightState;
    }

    private static void trackCrouch(boolean crouchState) {
        if (lastCrouchState == crouchState) {
            return;
        }
        lastCrouchState = crouchState;
    }

    private static void trackSprint(boolean sprintState) {
        if (lastSprintState == sprintState) {
            return;
        }
        lastSprintState = sprintState;
    }

    private static void trackJump(boolean jumpState) {
        if (lastJumpState == jumpState) {
            return;
        }
        lastJumpState = jumpState;
    }

    private static void trackDropItem(boolean dropDown) {
        dropItemPressed = dropItemDown != dropDown && dropDown; // pressed state is only true on the frame the key is pressed down
        dropItemDown = dropDown;
    }

    private static void trackHotbarIndex(int index) {
        if (index != hotbarIndex) {
            int delta = index - hotbarIndex;
            if (delta == 1 || (delta == -8 && index == 0)) {
                hotbarMoveRight = true;
                hotbarMoveLeft = false;
            } else if (delta == -1 || (delta == 8 && index == 8)) {
                hotbarMoveLeft = true;
                hotbarMoveRight = false;
            } else {
                hotbarMoveLeft = false;
                hotbarMoveRight = false;
            }
        } else {
            hotbarMoveLeft = false;
            hotbarMoveRight = false;
        }
        hotbarIndex = index;
    }

    private static void trackMoveToOffhand(boolean offhandDown) {
        moveToOffhandPressed = moveToOffhandDown != offhandDown && offhandDown; // pressed state is only true on the frame the key is pressed down
        moveToOffhandDown = offhandDown;
    }

    private static void trackPlayerMenuOpen(boolean playerMenuOpenState) {
        if (playerMenuOpen == playerMenuOpenState) {
            return;
        }
        playerMenuOpen = playerMenuOpenState;
    }

    private static void trackOpenChat(boolean openChatState) {
        openChatPressed = openChatDown != openChatState && openChatState; // pressed state is only true on the frame the key is pressed down
        openChatDown = openChatState;
    }

    private static void trackCursorMoveX(double cursorX) {
        if (lastCursorX == cursorX) {
            return;
        }
        cursorXDelta = cursorX - lastCursorX;
        lastCursorX = cursorX;
    }

    private static void trackCursorMoveY(double cursorY) {
        if (lastCursorY == cursorY) {
            return;
        }
        cursorYDelta = cursorY - lastCursorY;
        lastCursorY = cursorY;
    }

    private static void trackLeftClick(boolean leftClickState, Minecraft minecraft) {
        lastLeftClickPressed = lastLeftClickDown != leftClickState && leftClickState; // pressed state is only true on the frame the key is pressed down
        lastLeftClickDown = leftClickState;

        MultiPlayerGameMode gameMode = minecraft.gameMode;
        boolean isBreakingBlock = gameMode != null && gameMode.isDestroying();
        boolean anyScreen = minecraft.screen != null;
        lastLeftClickActive = attackPerformed || (lastLeftClickPressed && anyScreen) || isBreakingBlock;
    }

    private static void trackRightClick(boolean rightClickState, Minecraft minecraft) {
        lastRightClickPressed = lastRightClickDown != rightClickState && rightClickState; // pressed state is only true on the frame the key is pressed down
        lastRightClickDown = rightClickState;

        Player player = minecraft.player;
        boolean anyHeldItemInUse = player != null && player.isUsingItem();
        boolean anyScreen = minecraft.screen != null;
        lastRightClickActive = blockInteracted || (lastRightClickPressed && anyScreen) || anyHeldItemInUse;
    }

    private static final int TARGET_FRAME_RATE = 60;

    private static int lastWidth = 0;
    private static int lastHeight = 0;

    private static final Timer timer = new Timer(TARGET_FRAME_RATE);

    public static void captureState(Minecraft minecraft) {
        if (shutdownRequested) {
            return;
        }
        // InputFaker.doRandomInput();

        // set to target resolution if not equal
        // set to target resolution if not equal
        /*if (minecraft.getWindow().getWidth() != TARGET_WINDOW_WIDTH || minecraft.getWindow().getHeight() != TARGET_WINDOW_HEIGHT) {
            minecraft.getWindow().setWindowed(TARGET_WINDOW_WIDTH, TARGET_WINDOW_HEIGHT);
            minecraft.resizeDisplay();
            if (minecraft.getMainRenderTarget().width != TARGET_WINDOW_WIDTH || minecraft.getMainRenderTarget().height != TARGET_WINDOW_HEIGHT) {
                return;
            }
        }*/

        if (minecraft.getWindow().getWidth() != lastWidth || minecraft.getWindow().getHeight() != lastHeight) {
            lastWidth = minecraft.getWindow().getWidth();
            lastHeight = minecraft.getWindow().getHeight();
            resetRecording();
            return;
        }

        timer.advanceTime();

        for (int i = 0; i < timer.ticks; i++) {
            recordCaptureFrame(minecraft);
        }
    }

    private static boolean prevIsScreenOpen = false;
    private static long timeScreenOpened = 0;

    private static void resetRecording() {
        closeWritersAndMarkUploadsFinished();
        pressedScreenKeys.clear();
    }

    private static void recordCaptureFrame(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            resetRecording();
            return;
        }
        CameraType cameraType = Minecraft.getInstance().options.getCameraType();

        isNormalPerspective = cameraType == CameraType.FIRST_PERSON;
        isBackF5Perspective = cameraType == CameraType.THIRD_PERSON_BACK;
        isFrontF5Perspective = cameraType == CameraType.THIRD_PERSON_FRONT;

        trackLeftClick(minecraft.mouseHandler.isLeftPressed() || guiLeftMouseClicked, minecraft);
        trackRightClick(minecraft.mouseHandler.isRightPressed() || guiRightMouseClicked, minecraft);

        guiLeftMouseClicked = false;
        guiRightMouseClicked = false;

        blockInteracted = false;
        attackPerformed = false;

        trackYaw(player.getYRot());
        trackPitch(player.getXRot());

        trackScreenOpen(minecraft.screen != null);
        trackMoveForward(minecraft.options.keyUp.isDown() && minecraft.screen == null);
        trackMoveBackward(minecraft.options.keyDown.isDown() && minecraft.screen == null);
        trackMoveLeft(minecraft.options.keyLeft.isDown() && minecraft.screen == null);
        trackMoveRight(minecraft.options.keyRight.isDown() && minecraft.screen == null);
        trackCrouch((minecraft.options.keyShift.isDown() && minecraft.screen == null)
                ||
                (InputConstants.isKeyDown(minecraft.getWindow().getWindow(), InputConstants.KEY_LSHIFT) && (minecraft.screen != null) && (lastLeftClickActive || lastRightClickActive))
        ); // this one has an effect on click in the inventory screen

        trackJump(minecraft.options.keyJump.isDown() && (minecraft.player.onGround() || minecraft.player.isInWater() || minecraft.player.getAbilities().flying)); // only log jump if on ground or in water or when flying, where jump will move up
        trackDropItem(minecraft.options.keyDrop.isDown());
        trackSprint(minecraft.player.isSprinting() || (minecraft.options.keySprint.isDown() && dropItemPressed)); // log actual sprinting state

        trackHotbarIndex(player.getInventory().selected);

        trackMoveToOffhand(minecraft.options.keySwapOffhand.isDown());

        trackPlayerMenuOpen(minecraft.options.keyPlayerList.isDown());

        trackOpenChat(minecraft.options.keyChat.isDown());

        if (minecraft.screen != null) {
            if (!prevIsScreenOpen) {
                timeScreenOpened = System.currentTimeMillis();
            }
            if ((System.currentTimeMillis() - timeScreenOpened) < 10) {
                // clear all keys in the first 10ms after opening a screen... This is a bit of a hack
                // but it avoids reading the key as a typed character which opened the screen itself.
                pressedScreenKeys.clear();
            }
            double mouseX = minecraft.mouseHandler.xpos();
            double mouseY = minecraft.mouseHandler.ypos();
            trackCursorMoveX(mouseX);
            trackCursorMoveY(mouseY);
        } else {
            cursorXDelta = 0;
            cursorYDelta = 0;
            pressedScreenKeys.clear();
        }
        prevIsScreenOpen = minecraft.screen != null;

        saveFrame();
        saveActionState();
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        pressedScreenKeys.clear();
    }

    private static byte[] frameBuffer = null;
    private static int frameBufferWidth = 0;
    private static int frameBufferHeight = 0;

    private static FfmpegPipeWriter videoWriter = null;
    private static ActionLogWriter logWriter = null;
    private static Path currentVideoCapturePath = null;
    private static Path currentLogCapturePath = null;
    private static boolean ffmpegUnavailableLogged = false;

    private static Path createNextRecordingDirectory() throws IOException {
        Path capturesRoot = Path.of("captures");
        Files.createDirectories(capturesRoot);

        while (true) {
            Path recordingDir = capturesRoot.resolve(UUID.randomUUID().toString());
            if (Files.notExists(recordingDir)) {
                Files.createDirectories(recordingDir);
                return recordingDir;
            }
        }
    }

    private static synchronized void saveFrame() {
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        if (frameBuffer == null || frameBufferWidth != width || frameBufferHeight != height) {
            frameBufferWidth = width;
            frameBufferHeight = height;
            frameBuffer = new byte[width * height * 3]; // rgb

            try {
                closeWritersAndMarkUploadsFinished();
                Path recordingDir = createNextRecordingDirectory();
                Path videoPath = recordingDir.resolve("video.mp4");
                Path logPath = recordingDir.resolve("actions.alog");
                if (!FfmpegRuntimeBootstrap.isReady()) {
                    if (!ffmpegUnavailableLogged) {
                        ffmpegUnavailableLogged = true;
                        System.err.println("[ActionRecorder] FFmpeg unavailable; recording disabled: "
                                + FfmpegRuntimeBootstrap.getStartupError());
                    }
                    return;
                }
                String ffmpegExecutable = FfmpegRuntimeBootstrap.getFfmpegExecutable();
                videoWriter = new FfmpegPipeWriter(
                        ffmpegExecutable,
                        videoPath,
                        width, height, TARGET_FRAME_RATE,
                        128
                );
                logWriter = new ActionLogWriter(logPath);
                currentVideoCapturePath = videoPath;
                currentLogCapturePath = logPath;

                RecordingUploadDaemon uploadDaemon = RecordingUploadDaemon.getInstance();
                uploadDaemon.notifyRecordingStarted(videoPath);
                uploadDaemon.notifyRecordingStarted(logPath);

                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("[ActionRecorder] Started recording: " + recordingDir.getFileName()),
                            false
                    );
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        FrameCapture.grabMainFramebufferRGB(frameBuffer);
        renderCursor(mc.screen != null, frameBuffer);
        if (videoWriter != null) {
            videoWriter.pushFrame(frameBuffer);
        }
    }

    private static final String cursorResourcePath = "/assets/minecraftactionrecorder/cursor.png";

    private static final byte[] cursorRgba = readPngImage(cursorResourcePath);
    private static final int cursorWidth = 32;
    private static final int cursorHeight = 32;

    private static void renderCursor(boolean show, byte[] rgb) {
        if (!show) {
            return;
        }
        int windowWidth = frameBufferWidth;
        int windowHeight = frameBufferHeight;

        int mouseX = (int) lastCursorX;
        int mouseY = (int) (windowHeight - lastCursorY - cursorHeight);

        int startX = Math.max(0, mouseX);
        int startY = Math.max(0, mouseY);
        int endX = Math.min(windowWidth, mouseX + cursorWidth);
        int endY = Math.min(windowHeight, mouseY + cursorHeight);

        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int cursorX = x - mouseX;
                int cursorY = y - mouseY;
                int cursorIndex = (cursorX + cursorY * cursorWidth) * 4;
                int rgbIndex = (x + y * windowWidth) * 3;

                float alpha = (cursorRgba[cursorIndex + 3] & 0xFF) / 255.0f;
                if (alpha == 0) {
                    continue; // fully transparent
                }

                for (int c = 0; c < 3; c++) {
                    int cursorColor = cursorRgba[cursorIndex + c] & 0xFF;
                    int bgColor = rgb[rgbIndex + c] & 0xFF;
                    int blendedColor = (int) (cursorColor * alpha + bgColor * (1 - alpha));
                    rgb[rgbIndex + c] = (byte) blendedColor;
                }
            }
        }
    }

    private static byte[] readPngImage(String resourcePath) {
        try (var is = ActionRecorder.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            var img = javax.imageio.ImageIO.read(is);
            int w = img.getWidth();
            int h = img.getHeight();
            byte[] data = new byte[w * h * 4];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    int i = (x + (h - y - 1) * w) * 4;
                    data[i] = (byte) ((argb >> 16) & 0xFF); // R
                    data[i + 1] = (byte) ((argb >> 8) & 0xFF); // G
                    data[i + 2] = (byte) (argb & 0xFF); // B
                    data[i + 3] = (byte) ((argb >> 24) & 0xFF); // A
                }
            }
            return data;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read image: " + resourcePath, e);
        }
    }

    public static synchronized void saveActionState() {
        if (logWriter != null) {
            boolean[] states = new boolean[]{
                    // movement
                    lastForwardState,
                    lastBackwardState,
                    lastLeftState,
                    lastRightState,

                    lastScreenOpenState,

                    lastCrouchState,
                    lastSprintState,
                    lastJumpState,
                    dropItemPressed,

                    // hotbar
                    hotbarIndex == 0,
                    hotbarIndex == 1,
                    hotbarIndex == 2,
                    hotbarIndex == 3,
                    hotbarIndex == 4,
                    hotbarIndex == 5,
                    hotbarIndex == 6,
                    hotbarIndex == 7,
                    hotbarIndex == 8,

                    hotbarMoveLeft,
                    hotbarMoveRight,

                    // offhand
                    moveToOffhandPressed,

                    // player menu
                    playerMenuOpen,

                    // open chat
                    openChatPressed,

                    // mouse
                    lastLeftClickActive,
                    lastRightClickActive,

                    // perspective
                    isNormalPerspective,
                    isFrontF5Perspective,
                    isBackF5Perspective
            };
            try {
                // pressedScreenKeys = List<Character>
                logWriter.logStates(states, new float[]{yawDelta, pitchDelta}, new double[]{cursorXDelta, cursorYDelta}, pressedScreenKeys);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static synchronized void shutdownRecording() {
        shutdownRequested = true;
        System.out.println("[ActionRecorder] shutdownRecording start");
        closeWritersAndMarkUploadsFinished();
        System.out.println("[ActionRecorder] shutdownRecording done");
    }

    public static void requestShutdownSignal() {
        shutdownRequested = true;
    }

    private static synchronized void closeWritersAndMarkUploadsFinished() {
        if (videoWriter == null && logWriter == null && currentVideoCapturePath == null && currentLogCapturePath == null) {
            return;
        }
        if (videoWriter != null) {
            videoWriter.close();
            videoWriter = null;
        }
        if (logWriter != null) {
            try {
                logWriter.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            logWriter = null;
        }

        RecordingUploadDaemon uploadDaemon = RecordingUploadDaemon.getInstance();
        if (currentVideoCapturePath != null) {
            uploadDaemon.notifyRecordingFinished(currentVideoCapturePath);
            currentVideoCapturePath = null;
        }
        if (currentLogCapturePath != null) {
            uploadDaemon.notifyRecordingFinished(currentLogCapturePath);
            currentLogCapturePath = null;
        }
    }
}
