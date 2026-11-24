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

import java.util.Objects;

public record PrinterControlRequest(BlockPos printer, Action action, int x, int y, int z,
                                    Direction face, Rotation rotation) implements CustomPacketPayload {
    public enum Action {
        MOVE, ROTATE, FLIP_X, FLIP_Z, SET_FACE, SET_ANCHOR, RESET_POSITION, RESET_PROGRESS, CYCLE_REDSTONE, TOGGLE_MODE
    }

    public enum RedstoneMode { HIGH, LOW, ALWAYS }

    public enum OperationMode { BUILD, DECONSTRUCT }

    public static boolean isRedstoneSatisfied(RedstoneMode mode, boolean hasSignal) {
        return switch (mode == null ? RedstoneMode.HIGH : mode) {
            case HIGH -> hasSignal;
            case LOW -> !hasSignal;
            case ALWAYS -> true;
        };
    }

    public static final int TARGET_BUILD_TICKS = 3_600;
    public static final int MAX_BLOCKS_PER_TICK = 512;

    /** Scale per-tick placement rate linearly so vanilla max (66x66x66) finishes within <= 3 minutes. */
    public static int blocksPerTick(int totalPlacements) {
        if (totalPlacements <= 0) return 1;
        return Math.clamp((totalPlacements + TARGET_BUILD_TICKS - 1) / TARGET_BUILD_TICKS, 1, MAX_BLOCKS_PER_TICK);
    }

    public static int analogOutputSignal(boolean hasJob, int done, int total, boolean complete) {
        if (!hasJob || total <= 0) return 0;
        if (complete || done >= total) return 15;
        if (total == 1) return 1;
        return (int) Math.clamp(1L + (done * 13L) / (total - 1), 1L, 14L);
    }

    public PrinterControlRequest {
        Objects.requireNonNull(printer, "printer");
        action = action == null ? Action.MOVE : action;
        face = face == null ? Direction.UP : face;
        rotation = rotation == null ? Rotation.NONE : rotation;
    }

    public static final Type<PrinterControlRequest> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(JustEnoughOritech.MOD_ID, "printer_control"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PrinterControlRequest> CODEC = StreamCodec.of(
            (buf, req) -> {
                buf.writeBlockPos(req.printer());
                buf.writeEnum(req.action());
                buf.writeVarInt(req.x());
                buf.writeVarInt(req.y());
                buf.writeVarInt(req.z());
                Direction.STREAM_CODEC.encode(buf, req.face());
                Rotation.STREAM_CODEC.encode(buf, req.rotation());
            },
            buf -> new PrinterControlRequest(
                    buf.readBlockPos(),
                    buf.readEnum(Action.class),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    Direction.STREAM_CODEC.decode(buf),
                    Rotation.STREAM_CODEC.decode(buf)
            )
    );

    public static PrinterControlRequest move(BlockPos printer, int dx, int dy, int dz) {
        return new PrinterControlRequest(printer, Action.MOVE, dx, dy, dz, Direction.UP, Rotation.NONE);
    }

    public static PrinterControlRequest rotate(BlockPos printer, int steps) {
        return new PrinterControlRequest(printer, Action.ROTATE, steps, 0, 0, Direction.UP, Rotation.NONE);
    }

    public static PrinterControlRequest simple(BlockPos printer, Action action) {
        return new PrinterControlRequest(printer, action, 0, 0, 0, Direction.UP, Rotation.NONE);
    }

    public static PrinterControlRequest setFace(BlockPos printer, Direction face) {
        return new PrinterControlRequest(printer, Action.SET_FACE, 0, 0, 0, face, Rotation.NONE);
    }

    public static PrinterControlRequest setAnchor(BlockPos printer, BlockPos origin, Rotation rotation) {
        return new PrinterControlRequest(printer, Action.SET_ANCHOR, origin.getX(), origin.getY(), origin.getZ(), Direction.UP, rotation);
    }

    @Override
    public Type<PrinterControlRequest> type() {
        return TYPE;
    }

    public static void receive(PrinterControlRequest request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator()) return;
        var level = player.level();
        var pos = request.printer();
        int maxRange = request.action() == Action.SET_ANCHOR ? PrinterEntity.ANCHOR_RANGE : PrinterEntity.RANGE;
        if (!pos.closerToCenterThan(player.position(), maxRange)
                || !level.hasChunkAt(pos)
                || !(level.getBlockEntity(pos) instanceof PrinterEntity printer)) {
            player.sendOverlayMessage(Component.translatable("gui.just_enough_oritech.printer_missing"));
            return;
        }
        if (!level.mayInteract(player, pos) || !printer.authorized(player)) {
            player.sendOverlayMessage(Component.translatable("gui.just_enough_oritech.printer_access"));
            return;
        }
        if (printer.handleControl(player, request) && request.action() == Action.SET_ANCHOR) {
            player.sendOverlayMessage(Component.translatable("gui.just_enough_oritech.printer_anchored_msg"));
        }
    }
}
