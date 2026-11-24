package com.justenoughoritech.printer;

import com.justenoughoritech.JustEnoughOritech;
import com.justenoughoritech.network.ServerConfigReader;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Objects;
import java.util.Optional;

public record WriteBlueprintRequest(PrinterLayout layout, Optional<BlueprintData.Anchor> anchor) implements CustomPacketPayload {
    public WriteBlueprintRequest {
        Objects.requireNonNull(layout, "layout");
        anchor = anchor == null ? Optional.empty() : anchor;
    }

    public static final Type<WriteBlueprintRequest> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(JustEnoughOritech.MOD_ID, "write_blueprint"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WriteBlueprintRequest> CODEC = StreamCodec.of(
            (buf, req) -> {
                req.layout().encode(buf);
                buf.writeBoolean(req.anchor().isPresent());
                req.anchor().ifPresent(a -> BlueprintData.Anchor.STREAM_CODEC.encode(buf, a));
            },
            buf -> {
                var layout = PrinterLayout.decode(buf);
                Optional<BlueprintData.Anchor> anchor = buf.readBoolean()
                        ? Optional.of(BlueprintData.Anchor.STREAM_CODEC.decode(buf))
                        : Optional.empty();
                return new WriteBlueprintRequest(layout, anchor);
            }
    );

    @Override
    public Type<WriteBlueprintRequest> type() {
        return TYPE;
    }

    public static void receive(WriteBlueprintRequest request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator()) return;
        var server = player.level().getServer();
        if (server == null) return;
        var settings = ServerConfigReader.read(server);
        if (!settings.compatible() || request.layout().resolve(settings.maxSize()) == null) {
            player.sendOverlayMessage(Component.translatable("gui.just_enough_oritech.printer_invalid"));
            return;
        }
        if (!player.isCreative() && !consumePaper(player.getInventory())) {
            player.sendOverlayMessage(Component.translatable("gui.just_enough_oritech.blueprint_need_paper"));
            return;
        }
        var stack = new ItemStack(PrinterContent.BLUEPRINT.get());
        stack.set(PrinterContent.BLUEPRINT_DATA.get(), new BlueprintData(request.layout(), request.anchor()));
        player.getInventory().placeItemBackInInventory(stack);
        player.sendOverlayMessage(Component.translatable("gui.just_enough_oritech.blueprint_written"));
    }

    private static boolean consumePaper(Inventory inventory) {
        for (int i = 0; i < 36; i++) {
            var item = inventory.getItem(i);
            if (item.is(Items.PAPER) && !item.isEmpty()) {
                inventory.removeItem(i, 1);
                return true;
            }
        }
        var offhand = inventory.getItem(Inventory.SLOT_OFFHAND);
        if (offhand.is(Items.PAPER) && !offhand.isEmpty()) {
            inventory.removeItem(Inventory.SLOT_OFFHAND, 1);
            return true;
        }
        return false;
    }
}
