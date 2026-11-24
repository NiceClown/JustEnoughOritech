package com.justenoughoritech.mixin;

import com.justenoughoritech.client.ProjectionController;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), cancellable = true)
    private void justEnoughOritech$projectionKeys(long window, int action, KeyEvent key, CallbackInfo callback) {
        if (minecraft != null && minecraft.getWindow() != null
                && window == minecraft.getWindow().handle()
                && ProjectionController.handleProjectionKey(key, action)) {
            callback.cancel();
        }
    }
}
