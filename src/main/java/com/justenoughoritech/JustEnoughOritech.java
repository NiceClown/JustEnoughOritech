package com.justenoughoritech;

import com.mojang.logging.LogUtils;
import com.justenoughoritech.network.ReactorConfigPayload;
import com.justenoughoritech.network.ServerConfigReader;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

@Mod(JustEnoughOritech.MOD_ID)
public final class JustEnoughOritech {
    public static final String MOD_ID = "just_enough_oritech";
    public static final Logger LOGGER = LogUtils.getLogger();

    public JustEnoughOritech(IEventBus modBus, ModContainer container) {
        com.justenoughoritech.printer.PrinterContent.register(modBus);
        modBus.addListener(RegisterPayloadHandlersEvent.class, this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogin);
        if (FMLEnvironment.getDist() == Dist.CLIENT) registerClient(modBus);
    }

    private static void registerClient(IEventBus modBus) {
        try {
            Class<?> bootstrap=Class.forName("com.justenoughoritech.client.ClientBootstrap");
            bootstrap.getMethod("register",IEventBus.class).invoke(null,modBus);
        } catch (ReflectiveOperationException exception) {
            LOGGER.error("Could not register client key bindings",exception);
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar("0.4.0");
        registrar.playToClient(ReactorConfigPayload.TYPE, ReactorConfigPayload.STREAM_CODEC,
                ReactorConfigPayload::handleClient);
        registrar.playToClient(com.justenoughoritech.printer.PrinterReport.TYPE,
                com.justenoughoritech.printer.PrinterReport.CODEC,com.justenoughoritech.printer.PrinterReport::receive);
        registrar.playToServer(com.justenoughoritech.printer.PrintRequest.TYPE,
                com.justenoughoritech.printer.PrintRequest.CODEC, com.justenoughoritech.printer.PrintRequest::receive);
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var result = ServerConfigReader.read(player.level().getServer());
        PacketDistributor.sendToPlayer(player, result);
    }
}
