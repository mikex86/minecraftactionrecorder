package me.michael.kei.actionrecorder;

import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL20.GL_RGB;

public class FrameCapture {

    private static ByteBuffer rgbScratch = BufferUtils.createByteBuffer(1);

    public static void grabMainFramebufferRGB(byte[] rgbOut) {
        Minecraft mc = Minecraft.getInstance();
        int drawFBO = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();

        int needed = w * h * 3;
        if (rgbScratch.capacity() < needed) {
            rgbScratch = BufferUtils.createByteBuffer(needed);
        }
        if (rgbOut.length != needed) {
            throw new IllegalArgumentException("rgbOut too small, need " + needed + " bytes");
        }
        rgbScratch.clear();

        // Save bindings/state we touch
        int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] prevPack = new int[]{GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT)};
        int prevReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);

        try {
            // Read exactly from the currently active draw FBO.
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFBO);
            if (drawFBO == 0) {
                GL11.glReadBuffer(GL11.GL_BACK);
            } else {
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            }

            // Read tightly packed
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);

            // Read RGBA8 pixels from (0,0) to (w,h) into our buffer
            GL11.glReadPixels(
                    0, 0, w, h,
                    GL_RGB, GL11.GL_UNSIGNED_BYTE,
                    rgbScratch
            );

            // Convert to RGB and flip vertically (OpenGL origin is lower-left)
            rgbScratch.rewind();
            rgbScratch.get(rgbOut);
        } finally {
            // Restore GL state
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevPack[0]);
            GL11.glReadBuffer(prevReadBuffer);
        }
    }
}
