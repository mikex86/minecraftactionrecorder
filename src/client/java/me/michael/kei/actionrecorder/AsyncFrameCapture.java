package me.michael.kei.actionrecorder;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;

public final class AsyncFrameCapture {

    // 2 works, 3 is usually a little safer against stalls.
    private static final int PBO_COUNT = 3;

    private static final int[] pbos = new int[PBO_COUNT];
    private static final long[] fences = new long[PBO_COUNT];
    private static final boolean[] inFlight = new boolean[PBO_COUNT];

    private static boolean initialized = false;
    private static int nextIndex = 0;
    private static int width = -1;
    private static int height = -1;
    private static int sizeBytes = -1;

    private AsyncFrameCapture() {}

    /**
     * Queues a GPU readback for the current frame and, if available, copies an older frame into rgbOut.
     *
     * @return true if rgbOut was filled with a completed frame, false if no frame was ready yet
     */
    public static boolean grabMainFramebufferRGBAsync(byte[] rgbOut) {
        Minecraft mc = Minecraft.getInstance();
        int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        int needed = w * h * 3;

        if (rgbOut.length != needed) {
            throw new IllegalArgumentException("rgbOut must be exactly " + needed + " bytes");
        }

        ensureCapacity(w, h, needed);

        int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int prevPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int prevPackPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);

        int slot = nextIndex;
        boolean copied = false;

        try {
            // Read exactly from the currently active draw FBO.
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFbo);
            GL11.glReadBuffer(drawFbo == 0 ? GL11.GL_BACK : GL30.GL_COLOR_ATTACHMENT0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);

            // 1) Try to harvest the oldest pending readback in this slot.
            if (inFlight[slot]) {
                long sync = fences[slot];
                int wait = GL32.glClientWaitSync(sync, 0, 0);

                if (wait == GL32.GL_ALREADY_SIGNALED || wait == GL32.GL_CONDITION_SATISFIED) {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbos[slot]);

                    ByteBuffer mapped = GL30.glMapBufferRange(
                            GL21.GL_PIXEL_PACK_BUFFER,
                            0,
                            sizeBytes,
                            GL30.GL_MAP_READ_BIT,
                            null
                    );

                    if (mapped == null) {
                        throw new IllegalStateException("glMapBufferRange returned null");
                    }

                    mapped.get(rgbOut, 0, sizeBytes);

                    if (!GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER)) {
                        throw new IllegalStateException("PBO contents became corrupted during unmap");
                    }

                    GL32.glDeleteSync(sync);
                    fences[slot] = 0L;
                    inFlight[slot] = false;
                    copied = true;
                } else if (wait == GL32.GL_WAIT_FAILED) {
                    GL32.glDeleteSync(sync);
                    fences[slot] = 0L;
                    inFlight[slot] = false;
                    throw new IllegalStateException("glClientWaitSync failed");
                }
            }

            // 2) Reuse this slot only once its previous transfer is done.
            if (!inFlight[slot]) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbos[slot]);

                // Null pointer here means "write into bound PBO at offset 0".
                GL11.glReadPixels(
                        0, 0, w, h,
                        GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE,
                        0L
                );

                fences[slot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                inFlight[slot] = true;
                nextIndex = (nextIndex + 1) % PBO_COUNT;
            }

            return copied;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPackPbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GL11.glReadBuffer(prevReadBuffer);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevPackAlignment);
        }
    }

    /**
     * Free PBOs/syncs. Call this on shutdown, or let ensureCapacity recreate on resize.
     */
    public static void shutdown() {
        destroy();
    }

    private static void ensureCapacity(int w, int h, int needed) {
        if (initialized && width == w && height == h && sizeBytes == needed) {
            return;
        }

        destroy();

        int prevPackPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            for (int i = 0; i < PBO_COUNT; i++) {
                pbos[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbos[i]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, needed, GL15.GL_STREAM_READ);
                fences[i] = 0L;
                inFlight[i] = false;
            }
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPackPbo);
        }

        width = w;
        height = h;
        sizeBytes = needed;
        nextIndex = 0;
        initialized = true;
    }

    private static void destroy() {
        for (int i = 0; i < PBO_COUNT; i++) {
            if (fences[i] != 0L) {
                GL32.glDeleteSync(fences[i]);
                fences[i] = 0L;
            }
            if (pbos[i] != 0) {
                GL15.glDeleteBuffers(pbos[i]);
                pbos[i] = 0;
            }
            inFlight[i] = false;
        }

        initialized = false;
        nextIndex = 0;
        width = -1;
        height = -1;
        sizeBytes = -1;
    }
}