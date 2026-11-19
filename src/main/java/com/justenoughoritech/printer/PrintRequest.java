package com.justenoughoritech.printer;

import com.justenoughoritech.JustEnoughOritech;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record PrintRequest(BlockPos printer,Direction face,Rotation rotation,PrinterLayout layout) implements CustomPacketPayload {
    public static final Type<PrintRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath(JustEnoughOritech.MOD_ID,"print_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf,PrintRequest> CODEC = StreamCodec.of(
            (buf,request) -> {
                buf.writeBlockPos(request.printer());
                Direction.STREAM_CODEC.encode(buf,request.face());
                Rotation.STREAM_CODEC.encode(buf,request.rotation());
                request.layout().encode(buf);
            },buf -> new PrintRequest(buf.readBlockPos(),Direction.STREAM_CODEC.decode(buf),
                    Rotation.STREAM_CODEC.decode(buf),PrinterLayout.decode(buf)));
    @Override public Type<PrintRequest> type() { return TYPE; }

    public static void receive(PrintRequest request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator()) return;
        var level = player.level();
        var pos=request.printer();
        String status;
        if (!pos.closerToCenterThan(player.position(),PrinterEntity.RANGE)
                || !level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof PrinterEntity printer)) {
            status="printer_attach";
        } else if (!printer.authorized(player)) {
            status="printer_access";
        } else if (!printer.available(player)) {
            status="printer_busy";
        } else {
            status=printer.accept(player,request.layout(),request.face(),request.rotation()) ? "printer_sent" : "printer_invalid";
        }
        player.sendOverlayMessage(Component.translatable("gui.just_enough_oritech."+status));
    }
}
