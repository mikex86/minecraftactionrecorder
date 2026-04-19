package me.michael.kei.actionrecorder.mixin;

import me.michael.kei.actionrecorder.ActionRecorder;
import me.michael.kei.actionrecorder.TimeScaler;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "runTick", at = @At("TAIL"))
    private void runTick(CallbackInfo ci) {
        @SuppressWarnings("DataFlowIssue") Minecraft mc = (Minecraft) (Object) this;
        ActionRecorder.captureState(mc);

        Util.timeSource = TimeScaler::scaledNanoTime;
    }

    // on timer tick
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void onGameTick(CallbackInfo ci) {
        ActionRecorder.onGameTick();
    }

    @Inject(method = "startAttack()Z", at = @At("HEAD"))
    private void onStartAttack(CallbackInfoReturnable<Boolean> ci) {
        ActionRecorder.attackPerformed = true;
    }
}
