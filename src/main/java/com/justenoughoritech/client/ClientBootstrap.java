package com.justenoughoritech.client;

import net.neoforged.bus.api.IEventBus;

public final class ClientBootstrap {
    private ClientBootstrap() {}
    public static void register(IEventBus modBus) {
        modBus.addListener(ProjectionController::registerKeys);
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) ->
                event.register(com.justenoughoritech.printer.PrinterContent.MENU.get(),PrinterScreen::new));
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent event) ->
                event.register(AssemblyPreview.State.class, AssemblyPreview::new));
    }
}
