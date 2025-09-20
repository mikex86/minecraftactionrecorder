package me.michael.kei.actionrecorder.mixin;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlCommandEncoder.class)
public interface GlCommandEncoderMixin {

    @Accessor("drawFBO") int getDrawFBO();


}
