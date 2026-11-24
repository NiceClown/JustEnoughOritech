package com.justenoughoritech.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;

public final class ClientBootstrap {
    private ClientBootstrap() {}
    public static void register(IEventBus modBus) {
        modBus.addListener(ProjectionController::registerKeys);
        NeoForge.EVENT_BUS.addListener(SimulatorWidget::clearViewport);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,SimulatorWidget::handlePress);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,SimulatorWidget::handleRelease);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,SimulatorWidget::handleDrag);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,SimulatorWidget::handleScroll);
        NeoForge.EVENT_BUS.addListener(ClientBootstrap::onLoggingOut);
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) -> {
                event.register(com.justenoughoritech.printer.PrinterContent.MENU.get(), PrinterScreen::new);
                event.register(com.justenoughoritech.printer.PrinterContent.BENCH_MENU.get(), BlueprintBenchScreen::new);
        });
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent event) ->
                event.register(AssemblyPreview.State.class, AssemblyPreview::new));
    }

    /** The next connection must not inherit the previous server's config, results or simulation. */
    private static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        ClientRuntimeConfig.reset();
        ProjectionController.onDisconnect();
    }
}
