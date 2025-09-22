package me.michael.kei.actionrecorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ActionRecorder {

    private static float lastYaw = 0;
    private static float lastPitch = 0;
    private static boolean lastInventoryOpenState = false;
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

    private static boolean dropItemPressed = false;
    private static boolean dropItemDown = false;
    private static boolean hotbarOnePressed = false;
    private static boolean hotbarOneDown = false;
    private static boolean hotbarTwoPressed = false;
    private static boolean hotbarTwoDown = false;
    private static boolean hotbarThreePressed = false;
    private static boolean hotbarThreeDown = false;
    private static boolean hotbarFourPressed = false;
    private static boolean hotbarFourDown = false;
    private static boolean hotbarFivePressed = false;
    private static boolean hotbarFiveDown = false;
    private static boolean hotbarSixPressed = false;
    private static boolean hotbarSixDown = false;
    private static boolean hotbarSevenPressed = false;
    private static boolean hotbarSevenDown = false;
    private static boolean hotbarEightPressed = false;
    private static boolean hotbarEightDown = false;
    private static boolean hotbarNinePressed = false;
    private static boolean hotbarNineDown = false;

    private static int hotbarIndex = 0;
    private static boolean hotbarMoveLeft = false;
    private static boolean hotbarMoveRight = false;

    private static boolean moveToOffhandPressed = false;
    private static boolean moveToOffhandDown = false;

    private static boolean playerMenuOpen = false;

    private static boolean openChatPressed = false;
    private static boolean openChatDown = false;

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
        yawDelta = yaw - lastYaw;
        lastYaw = yaw;
    }

    private static void trackPitch(float pitch) {
        if (lastPitch == pitch) {
            pitchDelta = 0;
            return;
        }
        pitchDelta = pitch - lastPitch;
        lastPitch = pitch;
    }

    private static void trackInventoryOpen(boolean inventoryOpenState) {
        if (lastInventoryOpenState == inventoryOpenState) {
            return;
        }
        lastInventoryOpenState = inventoryOpenState;
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

    private static void trackHotbarOne(boolean hotbarOne) {
        hotbarOnePressed = hotbarOneDown != hotbarOne && hotbarOne;
        hotbarOneDown = hotbarOne;
    }

    private static void trackHotbarTwo(boolean hotbarTwo) {
        hotbarTwoPressed = hotbarTwoDown != hotbarTwo && hotbarTwo;
        hotbarTwoDown = hotbarTwo;
    }

    private static void trackHotbarThree(boolean hotbarThree) {
        hotbarThreePressed = hotbarThreeDown != hotbarThree && hotbarThree;
        hotbarThreeDown = hotbarThree;
    }

    private static void trackHotbarFour(boolean hotbarFour) {
        hotbarFourPressed = hotbarFourDown != hotbarFour && hotbarFour;
        hotbarFourDown = hotbarFour;
    }

    private static void trackHotbarFive(boolean hotbarFive) {
        hotbarFivePressed = hotbarFiveDown != hotbarFive && hotbarFive;
        hotbarFiveDown = hotbarFive;
    }

    private static void trackHotbarSix(boolean hotbarSix) {
        hotbarSixPressed = hotbarSixDown != hotbarSix && hotbarSix;
        hotbarSixDown = hotbarSix;
    }

    private static void trackHotbarSeven(boolean hotbarSeven) {
        hotbarSevenPressed = hotbarSevenDown != hotbarSeven && hotbarSeven;
        hotbarSevenDown = hotbarSeven;
    }

    private static void trackHotbarEight(boolean hotbarEight) {
        hotbarEightPressed = hotbarEightDown != hotbarEight && hotbarEight;
        hotbarEightDown = hotbarEight;
    }

    private static void trackHotbarNine(boolean hotbarNine) {
        hotbarNinePressed = hotbarNineDown != hotbarNine && hotbarNine;
        hotbarNineDown = hotbarNine;
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

    private static void trackLeftClick(boolean leftClickState) {
        lastLeftClickPressed = lastLeftClickDown != leftClickState && leftClickState; // pressed state is only true on the frame the key is pressed down
        lastLeftClickDown = leftClickState;
    }

    private static void trackRightClick(boolean rightClickState) {
        lastRightClickPressed = lastRightClickDown != rightClickState && rightClickState; // pressed state is only true on the frame the key is pressed down
        lastRightClickDown = rightClickState;
    }

    private static final int TARGET_FRAME_RATE = 60;

    private static final int TARGET_WINDOW_WIDTH = 1920;
    private static final int TARGET_WINDOW_HEIGHT = 1080;

    private static final Timer timer = new Timer(TARGET_FRAME_RATE);

    public static void captureState(Minecraft minecraft) {
        // set to target resolution if not equal
        if (minecraft.getWindow().getWidth() != TARGET_WINDOW_WIDTH || minecraft.getWindow().getHeight() != TARGET_WINDOW_HEIGHT) {
            minecraft.getWindow().setWindowed(TARGET_WINDOW_WIDTH, TARGET_WINDOW_HEIGHT);
            minecraft.resizeDisplay();
            if (minecraft.getMainRenderTarget().width != TARGET_WINDOW_WIDTH || minecraft.getMainRenderTarget().height != TARGET_WINDOW_HEIGHT) {
                return;
            }
        }

        timer.advanceTime();

        for (int i = 0; i < timer.ticks; i++) {
            recordCaptureFrame(minecraft);
        }
    }

    private static boolean prevIsScreenOpen = false;
    private static long timeScreenOpened = 0;

    private static void recordCaptureFrame(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            pressedScreenKeys.clear();
            return;
        }

        saveFrame();

        trackLeftClick(minecraft.options.keyAttack.isDown());
        trackRightClick(minecraft.options.keyUse.isDown());

        trackYaw(player.getYRot());
        trackPitch(player.getXRot());
        trackInventoryOpen(minecraft.screen instanceof InventoryScreen);
        trackMoveForward(minecraft.options.keyUp.isDown() && minecraft.screen == null);
        trackMoveBackward(minecraft.options.keyDown.isDown() && minecraft.screen == null);
        trackMoveLeft(minecraft.options.keyLeft.isDown() && minecraft.screen == null);
        trackMoveRight(minecraft.options.keyRight.isDown() && minecraft.screen == null);
        trackCrouch((minecraft.options.keyShift.isDown() && minecraft.screen == null)
                ||
                (minecraft.options.keyShift.isDown() && (minecraft.screen != null) && (lastLeftClickPressed || lastRightClickPressed))
        ); // this one has an effect on click in the inventory screen

        trackSprint(minecraft.player.isSprinting()); // log actual sprinting state
        trackJump(minecraft.options.keyJump.isDown() && (minecraft.player.onGround() || minecraft.player.isInWater())); // only log jump if on ground or in water, where jump will move up
        trackDropItem(minecraft.options.keyDrop.isDown());

        // hotbar
        trackHotbarOne(minecraft.options.keyHotbarSlots[0].isDown());
        trackHotbarTwo(minecraft.options.keyHotbarSlots[1].isDown());
        trackHotbarThree(minecraft.options.keyHotbarSlots[2].isDown());
        trackHotbarFour(minecraft.options.keyHotbarSlots[3].isDown());
        trackHotbarFive(minecraft.options.keyHotbarSlots[4].isDown());
        trackHotbarSix(minecraft.options.keyHotbarSlots[5].isDown());
        trackHotbarSeven(minecraft.options.keyHotbarSlots[6].isDown());
        trackHotbarEight(minecraft.options.keyHotbarSlots[7].isDown());
        trackHotbarNine(minecraft.options.keyHotbarSlots[8].isDown());

        trackHotbarIndex(player.getInventory().getSelectedSlot());

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

        saveActionState();
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        pressedScreenKeys.clear();
    }

    private static byte[] frameBuffer = null;
    private static int frameBufferWidth = 0;
    private static int frameBufferHeight = 0;

    private static FfmpegPipeWriter videoWriter = null;
    private static ActionLogWriter logWriter = null;

    private static Path getNextCapturePath(String extension) throws IOException {
        String baseName = "capture";
        int index = 1;
        Path path;
        do {
            String fileName = String.format("captures/%s_%03d.%s", baseName, index, extension);
            path = Path.of(fileName);
            Files.createDirectories(path.getParent());
            index++;
        } while (path.toFile().exists());
        return path;
    }

    private static void saveFrame() {
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        if (frameBuffer == null || frameBufferWidth != width || frameBufferHeight != height) {
            frameBufferWidth = width;
            frameBufferHeight = height;
            frameBuffer = new byte[width * height * 3]; // rgb

            try {
                if (videoWriter != null) {
                    videoWriter.close();

                    videoWriter = null;
                }
                if (logWriter != null) {
                    logWriter.close();
                    logWriter = null;
                }
                videoWriter = new FfmpegPipeWriter(
                        "ffmpeg",
                        getNextCapturePath("mp4"),
                        width, height, TARGET_FRAME_RATE,
                        128
                );
                logWriter = new ActionLogWriter(getNextCapturePath("alog"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        FrameCapture.grabMainFramebufferRGB(frameBuffer);
        renderCursor(mc.screen != null, frameBuffer);
        videoWriter.pushFrame(frameBuffer);
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

    public static void saveActionState() {
        if (logWriter != null) {
            boolean[] states = new boolean[]{
                    // movement
                    lastForwardState,
                    lastBackwardState,
                    lastLeftState,
                    lastRightState,

                    lastInventoryOpenState,

                    lastCrouchState,
                    lastSprintState,
                    lastJumpState,
                    dropItemPressed,

                    // hotbar
                    hotbarOnePressed,
                    hotbarTwoPressed,
                    hotbarThreePressed,
                    hotbarFourPressed,
                    hotbarFivePressed,
                    hotbarSixPressed,
                    hotbarSevenPressed,
                    hotbarEightPressed,
                    hotbarNinePressed,

                    hotbarMoveLeft,
                    hotbarMoveRight,

                    // offhand
                    moveToOffhandPressed,

                    // player menu
                    playerMenuOpen,

                    // open chat
                    openChatPressed,

                    // mouse
                    lastLeftClickPressed,
                    lastRightClickPressed,
            };
            try {
                // pressedScreenKeys = List<Character>
                logWriter.logStates(states, new float[]{yawDelta, pitchDelta}, new double[]{cursorXDelta, cursorYDelta}, pressedScreenKeys);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
