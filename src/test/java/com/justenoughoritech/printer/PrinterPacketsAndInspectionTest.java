package com.justenoughoritech.printer;

import com.justenoughoritech.sim.ReactorLayout;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrinterPacketsAndInspectionTest {
    private static RegistryFriendlyByteBuf buf() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), null, null);
    }

    @Test
    void printerReportCodecRoundTripsWithAndWithoutObstruction() {
        var withObstruction = new PrinterReport(
                new BlockPos(10, 64, -30),
                new BlockPos(11, 64, -29),
                new BlockPos(13, 65, -28),
                true,
                List.of(
                        new PrinterReport.Missing(Identifier.fromNamespaceAndPath("oritech", "reactor_wall"), 42),
                        new PrinterReport.Missing(Identifier.fromNamespaceAndPath("oritech", "reactor_rod"), 6)
                ));
        var b1 = buf();
        PrinterReport.CODEC.encode(b1, withObstruction);
        var decoded1 = PrinterReport.CODEC.decode(b1);
        assertEquals(withObstruction, decoded1);
        assertEquals(0, b1.readableBytes());
        assertEquals(PrinterReport.TYPE, withObstruction.type());
        assertEquals("just_enough_oritech:printer_report", PrinterReport.TYPE.id().toString());

        var clear = new PrinterReport(
                new BlockPos(-5, -60, 100),
                new BlockPos(-4, -60, 100),
                null,
                false,
                List.of());
        var b2 = buf();
        PrinterReport.CODEC.encode(b2, clear);
        var decoded2 = PrinterReport.CODEC.decode(b2);
        assertEquals(clear, decoded2);
        assertNull(decoded2.obstruction());
        assertFalse(decoded2.unloaded());
        assertTrue(decoded2.missing().isEmpty());
        assertEquals(0, b2.readableBytes());
    }

    @Test
    void printerReportCodecEnforcesMissingCountBoundary64() {
        var atLimit = new ArrayList<PrinterReport.Missing>();
        for (int i = 0; i < 64; i++) {
            atLimit.add(new PrinterReport.Missing(Identifier.fromNamespaceAndPath("test", "item_" + i), i + 1));
        }
        var report64 = new PrinterReport(BlockPos.ZERO, BlockPos.ZERO, null, false, atLimit);
        var b64 = buf();
        PrinterReport.CODEC.encode(b64, report64);
        assertEquals(report64, PrinterReport.CODEC.decode(b64));

        for (int badCount : new int[]{-1, 65}) {
            var bad = buf();
            bad.writeBlockPos(BlockPos.ZERO);
            bad.writeBlockPos(BlockPos.ZERO);
            bad.writeBoolean(false);
            bad.writeBoolean(false);
            bad.writeVarInt(badCount);
            assertThrows(IllegalArgumentException.class, () -> PrinterReport.CODEC.decode(bad));
        }
    }

    @Test
    void printRequestCodecRoundTripsAcrossAllDirectionsAndRotations() {
        var layout = new PrinterLayout(2, 3, 2, 2,
                List.of("oritech:reactor_rod", "", "oritech:reactor_heat_vent", "", "", ""));
        var pos = new BlockPos(-123, 72, 456);
        for (Direction dir : Direction.values()) {
            for (Rotation rot : Rotation.values()) {
                var request = new PrintRequest(pos, dir, rot, layout);
                var b = buf();
                PrintRequest.CODEC.encode(b, request);
                var decoded = PrintRequest.CODEC.decode(b);
                assertEquals(request, decoded);
                assertEquals(0, b.readableBytes());
                assertEquals(PrintRequest.TYPE, request.type());
                assertEquals("just_enough_oritech:print_request", PrintRequest.TYPE.id().toString());
            }
        }
    }

    @Test
    void buildInspectionHandlesEmptyFromClampingBudgetOverflowAndOutOfRangePlaced() {
        var origin = new BlockPos(5, 70, -12);
        var empty = new BuildInspection(List.of(), origin, 0);
        assertEquals(origin, empty.origin());
        assertTrue(empty.scan((pos, id) -> BuildInspection.CellStatus.AIR, 2048, false));
        assertNull(empty.obstruction());
        assertFalse(empty.unloaded());
        assertTrue(empty.requiredIds().isEmpty());

        var placements = List.of(
                new ReactorLayout.Placement(0, 0, 0, ReactorLayout.WALL),
                new ReactorLayout.Placement(1, 0, 0, ReactorLayout.WALL)
        );
        var clampedLow = new BuildInspection(placements, origin, -10);
        assertFalse(clampedLow.scan((pos, id) -> BuildInspection.CellStatus.AIR, 0, false));
        assertFalse(clampedLow.scan((pos, id) -> BuildInspection.CellStatus.AIR, -5, false));

        var clampedHigh = new BuildInspection(placements, origin, 999);
        assertTrue(clampedHigh.scan((pos, id) -> BuildInspection.CellStatus.AIR, Integer.MAX_VALUE, false));
        assertNull(clampedHigh.obstruction());
        assertTrue(clampedHigh.requiredIds().isEmpty());

        clampedLow.placed(-1);
        clampedLow.placed(0);
        clampedLow.placed(99);
        assertTrue(clampedLow.requiredIds().isEmpty());
    }

    @Test
    void buildInspectionScansAirMatchingObstructionUnloadedAndPlacedDecrement() {
        var origin = new BlockPos(10, 64, 20);
        var placements = List.of(
                new ReactorLayout.Placement(0, 0, 0, ReactorLayout.WALL),        // slot 0: already placed -> skip
                new ReactorLayout.Placement(1, 0, 0, ReactorLayout.WALL),        // slot 1: air -> count WALL +1
                new ReactorLayout.Placement(2, 0, 0, ReactorLayout.ENERGY_PORT), // slot 2: obstructed -> first obstruction + count ENERGY_PORT +1
                new ReactorLayout.Placement(3, 0, 0, ReactorLayout.WALL),        // slot 3: unloaded -> unloaded=true + count WALL +1
                new ReactorLayout.Placement(4, 0, 0, ReactorLayout.CONTROLLER)   // slot 4: obstructed -> keeps first obstruction (slot 2) + count CONTROLLER +1
        );
        var loaded = Map.of(
                origin.offset(0, 0, 0), true,
                origin.offset(1, 0, 0), true,
                origin.offset(2, 0, 0), true,
                origin.offset(3, 0, 0), false,
                origin.offset(4, 0, 0), true
        );
        var existing = Map.of(
                origin.offset(0, 0, 0), ReactorLayout.WALL,
                origin.offset(1, 0, 0), "minecraft:air",
                origin.offset(2, 0, 0), "minecraft:dirt",
                origin.offset(4, 0, 0), "minecraft:oak_log"
        );
        java.util.function.BiFunction<BlockPos, String, BuildInspection.CellStatus> probe = (pos, blockId) -> {
            if (!loaded.getOrDefault(pos, false)) return BuildInspection.CellStatus.UNLOADED;
            String present = existing.getOrDefault(pos, "minecraft:air");
            if (blockId.equals(present)) return BuildInspection.CellStatus.MATCH;
            return "minecraft:air".equals(present) ? BuildInspection.CellStatus.AIR : BuildInspection.CellStatus.OBSTRUCTED;
        };

        // Paginated scan: first budget=2 covers slot 0 (already placed) and slot 1 (air).
        var full = new BuildInspection(placements, origin, 0);
        assertFalse(full.scan(probe, 2, false));
        assertEquals(Map.of(ReactorLayout.WALL, 1), full.requiredIds());
        assertNull(full.obstruction());
        assertFalse(full.unloaded());

        // Second step finishes slots 2..4 (also tests Integer.MAX_VALUE budget without int overflow!).
        assertTrue(full.scan(probe, Integer.MAX_VALUE, false));
        assertEquals(origin.offset(2, 0, 0), full.obstruction(), "must retain FIRST obstruction only");
        assertTrue(full.unloaded(), "must record unloaded chunk");
        assertEquals(Map.of(ReactorLayout.WALL, 2, ReactorLayout.ENERGY_PORT, 1, ReactorLayout.CONTROLLER, 1),
                full.requiredIds());

        // placed() decrements counted slots, removes entries at 0, ignores uncounted slots, and is idempotent.
        full.placed(0); // already placed -> not counted
        assertEquals(2, full.requiredIds().get(ReactorLayout.WALL));
        full.placed(1);
        assertEquals(1, full.requiredIds().get(ReactorLayout.WALL));
        full.placed(1); // repeated call is a no-op
        assertEquals(1, full.requiredIds().get(ReactorLayout.WALL));
        full.placed(3);
        assertFalse(full.requiredIds().containsKey(ReactorLayout.WALL), "reaching 0 must remove key");
        full.placed(2);
        assertFalse(full.requiredIds().containsKey(ReactorLayout.ENERGY_PORT));
        assertEquals(Map.of(ReactorLayout.CONTROLLER, 1), full.requiredIds());

        // stopAtObstruction = true returns immediately at slot 2 without populating requiredIds or scanning slot 3.
        var fast = new BuildInspection(placements, origin, 0);
        assertTrue(fast.scan(probe, 10, true));
        assertEquals(origin.offset(2, 0, 0), fast.obstruction());
        assertFalse(fast.unloaded(), "must stop at slot 2 before reaching unloaded slot 3");
        assertTrue(fast.requiredIds().isEmpty());

        // Starting from cursor=3 skips earlier slots and detects unloaded + slot 4 obstruction.
        var mid = new BuildInspection(placements, origin, 3);
        assertTrue(mid.scan(probe, 10, false));
        assertTrue(mid.unloaded());
        assertEquals(origin.offset(4, 0, 0), mid.obstruction());
        assertEquals(Map.of(ReactorLayout.WALL, 1, ReactorLayout.CONTROLLER, 1), mid.requiredIds());
    }
}
