package com.justenoughoritech.printer;

import com.justenoughoritech.JustEnoughOritech;
import com.justenoughoritech.network.ServerConfigReader;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Objects;
import java.util.Optional;

/** Opens the server-backed Blueprint Drafting Table container from the JEI simulator. */
public record OpenBlueprintBenchRequest(PrinterLayout layout, Optional<BlueprintData.Anchor> anchor,
                                        String name) implements CustomPacketPayload {
    public OpenBlueprintBenchRequest {
        Objects.requireNonNull(layout, "layout");
        anchor = anchor == null ? Optional.empty() : anchor;
        name = BlueprintData.sanitizeName(name);
    }

    public static final Type<OpenBlueprintBenchRequest> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(JustEnoughOritech.MOD_ID, "open_blueprint_bench"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenBlueprintBenchRequest> CODEC = StreamCodec.of(
            (buf, req) -> {
                req.layout().encode(buf);
                buf.writeBoolean(req.anchor().isPresent());
                req.anchor().ifPresent(a -> BlueprintData.Anchor.STREAM_CODEC.encode(buf, a));
                buf.writeUtf(req.name(), BlueprintData.MAX_NAME_LENGTH);
            },
            buf -> {
                var layout = PrinterLayout.decode(buf);
                Optional<BlueprintData.Anchor> anchor = buf.readBoolean()
                        ? Optional.of(BlueprintData.Anchor.STREAM_CODEC.decode(buf))
                        : Optional.empty();
                String name = buf.readUtf(BlueprintData.MAX_NAME_LENGTH);
                return new OpenBlueprintBenchRequest(layout, anchor, name);
            }
    );

    @Override
    public Type<OpenBlueprintBenchRequest> type() {
        return TYPE;
    }

    public static void receive(OpenBlueprintBenchRequest request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator()) return;
        var server = player.level().getServer();
        if (server == null) return;
        var settings = ServerConfigReader.read(server);
        if (!settings.compatible() || request.layout().resolve(settings.maxSize()) == null) {
            player.sendOverlayMessage(Component.translatable("gui.just_enough_oritech.printer_invalid"));
            return;
        }
        var data = new BlueprintData(request.layout(), request.anchor(), request.name());
        player.openMenu(
                new SimpleMenuProvider(
                        (id, inv, actor) -> new BlueprintBenchMenu(id, inv, data),
                        Component.translatable("gui.just_enough_oritech.blueprint_bench_title")),
                buf -> BlueprintData.STREAM_CODEC.encode(buf, data));
    }
}
