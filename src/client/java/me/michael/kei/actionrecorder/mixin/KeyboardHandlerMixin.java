package me.michael.kei.actionrecorder.mixin;

import me.michael.kei.actionrecorder.ActionRecorder;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    @Inject(method = "charTyped", at = @At("HEAD"))
    private void charTyped(long window, CharacterEvent event, CallbackInfo ci) {
        String character = new StringBuilder().appendCodePoint(event.codepoint()).toString();
        ActionRecorder.pressedScreenKeys.add(character);
    }

}
