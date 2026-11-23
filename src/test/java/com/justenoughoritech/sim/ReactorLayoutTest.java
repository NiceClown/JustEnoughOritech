package com.justenoughoritech.sim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorLayoutTest {
    private static ReactorParts.Part part(String id, ReactorParts.Kind kind, int rods, int pulses) {
        return new ReactorParts.Part(id, id, null, kind, rods, pulses);
    }

    private static final ReactorParts.Part SINGLE = part("oritech:reactor_rod", ReactorParts.Kind.ROD, 1, 1);
    private static final ReactorParts.Part DOUBLE = part("oritech:reactor_double_rod", ReactorParts.Kind.ROD, 2, 4);
    private static final ReactorParts.Part ABSORBER = part("oritech:reactor_heat_absorber", ReactorParts.Kind.ABSORBER, 0, 0);
    private static final ReactorParts.Part VENT = part("oritech:reactor_heat_vent", ReactorParts.Kind.VENT, 0, 0);
    private static final ReactorParts.Part PIPE = part("oritech:reactor_heat_pipe", ReactorParts.Kind.PIPE, 0, 0);
    private static final ReactorParts.Part REFLECTOR = part("oritech:reactor_neutron_reflector", ReactorParts.Kind.REFLECTOR, 0, 0);

    @Test
    void defaultLayoutIsThreeCubedEmptyWithOnePort() {
        var layout = new ReactorLayout();
        assertEquals(3, layout.width());
        assertEquals(3, layout.length());
        assertEquals(3, layout.height());
        assertEquals(1, layout.energyPorts());
        for (int z = 0; z < 3; z++) {
            for (int x = 0; x < 3; x++) {
                assertEquals(ReactorParts.Part.EMPTY, layout.get(x, z));
            }
        }
    }

    @Test
    void setNullNormalizesToEmptyAndClearPreservesDimensionsAndPorts() {
        var layout = new ReactorLayout();
        layout.resize(4, 5, 64);
        layout.setHeight(6, 64);
        layout.setEnergyPorts(10);
        layout.set(1, 2, SINGLE);
        assertEquals(SINGLE, layout.get(1, 2));

        layout.set(1, 2, null);
        assertEquals(ReactorParts.Part.EMPTY, layout.get(1, 2));

        layout.set(0, 0, DOUBLE);
        layout.set(3, 4, VENT);
        layout.clear();
        assertEquals(4, layout.width());
        assertEquals(5, layout.length());
        assertEquals(6, layout.height());
        assertEquals(10, layout.energyPorts());
        for (int z = 0; z < 5; z++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(ReactorParts.Part.EMPTY, layout.get(x, z));
            }
        }
    }

    @Test
    void copyAndReplaceWithAreIsolatedDeepCopies() {
        var original = new ReactorLayout();
        original.resize(4, 3, 64);
        original.setHeight(5, 64);
        original.setEnergyPorts(7);
        original.set(0, 0, SINGLE);
        original.set(3, 2, ABSORBER);

        var copy = original.copy();
        assertEquals(4, copy.width());
        assertEquals(3, copy.length());
        assertEquals(5, copy.height());
        assertEquals(7, copy.energyPorts());
        assertEquals(SINGLE, copy.get(0, 0));
        assertEquals(ABSORBER, copy.get(3, 2));

        copy.set(0, 0, VENT);
        copy.setEnergyPorts(2);
        assertEquals(SINGLE, original.get(0, 0));
        assertEquals(7, original.energyPorts());

        var target = new ReactorLayout();
        target.replaceWith(original);
        original.set(3, 2, PIPE);
        assertEquals(ABSORBER, target.get(3, 2));
        assertEquals(7, target.energyPorts());
    }

    @Test
    void resizeClampsBoundsPreservesOverlapAndClampsEnergyPorts() {
        var layout = new ReactorLayout();
        layout.resize(4, 4, 64);
        layout.setHeight(4, 64);
        layout.setEnergyPorts(60);
        layout.set(0, 0, SINGLE);
        layout.set(1, 2, VENT);
        layout.set(3, 3, ABSORBER);

        // Grow: old cells stay in place, new cells are EMPTY.
        layout.resize(6, 5, 64);
        assertEquals(6, layout.width());
        assertEquals(5, layout.length());
        assertEquals(SINGLE, layout.get(0, 0));
        assertEquals(VENT, layout.get(1, 2));
        assertEquals(ABSORBER, layout.get(3, 3));
        assertEquals(ReactorParts.Part.EMPTY, layout.get(5, 4));
        assertEquals(60, layout.energyPorts());

        // Shrink: surviving overlap preserved, energyPorts clamped to 2*(2+2)*4 - 1 = 31.
        layout.resize(2, 2, 64);
        assertEquals(2, layout.width());
        assertEquals(2, layout.length());
        assertEquals(SINGLE, layout.get(0, 0));
        assertEquals(ReactorParts.Part.EMPTY, layout.get(1, 1));
        assertEquals(31, layout.energyPorts());

        // Extreme clamping.
        layout.resize(-5, 999, 14);
        assertEquals(1, layout.width());
        assertEquals(14, layout.length());
        layout.setHeight(0, 14);
        assertEquals(1, layout.height());
        assertEquals(29, layout.energyPorts()); // 2*(1+14)*1 - 1 = 29, clamped from 31
        layout.setHeight(100, 14);
        assertEquals(14, layout.height());
    }

    @Test
    void maxEnergyPortsFormulaAndBoundaries() {
        assertEquals(3, ReactorLayout.maxEnergyPorts(1, 1, 1));
        assertEquals(35, ReactorLayout.maxEnergyPorts(3, 3, 3));
        assertEquals(55, ReactorLayout.maxEnergyPorts(2, 5, 4));
        assertEquals(16_383, ReactorLayout.maxEnergyPorts(64, 64, 64));
        assertEquals(0, ReactorLayout.maxEnergyPorts(0, 3, 3));
        assertEquals(0, ReactorLayout.maxEnergyPorts(3, 0, 3));
        assertEquals(0, ReactorLayout.maxEnergyPorts(3, 3, 0));
        assertEquals(0, ReactorLayout.maxEnergyPorts(-1, 5, 5));

        var layout = new ReactorLayout();
        layout.setEnergyPorts(0);
        assertEquals(1, layout.energyPorts());
        layout.setEnergyPorts(-10);
        assertEquals(1, layout.energyPorts());
        layout.setEnergyPorts(1000);
        assertEquals(35, layout.energyPorts());
    }

    @Test
    void validationErrorsCoversAllChecksAndMaxInteriorLimit() {
        var layout = new ReactorLayout();
        assertEquals(List.of("gui.just_enough_oritech.need_rod"), layout.validationErrors(64));

        layout.set(1, 1, SINGLE);
        assertTrue(layout.validationErrors(64).isEmpty());

        // Exactly at limit 64 is valid, 65 exceeds limit 64.
        var atLimit = new ReactorLayout();
        atLimit.resize(64, 1, 64);
        atLimit.setHeight(1, 64);
        atLimit.set(0, 0, SINGLE);
        assertTrue(atLimit.validationErrors(64).isEmpty());
        assertTrue(atLimit.validationErrors(63).contains("gui.just_enough_oritech.dimensions"));

        // Height or length exceeding max also triggers dimensions error.
        var tall = new ReactorLayout();
        tall.resize(1, 1, 64);
        tall.setHeight(15, 64);
        tall.set(0, 0, SINGLE);
        assertTrue(tall.validationErrors(14).contains("gui.just_enough_oritech.dimensions"));
        assertTrue(tall.validationErrors(15).isEmpty());

        var wide = new ReactorLayout();
        wide.resize(1, 15, 64);
        wide.setHeight(1, 64);
        wide.set(0, 0, SINGLE);
        assertTrue(wide.validationErrors(14).contains("gui.just_enough_oritech.dimensions"));
    }

    @Test
    void blockCountsExactlyMatchesPlacementsAcrossShapesAndPortCounts() {
        int[][] shapes = {{1, 1, 1, 1}, {1, 1, 1, 3}, {2, 4, 3, 5}, {5, 3, 2, 12}, {4, 4, 4, 63}};
        for (var s : shapes) {
            var layout = new ReactorLayout();
            layout.resize(s[0], s[1], 64);
            layout.setHeight(s[2], 64);
            layout.setEnergyPorts(s[3]);
            layout.set(0, 0, SINGLE);
            if (s[0] > 1) layout.set(1, 0, VENT);
            if (s[1] > 1) layout.set(0, 1, ABSORBER);
            if (s[0] > 1 && s[1] > 1) layout.set(1, 1, PIPE);
            if (s[0] > 2 && s[1] > 2) layout.set(2, 2, DOUBLE);
            if (s[0] > 3 && s[1] > 2) layout.set(3, 2, REFLECTOR);

            var placements = layout.placements();
            var actualCounts = new HashMap<String, Integer>();
            for (var p : placements) {
                actualCounts.merge(p.blockId(), 1, Integer::sum);
            }
            assertEquals(layout.blockCounts(), actualCounts,
                    "blockCounts mismatch for shape " + s[0] + "x" + s[1] + "x" + s[2] + " ports=" + s[3]);

            // Verify controller is unique and at (0, 1, 1).
            var controllers = placements.stream()
                    .filter(p -> p.blockId().equals(ReactorLayout.CONTROLLER)).toList();
            assertEquals(1, controllers.size());
            assertEquals(new ReactorLayout.Placement(0, 1, 1, ReactorLayout.CONTROLLER), controllers.getFirst());

            // Verify ceiling fuel/coolant ports sit directly above rods and absorbers.
            var byPos = new HashMap<String, String>();
            for (var p : placements) byPos.put(p.x() + "," + p.y() + "," + p.z(), p.blockId());
            int emptyCells = 0;
            for (int z = 0; z < s[1]; z++) {
                for (int x = 0; x < s[0]; x++) {
                    var kind = layout.get(x, z).kind();
                    String top = byPos.get((x + 1) + "," + (s[2] + 1) + "," + (z + 1));
                    if (kind == ReactorParts.Kind.ROD) assertEquals(ReactorLayout.FUEL_PORT, top);
                    else if (kind == ReactorParts.Kind.ABSORBER) assertEquals(ReactorLayout.COOLANT_PORT, top);
                    else assertEquals(ReactorLayout.WALL, top);
                    if (kind == ReactorParts.Kind.EMPTY) {
                        emptyCells++;
                        assertFalse(byPos.containsKey((x + 1) + ",1," + (z + 1)),
                                "empty interior cells must not emit placements");
                    }
                }
            }
            int expectedTotal = (s[0] + 2) * (s[1] + 2) * (s[2] + 2) - emptyCells * s[2];
            assertEquals(expectedTotal, placements.size());
        }
    }

    @ParameterizedTest
    @EnumSource(Rotation.class)
    void rotatedPlacementsPreserveOrderBoundsBijectionAndOutwardWallFacing(Rotation rotation) {
        var layout = new ReactorLayout();
        layout.resize(2, 5, 64);
        layout.setHeight(3, 64);
        layout.setEnergyPorts(6);
        layout.set(0, 0, SINGLE);
        layout.set(1, 0, VENT);
        layout.set(0, 4, ABSORBER);
        layout.set(1, 2, DOUBLE);

        var base = layout.placements();
        var rotated = layout.placements(rotation);
        int bw = layout.buildWidth(rotation);
        int bl = layout.buildLength(rotation);
        int h = layout.height();

        if (rotation == Rotation.NONE || rotation == Rotation.CLOCKWISE_180) {
            assertEquals(2, bw);
            assertEquals(5, bl);
        } else {
            assertEquals(5, bw);
            assertEquals(2, bl);
        }

        assertEquals(base.size(), rotated.size());
        var seenPositions = new HashSet<BlockPos>();
        for (int i = 0; i < base.size(); i++) {
            var b = base.get(i);
            var r = rotated.get(i);
            // Order and block IDs preserved across rotations.
            assertEquals(b.blockId(), r.blockId());
            assertEquals(b.y(), r.y());
            assertTrue(r.x() >= 0 && r.x() <= bw + 1, "x out of bounds: " + r);
            assertTrue(r.y() >= 0 && r.y() <= h + 1, "y out of bounds: " + r);
            assertTrue(r.z() >= 0 && r.z() <= bl + 1, "z out of bounds: " + r);
            assertTrue(seenPositions.add(new BlockPos(r.x(), r.y(), r.z())), "duplicate pos: " + r);

            boolean shell = ReactorLayout.onShell(r, bw, h, bl);
            boolean expectedShell = ReactorLayout.onShell(b, layout.width(), h, layout.length());
            assertEquals(expectedShell, shell);

            // Controller and energy ports must lie strictly on a side wall and face strictly outward.
            if (r.blockId().equals(ReactorLayout.CONTROLLER) || r.blockId().equals(ReactorLayout.ENERGY_PORT)) {
                assertTrue(shell);
                Direction facing = ReactorLayout.wallFacing(r, bw);
                BlockPos outside = new BlockPos(r.x(), r.y(), r.z()).relative(facing);
                boolean isOutside = outside.getX() < 0 || outside.getX() > bw + 1
                        || outside.getZ() < 0 || outside.getZ() > bl + 1;
                assertTrue(isOutside, "wall block " + r + " facing " + facing + " must point outside shell");
            }
        }
    }

    @Test
    void buildOriginKeepsAimedCornerFixedAcrossAllFacesAndRotations() {
        var layout = new ReactorLayout();
        layout.resize(2, 5, 64);
        layout.setHeight(3, 64);
        layout.set(0, 0, SINGLE);
        var corner = new BlockPos(10, 64, -20);

        for (Direction face : Direction.values()) {
            for (Rotation rotation : Rotation.values()) {
                BlockPos origin = layout.buildOrigin(corner, face, rotation);
                var rotated = layout.placements(rotation);
                int minY = rotated.stream().mapToInt(p -> origin.getY() + p.y()).min().orElseThrow();
                int maxY = rotated.stream().mapToInt(p -> origin.getY() + p.y()).max().orElseThrow();
                if (face == Direction.DOWN) {
                    assertEquals(corner.getY(), maxY, "DOWN face aligns reactor ceiling with aimed corner");
                    assertEquals(corner.getY() - (layout.height() + 1), minY);
                } else {
                    assertEquals(corner.getY(), minY, face + " aligns reactor floor with aimed corner");
                    assertEquals(corner.getY() + layout.height() + 1, maxY);
                }
                // Original (0, 0, 0) corner block must land at corner X/Z in world coordinates.
                var cornerBlock = rotated.getFirst();
                assertEquals(corner.getX(), origin.getX() + cornerBlock.x(), "X mismatch for " + rotation);
                assertEquals(corner.getZ(), origin.getZ() + cornerBlock.z(), "Z mismatch for " + rotation);
            }
        }
    }

    @Test
    void tileEdgeCasesPreservesHeightAndPortsAndGuardsOverflow() {
        var layout = new ReactorLayout();
        layout.resize(3, 2, 64);
        layout.setHeight(5, 64);
        layout.setEnergyPorts(12);
        layout.set(0, 0, SINGLE);

        assertFalse(layout.tile(-1, 1, 64));
        assertFalse(layout.tile(1, -2, 64));
        assertFalse(layout.tile(Integer.MAX_VALUE, 1, 64));
        assertFalse(layout.tile(1, Integer.MAX_VALUE, 64));
        assertFalse(layout.tile(1, 1, 2)); // width=3 exceeds max=2
        assertFalse(layout.tile(1, 1, 0));

        assertTrue(layout.tile(1, 1, 64));
        assertEquals(3, layout.width());
        assertEquals(2, layout.length());
        assertEquals(5, layout.height());
        assertEquals(12, layout.energyPorts());

        assertTrue(layout.tile(2, 3, 64));
        assertEquals(6, layout.width());
        assertEquals(6, layout.length());
        assertEquals(5, layout.height());
        assertEquals(12, layout.energyPorts());
        assertEquals(SINGLE, layout.get(3, 4));
        assertNotEquals(SINGLE, layout.get(1, 0));
    }
}
