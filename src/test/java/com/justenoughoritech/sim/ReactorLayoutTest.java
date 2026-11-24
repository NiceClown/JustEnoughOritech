package com.justenoughoritech.sim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
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

        // Exactly at limit 64 on every axis and exact maxEnergyPorts produces zero errors.
        var atLimit = new ReactorLayout();
        atLimit.resize(14, 14, 14);
        atLimit.setHeight(14, 14);
        atLimit.setEnergyPorts(ReactorLayout.maxEnergyPorts(14, 14, 14));
        atLimit.set(0, 0, SINGLE);
        assertTrue(atLimit.validationErrors(14).isEmpty());
        assertTrue(atLimit.validationErrors(13).contains("gui.just_enough_oritech.dimensions"));

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

            // Vertical outer corners are ALWAYS WALL, never ENERGY_PORT or CONTROLLER, even at max ports.
            for (int y = 0; y <= s[2] + 1; y++) {
                assertEquals(ReactorLayout.WALL, byPos.get("0," + y + ",0"));
                assertEquals(ReactorLayout.WALL, byPos.get((s[0] + 1) + "," + y + ",0"));
                assertEquals(ReactorLayout.WALL, byPos.get("0," + y + "," + (s[1] + 1)));
                assertEquals(ReactorLayout.WALL, byPos.get((s[0] + 1) + "," + y + "," + (s[1] + 1)));
            }
        }
    }

    @Test
    void onShellChecksEverySingleFaceAndRejectsInteriorCells() {
        // Interior cells (not touching any of the 6 outer faces) must return false.
        assertFalse(ReactorLayout.onShell(new ReactorLayout.Placement(1, 1, 1, ReactorLayout.WALL), 3, 3, 3));
        assertFalse(ReactorLayout.onShell(new ReactorLayout.Placement(2, 2, 2, ReactorLayout.WALL), 3, 3, 3));
        assertFalse(ReactorLayout.onShell(new ReactorLayout.Placement(3, 3, 3, ReactorLayout.WALL), 3, 3, 3));
        // Each of the 6 outer faces individually, with the other two coordinates strictly interior (2).
        assertTrue(ReactorLayout.onShell(new ReactorLayout.Placement(0, 2, 2, ReactorLayout.WALL), 3, 3, 3));
        assertTrue(ReactorLayout.onShell(new ReactorLayout.Placement(4, 2, 2, ReactorLayout.WALL), 3, 3, 3));
        assertTrue(ReactorLayout.onShell(new ReactorLayout.Placement(2, 0, 2, ReactorLayout.WALL), 3, 3, 3));
        assertTrue(ReactorLayout.onShell(new ReactorLayout.Placement(2, 4, 2, ReactorLayout.WALL), 3, 3, 3));
        assertTrue(ReactorLayout.onShell(new ReactorLayout.Placement(2, 2, 0, ReactorLayout.WALL), 3, 3, 3));
        assertTrue(ReactorLayout.onShell(new ReactorLayout.Placement(2, 2, 4, ReactorLayout.WALL), 3, 3, 3));
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

        // Single-axis exact boundary (xCopies == 1 or zCopies == 1) and exact max / dim boundary.
        var singleAxis = new ReactorLayout();
        singleAxis.resize(3, 2, 64);
        assertTrue(singleAxis.tile(1, 3, 6), "zCopies == max/length == 3 and xCopies == 1 must succeed");
        assertEquals(3, singleAxis.width());
        assertEquals(6, singleAxis.length());
        assertTrue(singleAxis.tile(2, 1, 6), "xCopies == max/width == 2 and zCopies == 1 must succeed");
        assertEquals(6, singleAxis.width());
        assertEquals(6, singleAxis.length());

        assertTrue(layout.tile(2, 3, 64));
        assertEquals(6, layout.width());
        assertEquals(6, layout.length());
        assertEquals(5, layout.height());
        assertEquals(12, layout.energyPorts());
        assertEquals(SINGLE, layout.get(3, 4));
        assertNotEquals(SINGLE, layout.get(1, 0));
    }

    @Test
    void dihedralOrientationAndMirroredPlacementsPreserveValidShellAndControllerFacing() {
        var layout = new ReactorLayout();
        layout.resize(2, 5, 64);
        layout.setHeight(3, 64);
        layout.setEnergyPorts(4);
        layout.set(0, 0, SINGLE);
        layout.set(1, 4, ABSORBER);

        var distinctControllerPositions = new java.util.HashSet<String>();
        for (Rotation rot : Rotation.values()) {
            for (Mirror mirror : new Mirror[]{Mirror.NONE, Mirror.FRONT_BACK}) {
                var o = new ReactorLayout.Orientation(rot, mirror);
                assertEquals(o, o.flippedWorldX().flippedWorldX(), "flipWorldX must be an involution for " + o);
                assertEquals(o, o.flippedWorldZ().flippedWorldZ(), "flipWorldZ must be an involution for " + o);
                assertEquals(o, o.rotated(1).rotated(1).rotated(1).rotated(1));
                assertEquals(o, o.rotated(1).rotated(-1));

                int bw = layout.buildWidth(rot);
                int bl = layout.buildLength(rot);
                var placed = layout.placements(rot, mirror);
                assertEquals(layout.placements().size(), placed.size());

                // Verify flipWorldX mirrors exact X coords into w+1-x without altering Y or Z.
                var fx = o.flippedWorldX();
                var fxPlaced = layout.placements(fx.rotation(), fx.mirror());
                var expectedFx = placed.stream()
                        .map(p -> p.y() + ":" + (bw + 1 - p.x()) + ":" + p.z() + ":" + p.blockId())
                        .collect(java.util.stream.Collectors.toSet());
                var actualFx = fxPlaced.stream()
                        .map(p -> p.y() + ":" + p.x() + ":" + p.z() + ":" + p.blockId())
                        .collect(java.util.stream.Collectors.toSet());
                assertEquals(expectedFx, actualFx, "flipWorldX must mirror exact world X placements for " + o);

                // Verify flipWorldZ mirrors exact Z coords into l+1-z without altering X or Y.
                var fz = o.flippedWorldZ();
                var fzPlaced = layout.placements(fz.rotation(), fz.mirror());
                var expectedFz = placed.stream()
                        .map(p -> p.y() + ":" + p.x() + ":" + (bl + 1 - p.z()) + ":" + p.blockId())
                        .collect(java.util.stream.Collectors.toSet());
                var actualFz = fzPlaced.stream()
                        .map(p -> p.y() + ":" + p.x() + ":" + p.z() + ":" + p.blockId())
                        .collect(java.util.stream.Collectors.toSet());
                assertEquals(expectedFz, actualFz, "flipWorldZ must mirror exact world Z placements for " + o);

                // Controller and energy ports lie strictly on side walls and face outward.
                var facing = layout.controllerFacing(rot, mirror);
                int controllers = 0;
                for (var p : placed) {
                    if (ReactorLayout.CONTROLLER.equals(p.blockId())) {
                        controllers++;
                        assertEquals(facing, ReactorLayout.wallFacing(p, bw));
                        distinctControllerPositions.add(p.x() + "," + p.z());
                    }
                    if (ReactorLayout.CONTROLLER.equals(p.blockId()) || ReactorLayout.ENERGY_PORT.equals(p.blockId())) {
                        assertTrue(ReactorLayout.onShell(p, bw, layout.height(), bl));
                        BlockPos outside = new BlockPos(p.x(), p.y(), p.z()).relative(ReactorLayout.wallFacing(p, bw));
                        assertTrue(outside.getX() < 0 || outside.getX() > bw + 1
                                || outside.getZ() < 0 || outside.getZ() > bl + 1);
                    }
                }
                assertEquals(1, controllers);
            }
        }
        assertEquals(8, distinctControllerPositions.size(), "All 8 D4 symmetries should move the asymmetric controller");
    }

    @Test
    void faceOriginCentersFlushAgainstAllSixPrinterFacesWithoutOverlap() {
        var layout = new ReactorLayout();
        layout.resize(3, 5, 64);
        layout.setHeight(4, 64);
        var printer = new BlockPos(100, 70, -50);

        for (Direction face : Direction.values()) {
            for (Rotation rot : Rotation.values()) {
                int outerW = layout.buildWidth(rot) + 2;
                int outerH = layout.height() + 2;
                int outerL = layout.buildLength(rot) + 2;
                var origin = layout.faceOrigin(printer, face, rot);
                int minX = origin.getX(), maxX = minX + outerW - 1;
                int minY = origin.getY(), maxY = minY + outerH - 1;
                int minZ = origin.getZ(), maxZ = minZ + outerL - 1;

                boolean inside = printer.getX() >= minX && printer.getX() <= maxX
                        && printer.getY() >= minY && printer.getY() <= maxY
                        && printer.getZ() >= minZ && printer.getZ() <= maxZ;
                assertFalse(inside, "Reactor must never overlap printer block for face=" + face + " rot=" + rot);

                switch (face) {
                    case UP -> assertEquals(printer.getY() + 1, minY);
                    case DOWN -> assertEquals(printer.getY() - 1, maxY);
                    case NORTH -> assertEquals(printer.getZ() - 1, maxZ);
                    case SOUTH -> assertEquals(printer.getZ() + 1, minZ);
                    case WEST -> assertEquals(printer.getX() - 1, maxX);
                    case EAST -> assertEquals(printer.getX() + 1, minX);
                }
            }
        }
    }

    @Test
    void specializedPreviewPlacementsMatchFilteredFullPlacementsAcrossOrientations() {
        var layout = new ReactorLayout();
        layout.resize(4, 3, 64);
        layout.setHeight(3, 64);
        layout.setEnergyPorts(4);
        layout.set(0, 0, SINGLE);
        layout.set(1, 1, ABSORBER);
        layout.set(3, 2, VENT);

        for (Rotation rot : Rotation.values()) {
            for (Mirror mir : Mirror.values()) {
                var full = layout.placements(rot, mir);
                int bw = layout.buildWidth(rot);
                int bl = layout.buildLength(rot);

                var expectedShell = full.stream()
                        .filter(p -> ReactorLayout.onShell(p, bw, layout.height(), bl))
                        .toList();
                assertEquals(expectedShell, layout.shellPlacements(rot, mir));

                for (int layer = 0; layer <= layout.height() + 1; layer++) {
                    int targetY = layer;
                    var expectedLayer = full.stream().filter(p -> p.y() == targetY).toList();
                    assertEquals(expectedLayer, layout.layerPlacements(layer, rot, mir));
                }

                var expectedHeatmap = full.stream()
                        .filter(p -> !ReactorLayout.WALL.equals(p.blockId()) || p.y() == 0)
                        .toList();
                assertEquals(expectedHeatmap, layout.heatmapCutawayPlacements(rot, mir));
            }
        }
    }
}
