package me.michael.kei.actionrecorder.mixin;

import me.michael.kei.actionrecorder.ActionRecorder;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Inject(
        method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
        at = @At("HEAD")
    )
    private void beforeMouseClicked(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (action != 1) {
            return;
        }
        int button = buttonInfo.button();
        ActionRecorder.guiLeftMouseClicked = button == 0;
        ActionRecorder.guiRightMouseClicked = button == 1;
    }

}
