package com.justenoughoritech.printer;

import com.google.gson.JsonParser;
import com.justenoughoritech.sim.ReactorLayout;
import com.justenoughoritech.sim.ReactorParts;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrinterLayoutTest {
    private static ReactorParts.Part part(String id, ReactorParts.Kind kind, int rods, int pulses) {
        return new ReactorParts.Part(id, id, null, kind, rods, pulses);
    }

    private static final ReactorParts.Part SINGLE = part("oritech:reactor_rod", ReactorParts.Kind.ROD, 1, 1);
    private static final ReactorParts.Part DOUBLE = part("oritech:reactor_double_rod", ReactorParts.Kind.ROD, 2, 4);
    private static final ReactorParts.Part VENT = part("oritech:reactor_heat_vent", ReactorParts.Kind.VENT, 0, 0);
    private static final ReactorParts.Part ABSORBER = part("oritech:reactor_heat_absorber", ReactorParts.Kind.ABSORBER, 0, 0);
    private static final ReactorParts.Part PIPE = part("oritech:reactor_heat_pipe", ReactorParts.Kind.PIPE, 0, 0);

    private static final Map<String, ReactorParts.Part> PARTS = Map.of(
            ReactorParts.Part.EMPTY.id(), ReactorParts.Part.EMPTY,
            SINGLE.id(), SINGLE,
            DOUBLE.id(), DOUBLE,
            VENT.id(), VENT,
            ABSORBER.id(), ABSORBER,
            PIPE.id(), PIPE
    );

    private static RegistryFriendlyByteBuf buf() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), null, null);
    }

    @Test
    void limitMatchesOritechMaxInterior64() {
        assertEquals(64, PrinterLayout.LIMIT);
    }

    @Test
    void fromAndResolveRoundTripPreservesEveryDimensionPortAndCell() {
        var original = new ReactorLayout();
        original.resize(3, 5, 64);
        original.setHeight(4, 64);
        original.setEnergyPorts(9);
        original.set(0, 0, SINGLE);
        original.set(1, 0, VENT);
        original.set(2, 4, DOUBLE);
        original.set(1, 2, ABSORBER);
        original.set(0, 3, PIPE);

        var encoded = PrinterLayout.from(original);
        assertEquals(3, encoded.width());
        assertEquals(5, encoded.length());
        assertEquals(4, encoded.height());
        assertEquals(9, encoded.ports());
        assertEquals(15, encoded.cells().size());
        assertEquals(SINGLE.id(), encoded.cells().get(0));
        assertEquals(DOUBLE.id(), encoded.cells().get(4 * 3 + 2));

        var resolved = encoded.resolve(64, PARTS);
        assertNotNull(resolved);
        assertEquals(3, resolved.width());
        assertEquals(5, resolved.length());
        assertEquals(4, resolved.height());
        assertEquals(9, resolved.energyPorts());
        for (int z = 0; z < 5; z++) {
            for (int x = 0; x < 3; x++) {
                assertEquals(original.get(x, z).id(), resolved.get(x, z).id(), "cell " + x + "," + z);
            }
        }
    }

    @Test
    void resolveRequiresRodsWhileResolveDraftAcceptsEmptyDraft() {
        var emptyCells = Collections.nCopies(4, "");
        var draft = new PrinterLayout(2, 2, 2, 1, emptyCells);
        assertNotNull(draft.resolveDraft(64, PARTS), "draft without rods should resolve for editor import");
        assertNull(draft.resolve(64, PARTS), "printer resolve must reject layouts without rods");
    }

    @Test
    void resolveDraftRejectsOutOfRangeDimensionsPortsCellCountAndUnknownParts() {
        var oneRod = List.of(SINGLE.id());
        // Invalid dimensions
        assertNull(new PrinterLayout(0, 1, 1, 1, List.of()).resolveDraft(64, PARTS));
        assertNull(new PrinterLayout(1, 0, 1, 1, List.of()).resolveDraft(64, PARTS));
        assertNull(new PrinterLayout(1, 1, 0, 1, oneRod).resolveDraft(64, PARTS));
        // Exceeds server maxSize (e.g. FTB Evo 2 max interior = 14)
        assertNull(new PrinterLayout(15, 1, 1, 1, Collections.nCopies(15, SINGLE.id())).resolveDraft(14, PARTS));
        assertNull(new PrinterLayout(1, 15, 1, 1, Collections.nCopies(15, SINGLE.id())).resolveDraft(14, PARTS));
        assertNull(new PrinterLayout(1, 1, 15, 1, oneRod).resolveDraft(14, PARTS));
        // Exceeds LIMIT=64 even if server maxSize claims 66
        assertNull(new PrinterLayout(65, 1, 1, 1, Collections.nCopies(65, SINGLE.id())).resolveDraft(66, PARTS));
        // Ports bounds: 1x1x1 allows max 3 ports
        assertNull(new PrinterLayout(1, 1, 1, 0, oneRod).resolveDraft(64, PARTS));
        assertNotNull(new PrinterLayout(1, 1, 1, 3, oneRod).resolveDraft(64, PARTS));
        assertNull(new PrinterLayout(1, 1, 1, 4, oneRod).resolveDraft(64, PARTS));
        // Cell count mismatch
        assertNull(new PrinterLayout(2, 2, 1, 1, List.of(SINGLE.id(), "", "")).resolveDraft(64, PARTS));
        assertNull(new PrinterLayout(2, 2, 1, 1, List.of(SINGLE.id(), "", "", "", "")).resolveDraft(64, PARTS));
        // Unknown or null part id
        assertNull(new PrinterLayout(1, 1, 1, 1, List.of("oritech:nonexistent_part")).resolveDraft(64, PARTS));
        var withNull = new ArrayList<String>();
        withNull.add(null);
        assertNull(new PrinterLayout(1, 1, 1, 1, withNull).resolveDraft(64, PARTS));
    }

    @Test
    void defensiveCopyProtectsCellsFromExternalMutation() {
        var mutable = new ArrayList<String>(List.of(SINGLE.id(), VENT.id()));
        var plan = new PrinterLayout(2, 1, 1, 1, mutable);
        mutable.set(0, "mutated");
        assertEquals(SINGLE.id(), plan.cells().get(0));
        assertTrue(new PrinterLayout(1, 1, 1, 1, null).cells().isEmpty());
    }

    @Test
    void jsonCodecRoundTripsAndRejectsOutOfRangeValues() {
        var plan = new PrinterLayout(2, 3, 4, 5,
                List.of(SINGLE.id(), VENT.id(), "", ABSORBER.id(), PIPE.id(), DOUBLE.id()));
        var encoded = PrinterLayout.CODEC.encodeStart(JsonOps.INSTANCE, plan).getOrThrow();
        var reparsed = JsonParser.parseString(encoded.toString());
        var decoded = PrinterLayout.CODEC.parse(JsonOps.INSTANCE, reparsed).getOrThrow();
        assertEquals(plan, decoded);

        // Out-of-range JSON values fail codec validation (width=65 > LIMIT=64, ports=0, missing field)
        assertTrue(PrinterLayout.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"width\":65,\"length\":1,\"height\":1,\"ports\":1,\"cells\":[\"\"]}")).isError());
        assertTrue(PrinterLayout.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"width\":1,\"length\":1,\"height\":1,\"ports\":0,\"cells\":[\"\"]}")).isError());
        assertTrue(PrinterLayout.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"width\":1,\"length\":1,\"height\":1}")).isError());
    }

    @Test
    void binaryEncodeDecodeRoundTripsSmallAsymmetricAndMax64Cubed() {
        // Asymmetric mixed layout
        var mixed = new PrinterLayout(3, 5, 4, 11, List.of(
                SINGLE.id(), VENT.id(), "",
                PIPE.id(), ABSORBER.id(), DOUBLE.id(),
                "", "", "",
                VENT.id(), SINGLE.id(), PIPE.id(),
                ABSORBER.id(), DOUBLE.id(), VENT.id()));
        var b1 = buf();
        mixed.encode(b1);
        assertEquals(mixed, PrinterLayout.decode(b1));
        assertEquals(0, b1.readableBytes(), "decode must consume exact written bytes");

        // Max 64x64x64 layout with max ports (16383)
        var maxCells = new ArrayList<String>(64 * 64);
        for (int i = 0; i < 64 * 64; i++) maxCells.add(i % 2 == 0 ? SINGLE.id() : VENT.id());
        var maxPlan = new PrinterLayout(64, 64, 64, ReactorLayout.maxEnergyPorts(64, 64, 64), maxCells);
        var b2 = buf();
        maxPlan.encode(b2);
        var decodedMax = PrinterLayout.decode(b2);
        assertEquals(maxPlan, decodedMax);
        assertEquals(0, b2.readableBytes());
    }

    @Test
    void binaryDecodeAndEncodeRejectMalformedPacketsAndInvalidPalettes() {
        // Invalid dimensions on wire
        for (int[] bad : new int[][]{
                {0, 1, 1, 1}, {65, 1, 1, 1}, {1, 0, 1, 1}, {1, 65, 1, 1},
                {1, 1, 0, 1}, {1, 1, 65, 1}, {1, 1, 1, 0}, {1, 1, 1, 4}
        }) {
            var b = buf();
            b.writeVarInt(bad[0]);
            b.writeVarInt(bad[1]);
            b.writeVarInt(bad[2]);
            b.writeVarInt(bad[3]);
            b.writeVarInt(1);
            b.writeUtf("", 128);
            b.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> PrinterLayout.decode(b),
                    "expected rejection for " + java.util.Arrays.toString(bad));
        }

        // Invalid palette size: 0 and 33
        for (int badPalette : new int[]{0, 33}) {
            var b = buf();
            b.writeVarInt(1); b.writeVarInt(1); b.writeVarInt(1); b.writeVarInt(1);
            b.writeVarInt(badPalette);
            assertThrows(IllegalArgumentException.class, () -> PrinterLayout.decode(b));
        }

        // Out-of-bounds cell palette index
        var badIndex = buf();
        badIndex.writeVarInt(1); badIndex.writeVarInt(1); badIndex.writeVarInt(1); badIndex.writeVarInt(1);
        badIndex.writeVarInt(1);
        badIndex.writeUtf("", 128);
        badIndex.writeByte(1); // >= palette size 1
        assertThrows(IllegalArgumentException.class, () -> PrinterLayout.decode(badIndex));

        // encode rejects mismatched cell size, null cells, and >32 distinct palette ids
        assertThrows(IllegalArgumentException.class,
                () -> new PrinterLayout(2, 2, 1, 1, List.of("")).encode(buf()));
        var nullCell = new ArrayList<String>();
        nullCell.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> new PrinterLayout(1, 1, 1, 1, nullCell).encode(buf()));
        // Palette size 32 (exact upper limit) round-trips cleanly; 33 is rejected.
        var maxPalette = new ArrayList<String>();
        for (int i = 0; i < 32; i++) maxPalette.add("mod:part_" + i);
        var plan32 = new PrinterLayout(32, 1, 1, 1, maxPalette);
        var b32 = buf();
        plan32.encode(b32);
        assertEquals(plan32, PrinterLayout.decode(b32));
        assertEquals(0, b32.readableBytes());

        var tooManyParts = new ArrayList<String>(maxPalette);
        tooManyParts.add("mod:part_32");
        assertThrows(IllegalArgumentException.class,
                () -> new PrinterLayout(33, 1, 1, 1, tooManyParts).encode(buf()));
    }
}
