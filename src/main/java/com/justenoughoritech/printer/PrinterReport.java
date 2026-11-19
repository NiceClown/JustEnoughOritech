package com.justenoughoritech.printer;

import com.justenoughoritech.JustEnoughOritech;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record PrinterReport(BlockPos printer, BlockPos origin, BlockPos obstruction, boolean unloaded,
                            List<Missing> missing) implements CustomPacketPayload {
    public record Missing(Identifier item, int count) {}
    public static final Type<PrinterReport> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(JustEnoughOritech.MOD_ID,"printer_report"));
    public static final StreamCodec<RegistryFriendlyByteBuf,PrinterReport> CODEC =
            StreamCodec.of(PrinterReport::encode,PrinterReport::decode);

    private static void encode(RegistryFriendlyByteBuf buf, PrinterReport report) {
        buf.writeBlockPos(report.printer);
        buf.writeBlockPos(report.origin);
        buf.writeBoolean(report.obstruction != null);
        if (report.obstruction != null) buf.writeBlockPos(report.obstruction);
        buf.writeBoolean(report.unloaded);
        buf.writeVarInt(report.missing.size());
        for (var entry : report.missing) {
            buf.writeIdentifier(entry.item);
            buf.writeVarInt(entry.count);
        }
    }

    private static PrinterReport decode(RegistryFriendlyByteBuf buf) {
        var printer = buf.readBlockPos();
        var origin = buf.readBlockPos();
        var obstruction = buf.readBoolean() ? buf.readBlockPos() : null;
        boolean unloaded = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > 64) throw new IllegalArgumentException("Invalid material count");
        var missing = new ArrayList<Missing>(count);
        for (int i=0;i<count;i++) missing.add(new Missing(buf.readIdentifier(),buf.readVarInt()));
        return new PrinterReport(printer,origin,obstruction,unloaded,List.copyOf(missing));
    }

    public static void receive(PrinterReport report, IPayloadContext context) {
        if (context.player().level().getBlockEntity(report.printer()) instanceof PrinterEntity printer)
            printer.setReport(report);
    }

    @Override public Type<PrinterReport> type() { return TYPE; }
}
