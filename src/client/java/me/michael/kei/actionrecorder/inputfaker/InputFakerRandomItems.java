package me.michael.kei.actionrecorder.inputfaker;

import me.michael.kei.actionrecorder.AsyncFrameCapture;
import me.michael.kei.actionrecorder.FrameCapture;
import me.michael.kei.actionrecorder.Timer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;

public final class InputFakerRandomItems {

    private static final float TARGET_UPDATES_PER_SECOND = 60.0f;
    private static final int MAX_RANDOM_LEVEL = 60;
    private static final int DATASET_TARGET_HEIGHT = 240;
    private static final int DATASET_CAPTURE_DELAY_FRAMES = 2;

    private static final Timer timer = new Timer(TARGET_UPDATES_PER_SECOND);
    private static final Random random = new Random();
    private static final List<Item> RANDOM_ITEMS = BuiltInRegistries.ITEM.stream()
            .filter(item -> item != Items.AIR && item.getDefaultMaxStackSize() > 1)
            .toList();
    private static byte[] datasetFrameBuffer = null;
    private static int datasetFrameWidth = 0;
    private static int datasetFrameHeight = 0;
    private static long datasetFrameCounter = 0;
    private static boolean datasetWriteErrorLogged = false;
    private static final ArrayDeque<Integer> pendingDatasetSelectedSlots = new ArrayDeque<>();

    private InputFakerRandomItems() {
    }

    public static void doRandomInput() {
        Minecraft mc = Minecraft.getInstance();
        mc.mouseHandler.releaseMouse();

        if (pendingDatasetSelectedSlots.size() >= DATASET_CAPTURE_DELAY_FRAMES) {
            int slotToCapture = pendingDatasetSelectedSlots.removeFirst();
            saveHotbarDatasetFrame(mc, slotToCapture);
        }

        timer.advanceTime();
        int latestSelectedSlot = -1;
        for (int i = 0; i < timer.ticks; i++) {
            latestSelectedSlot = doTick();
        }
        if (latestSelectedSlot >= 0) {
            pendingDatasetSelectedSlots.addLast(latestSelectedSlot);
        }
    }

