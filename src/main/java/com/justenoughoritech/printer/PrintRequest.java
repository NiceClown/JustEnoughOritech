package com.justenoughoritech.printer;

import com.justenoughoritech.JustEnoughOritech;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record PrintRequest(BlockPos anchor, PrinterLayout layout) implements CustomPacketPayload {
    public static final Type<PrintRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath(JustEnoughOritech.MOD_ID,"print_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf,PrintRequest> CODEC = StreamCodec.of(
            (buf,request) -> {
                buf.writeBlockPos(request.anchor());
                request.layout().encode(buf);
            },buf -> new PrintRequest(buf.readBlockPos(),PrinterLayout.decode(buf)));
    @Override public Type<PrintRequest> type() { return TYPE; }

    public static void receive(PrintRequest request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator()) return;
        var level = player.level();
        var pos = player.blockPosition();
        PrinterEntity nearest = null;
        double best = PrinterEntity.RANGE*PrinterEntity.RANGE;
        for (int x=(pos.getX()-PrinterEntity.RANGE)>>4;x<=(pos.getX()+PrinterEntity.RANGE)>>4;x++) {
            for (int z=(pos.getZ()-PrinterEntity.RANGE)>>4;z<=(pos.getZ()+PrinterEntity.RANGE)>>4;z++) {
                var chunk = level.getChunkSource().getChunkNow(x,z);
                if (chunk==null) continue;
                for (var entity : chunk.getBlockEntities().values()) {
                    if (!(entity instanceof PrinterEntity printer) || !printer.available(player)) continue;
                    double distance = printer.getBlockPos().distToCenterSqr(player.position());
                    if (distance>=best) continue;
                    nearest=printer;
                    best=distance;
                }
            }
        }
        String status = nearest==null ? "printer_missing"
                : nearest.accept(player,request.layout(),request.anchor()) ? "printer_sent" : "printer_invalid";
        player.sendOverlayMessage(Component.translatable("gui.just_enough_oritech."+status));
    }
}
