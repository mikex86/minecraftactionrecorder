package me.michael.kei.actionrecorder.mixin;

import me.michael.kei.actionrecorder.ActionRecorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "runTick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        @SuppressWarnings("DataFlowIssue") Minecraft mc = (Minecraft) (Object) this;
        ActionRecorder.captureState(mc);
    }
}