    private static int doTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || RANDOM_ITEMS.isEmpty()) {
            return -1;
        }

        Inventory inventory = player.getInventory();
        randomizeHotbar(inventory);
        int selectedSlot = random.nextInt(Inventory.getSelectionSize());
        inventory.setSelectedSlot(selectedSlot);
        boolean populateOffhand = random.nextBoolean();
        player.setItemSlot(EquipmentSlot.OFFHAND, populateOffhand ? randomStack() : ItemStack.EMPTY);
        randomizeGuiScale(mc);
        randomizeRotation(player);

        setRandomVitals(player);
        return selectedSlot;
    }

    private static void randomizeHotbar(Inventory inventory) {
        int hotbarSize = Inventory.getSelectionSize();
        for (int slot = 0; slot < hotbarSize; slot++) {
            inventory.setItem(slot, ItemStack.EMPTY);
        }

        int populatedSlotCount = random.nextInt(hotbarSize + 1);
        int[] slots = new int[hotbarSize];
        for (int i = 0; i < hotbarSize; i++) {
            slots[i] = i;
        }

        for (int i = 0; i < populatedSlotCount; i++) {
            int j = i + random.nextInt(hotbarSize - i);
            int temp = slots[i];
            slots[i] = slots[j];
            slots[j] = temp;
            inventory.setItem(slots[i], randomStack());
        }
    }

    private static void randomizeGuiScale(Minecraft mc) {
        int maxGuiScale = mc.getWindow().calculateScale(0, mc.isEnforceUnicode());
        if (maxGuiScale <= 0) {
            return;
        }
        int randomGuiScale = random.nextInt(maxGuiScale + 1);
        mc.options.guiScale().set(randomGuiScale);
    }

    private static ItemStack randomStack() {
        Item item = RANDOM_ITEMS.get(random.nextInt(RANDOM_ITEMS.size()));
        int maxStackSize = item.getDefaultMaxStackSize();
        int count = 1 + random.nextInt(maxStackSize - 1);
        return new ItemStack(item, count);
    }

    private static void randomizeRotation(LocalPlayer player) {
        float randomYaw = (random.nextFloat() * 360.0f) - 180.0f;
        float randomPitch = (random.nextFloat() * 180.0f) - 90.0f;
        player.setYRot(randomYaw);
        player.setXRot(randomPitch);
        player.setYHeadRot(randomYaw);
        player.setYBodyRot(randomYaw);
    }

    private static void saveHotbarDatasetFrame(Minecraft mc, int selectedSlot) {
        int sourceWidth = mc.getWindow().getWidth();
        int sourceHeight = mc.getWindow().getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }

        ensureDatasetFrameBuffer(sourceWidth, sourceHeight);
        FrameCapture.grabMainFramebufferRGB(datasetFrameBuffer);
        flipRgbVertical(datasetFrameBuffer, sourceWidth, sourceHeight);
        BufferedImage sourceImage = rgbToBufferedImage(datasetFrameBuffer, sourceWidth, sourceHeight);
        BufferedImage scaledImage = resizeTo240p(sourceImage);

        Path datasetDir = mc.gameDirectory.toPath()
                .resolve("hotbar_dataset")
                .resolve(Integer.toString(selectedSlot));
        Path outputPath = datasetDir.resolve("frame" + datasetFrameCounter++ + ".jpeg");
        while (Files.exists(outputPath)) {
            outputPath = datasetDir.resolve("frame" + datasetFrameCounter++ + ".jpeg");
        }
        try {
            Files.createDirectories(datasetDir);
            ImageIO.write(scaledImage, "jpeg", outputPath.toFile());
        } catch (IOException e) {
            if (!datasetWriteErrorLogged) {
                datasetWriteErrorLogged = true;
                System.err.println("[InputFakerRandomItems] Failed to write dataset frame: " + e.getMessage());
            }
        }
    }

    public static void flipRgbVertical(byte[] rgb, int width, int height) {
        int stride = width * 3;
        byte[] row = new byte[stride];

        for (int y = 0; y < height / 2; y++) {
            int top = y * stride;
            int bottom = (height - 1 - y) * stride;

            System.arraycopy(rgb, top, row, 0, stride);
            System.arraycopy(rgb, bottom, rgb, top, stride);
            System.arraycopy(row, 0, rgb, bottom, stride);
        }
    }

    private static void ensureDatasetFrameBuffer(int width, int height) {
        if (datasetFrameBuffer != null && datasetFrameWidth == width && datasetFrameHeight == height) {
            return;
        }
        datasetFrameWidth = width;
        datasetFrameHeight = height;
        datasetFrameBuffer = new byte[width * height * 3];
    }

    private static BufferedImage rgbToBufferedImage(byte[] rgb, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = rgb[index++] & 0xFF;
                int g = rgb[index++] & 0xFF;
                int b = rgb[index++] & 0xFF;
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    private static BufferedImage resizeTo240p(BufferedImage sourceImage) {
        int sourceWidth = sourceImage.getWidth();
        int sourceHeight = sourceImage.getHeight();
        int targetHeight = DATASET_TARGET_HEIGHT;
        int targetWidth = Math.max(1, (int) Math.round((double) sourceWidth * targetHeight / sourceHeight));

        BufferedImage scaledImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaledImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(sourceImage, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return scaledImage;
    }

    private static void setRandomVitals(LocalPlayer player) {
        float maxHealth = player.getMaxHealth();
        float randomizedHealth = maxHealth <= 1.0f
                ? maxHealth
                : 1.0f + (random.nextFloat() * (maxHealth - 1.0f));
        player.setHealth(randomizedHealth);

        FoodData foodData = player.getFoodData();
        int randomizedFood = random.nextInt(21);
        foodData.setFoodLevel(randomizedFood);

        int level = random.nextInt(MAX_RANDOM_LEVEL + 1);
        float progress = random.nextFloat();
        player.experienceLevel = level;
        player.experienceProgress = progress;
        player.totalExperience = totalExperienceForLevel(level)
                + (int) (progress * experienceToNextLevel(level));
    }

    private static int totalExperienceForLevel(int level) {
        if (level <= 16) {
            return level * level + 6 * level;
        }
        if (level <= 31) {
            return (int) (2.5 * level * level - 40.5 * level + 360);
        }
        return (int) (4.5 * level * level - 162.5 * level + 2220);
    }

    private static int experienceToNextLevel(int level) {
        if (level >= 30) {
            return 112 + ((level - 30) * 9);
        }
        if (level >= 15) {
            return 37 + ((level - 15) * 5);
        }
        return 7 + (level * 2);
    }
}
