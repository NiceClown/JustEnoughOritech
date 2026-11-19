package com.justenoughoritech.mixin;

import com.justenoughoritech.client.ProjectionController;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    @Inject(method="keyPress", at=@At("HEAD"), cancellable=true)
    private void justEnoughOritech$projectionKeys(long window,int action,KeyEvent key,CallbackInfo callback) {
        if (window==net.minecraft.client.Minecraft.getInstance().getWindow().handle()
                && ProjectionController.handleProjectionKey(key,action)) callback.cancel();
    }
}
