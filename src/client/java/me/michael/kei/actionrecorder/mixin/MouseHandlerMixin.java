package me.michael.kei.actionrecorder.mixin;

import me.michael.kei.actionrecorder.ActionRecorder;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Inject(
        method = "onPress(JIII)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/Screen;afterMouseAction()V"
        )
    )
    private void beforeMouseClicked(long window, int button, int action, int mods, CallbackInfo ci) {
        ActionRecorder.guiLeftMouseClicked = (button == 0);
        ActionRecorder.guiRightMouseClicked = (button == 1);
    }

}
