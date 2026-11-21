package com.justenoughoritech.sim;

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural regression tests for the four planner findings in the v0.5.2 review.
 *
 * <p>Every test drives the real phase code (or the public {@code find}) and asserts on simulation
 * outcomes, never on source text. T1 uses the public entry point because the fixed-to-growth
 * hand-off is only observable there. T2/T3/T4 drive the phase seams the planner exposed
 * ({@code Search}, {@code Budget}, {@code localSearch}, {@code Search.forRefinement}) with
 * deterministic hand-built layouts.</p>
 */
class PlannerPhasesTest {
    private static final ReactorInputs.Fuel FUEL = new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000);
    private static final Set<String> SINGLE_DOUBLE = Set.of("test:single", "test:double");

    private static ReactorParts.Part part(String id, ReactorParts.Kind kind, int rods, int pulses) {
        return new ReactorParts.Part(id, id, null, kind, rods, pulses);
    }

    private static final ReactorParts.Part SINGLE = part("test:single", ReactorParts.Kind.ROD, 1, 1);
    private static final ReactorParts.Part DOUBLE = part("test:double", ReactorParts.Kind.ROD, 2, 4);
    private static final ReactorParts.Part REFLECTOR = part("test:reflector", ReactorParts.Kind.REFLECTOR, 0, 0);
    private static final ReactorParts.Part ABSORBER = part("test:absorber", ReactorParts.Kind.ABSORBER, 0, 0);
    private static final ReactorParts.Part VENT = part("test:vent", ReactorParts.Kind.VENT, 0, 0);

    /** Parts list with the test rods plus one of each cooling block, matching production discovery. */
    private static List<ReactorParts.Part> parts(String... rodIds) {
        var parts = new ArrayList<ReactorParts.Part>();
        parts.add(ReactorParts.Part.EMPTY);
        for (var id : rodIds) parts.add(rod(id));
        parts.add(part("test:pipe", ReactorParts.Kind.PIPE, 0, 0));
        parts.add(REFLECTOR);
        parts.add(ABSORBER);
        parts.add(VENT);
        return parts;
    }

    private static ReactorParts.Part rod(String id) {
        return switch (id) {
            case "test:single" -> SINGLE;
            case "test:double" -> DOUBLE;
            default -> throw new IllegalArgumentException(id);
        };
    }

    private static ReactorDesigner.Request request(long target, boolean resize, Set<String> rods) {
        return new ReactorDesigner.Request(target, ReactorDesigner.Risk.NORMAL, rods, resize,
                ReactorDesigner.CoolingMode.AUTO);
    }

    private static ReactorLayout cube(int width, int length, int height) {
        var layout = new ReactorLayout();
        layout.resize(width, length, 66);
        layout.setHeight(height, 66);
        return layout;
    }

    private static ReactorDesigner.Budget budget() {
        return new ReactorDesigner.Budget(20_000_000L,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(60), () -> false);
    }

    private static long count(ReactorDesigner.Design design, ReactorParts.Kind kind) {
        var layout = design.layout();
        long total = 0;
        for (int z = 0; z < layout.length(); z++)
            for (int x = 0; x < layout.width(); x++)
                if (layout.get(x, z).kind() == kind) total++;
        return total;
    }

    /** A 3x3 layout whose only rod is in the centre and whose four orthogonal neighbours are coolers. */
    private static ReactorLayout centreRodWith(ReactorParts.Part rod, ReactorParts.Part frontAndBack,
                                               ReactorParts.Part sides, int height) {
        var layout = cube(3, 3, height);
        layout.set(1, 1, rod);
        layout.set(1, 0, frontAndBack);
        layout.set(1, 2, frontAndBack);
        layout.set(0, 1, sides);
        layout.set(2, 1, sides);
        return layout;
    }

    // ------------------------------------------------------------------ T1

    @Test
    @Timeout(60)
    void growthGrowsTheOriginalCubeInsteadOfJumpingToAWiderFootprint() {
        // 3x3x3 tops out at 7680 RF/t for this rod set, so the target is only reachable by growth.
        var fixedOnly = ReactorDesigner.find(cube(3, 3, 3), ReactorConfig.DEFAULT,
                parts("test:single", "test:double"), FUEL, ItemStack.EMPTY,
                request(10_000, false, SINGLE_DOUBLE));
        assertNotNull(fixedOnly.safe(), "the fixed phase should still find a stable 3x3x3 design");
        assertEquals(3, fixedOnly.safe().layout().height(), "fixed phase must not change the height");
        assertTrue(fixedOnly.safe().exportRate() < 10_000,
                "precondition: 3x3x3 must be too small for the target, got " + fixedOnly.safe().exportRate());

        var resized = ReactorDesigner.find(cube(3, 3, 3), ReactorConfig.DEFAULT,
                parts("test:single", "test:double"), FUEL, ItemStack.EMPTY,
                request(10_000, true, SINGLE_DOUBLE));
        assertNotNull(resized.safe(), "resizing from 3x3x3 should meet the target");
        var grown = resized.safe();
        // Bug 2.1 made growth reject the base 3x3 footprint against the fixed phase's seen keys,
        // so the planner jumped straight to 3x4/4x4. Height must grow on the original footprint.
        assertEquals(3, grown.layout().width(), "should keep the original width, got " + describe(grown));
        assertEquals(3, grown.layout().length(), "should keep the original length, got " + describe(grown));
        assertTrue(grown.layout().height() > 3, "height should grow above the starting 3, got " + describe(grown));
        assertTrue(grown.exportRate() >= 10_000,
                "grown base footprint should meet the target, got " + grown.exportRate());
        assertTrue(grown.exportRate() > fixedOnly.safe().exportRate(),
                "growth should improve on the fixed phase");
    }

    // ------------------------------------------------------------------ T2

    @Test
    @Timeout(30)
    void localSearchTradesRedundantCoolingForAReflectorOnAFilledGrid() {
        // Every orthogonal neighbour of the rod is a vent, so the old EMPTY-only move had no target.
        var layout = centreRodWith(SINGLE, VENT, VENT, 2);
        var search = ReactorDesigner.Search.forRefinement(ReactorConfig.DEFAULT, FUEL, ItemStack.EMPTY,
                request(0, false, Set.of("test:single")), budget(), false);
        search.tryLayout(layout.copy());
        assertNotNull(search.best, "the filled cold layout should be a stable candidate");
        var before = search.best;
        assertEquals(4, count(before, ReactorParts.Kind.VENT), "precondition: rod is fully surrounded by vents");
        assertEquals(0, count(before, ReactorParts.Kind.REFLECTOR));

        ReactorDesigner.localSearch(List.of(SINGLE), VENT, ABSORBER, REFLECTOR, search);
        var after = search.best;
        assertNotSame(before, after, "localSearch should accept a better candidate");
        assertTrue(after.exportRate() > before.exportRate(),
                "trading surplus cooling for a reflector should raise output: " + before.exportRate()
                        + " -> " + after.exportRate());
        assertEquals(2, count(after, ReactorParts.Kind.REFLECTOR),
                "one mirrored pair should become reflectors");
        assertEquals(2, count(after, ReactorParts.Kind.VENT), "the remaining pair still cools the rod");
        assertEquals(before.buildBlocks(), after.buildBlocks(), "the trade should not add blocks");
        assertEquals(ReactorSimulation.Status.STEADY, after.outcome(), "the improved design must stay stable");
        assertTrue(after.peakHeat() <= ReactorConfig.DEFAULT.maxHeat());
    }

    @Test
    @Timeout(30)
    void nearLimitHeatDoesNotHideAColdGroupUpgrade() {
        // Custom cooling physics: one vent gives a fixed point at heat = 98 of a 100 heat limit, while
        // the four corner rods settle at 56. The hottest group is therefore at >90% of the limit.
        var config = new ReactorConfig(66, 128, 16, 0, 14, 100, 600, 25_000, false, 2400);
        var layout = new ReactorLayout();
        layout.resize(5, 5, 66);
        layout.setHeight(1, 66);
        layout.set(2, 2, SINGLE);
        layout.set(2, 1, REFLECTOR);
        layout.set(2, 3, REFLECTOR);
        layout.set(1, 2, VENT);
        for (var corner : new int[][]{{0, 0}, {4, 0}, {0, 4}, {4, 4}}) layout.set(corner[0], corner[1], SINGLE);
        for (var vent : new int[][]{{1, 0}, {3, 0}, {1, 4}, {3, 4}, {0, 1}, {0, 3}, {4, 1}, {4, 3}})
            layout.set(vent[0], vent[1], VENT);

        var search = ReactorDesigner.Search.forRefinement(config, FUEL, ItemStack.EMPTY,
                request(0, false, Set.of("test:single")), budget(), false);
        search.tryLayout(layout.copy());
        assertNotNull(search.best, "the two-zone layout should be stable");
        var before = search.best;
        assertTrue(before.peakHeat() * 10 >= config.maxHeat() * 9,
                "precondition: the hottest group is at or above 90% of the heat limit, peak=" + before.peakHeat());
        assertTrue(before.peakHeat() <= config.maxHeat(), "precondition: the layout is still stable");
        long reflectorsBefore = count(before, ReactorParts.Kind.REFLECTOR);
        assertEquals(2, reflectorsBefore);

        ReactorDesigner.localSearch(List.of(SINGLE), VENT, ABSORBER, REFLECTOR, search);
        var after = search.best;
        assertTrue(after.exportRate() > before.exportRate(),
                "a hot group must not stop the cold corner groups from being upgraded: "
                        + before.exportRate() + " -> " + after.exportRate());
        assertTrue(count(after, ReactorParts.Kind.REFLECTOR) > reflectorsBefore,
                "corner vents should have been traded for reflectors");
        assertTrue(after.peakHeat() <= config.maxHeat(), "the improved layout must stay under the heat limit");
        assertEquals(ReactorSimulation.Status.STEADY, after.outcome());
    }

    // ------------------------------------------------------------------ T3

    @Test
    @Timeout(30)
    void surplusAtMinimumHeightSavesBlocksWithoutLosingTheTarget() {
        // Resizing keeps needMore true, so this exercises the independent surplus branch.
        var layout = centreRodWith(SINGLE, REFLECTOR, VENT, 3);
        var search = ReactorDesigner.Search.forRefinement(ReactorConfig.DEFAULT, FUEL, ItemStack.EMPTY,
                request(200, true, Set.of("test:single")), budget(), true);
        search.tryLayout(layout.copy());
        assertNotNull(search.best);
        var before = search.best;
        assertTrue(before.exportRate() > 200, "precondition: the target is already exceeded");
        assertEquals(2, count(before, ReactorParts.Kind.REFLECTOR), "precondition: a redundant reflector pair");
        assertEquals(2, before.layout().height(), "precondition: height is already at its minimum");
        assertEquals(2, count(before, ReactorParts.Kind.VENT));

        ReactorDesigner.localSearch(List.of(SINGLE), VENT, ABSORBER, REFLECTOR, search);
        var after = search.best;
        assertTrue(after.buildBlocks() < before.buildBlocks(),
                "surplus moves should save blocks, " + before.buildBlocks() + " -> " + after.buildBlocks());
        assertEquals(0, count(after, ReactorParts.Kind.REFLECTOR), "the redundant reflector pair should go");
        assertTrue(after.exportRate() >= 200, "the cheaper layout must still meet the target");
        assertEquals(2, after.layout().height(), "the minimum height should not change");
        assertEquals(3, after.layout().width());
        assertEquals(3, after.layout().length());
        assertEquals(ReactorSimulation.Status.STEADY, after.outcome());
    }

    @Test
    @Timeout(30)
    void exactTargetEqualityStillAllowsAnEconomyMove() {
        // One energy port caps export at exactly 1000 RF/t while the layer generates 3072 RF/t.
        // The hidden headroom can save fuel at target equality; better() must still refuse to dip below it.
        var config = new ReactorConfig(66, 128, 16, 4, 100, 2000, 600, 1000, false, 2400);
        var layout = centreRodWith(DOUBLE, REFLECTOR, VENT, 3);
        var search = ReactorDesigner.Search.forRefinement(config, FUEL, ItemStack.EMPTY,
                request(1000, false, SINGLE_DOUBLE), budget(), false);
        search.tryLayout(layout.copy());
        assertNotNull(search.best);
        var before = search.best;
        assertEquals(1000, before.exportRate(), "precondition: export is port-capped exactly at the target");

        ReactorDesigner.localSearch(List.of(SINGLE, DOUBLE), VENT, ABSORBER, REFLECTOR, search);
        var after = search.best;
        assertTrue(after.exportRate() >= 1000, "economy moves must not drop below the target");
        assertTrue(after.fuelPerTick() < before.fuelPerTick() || after.buildBlocks() < before.buildBlocks(),
                "an economy move should save fuel or blocks at target equality");
        assertEquals(3, after.layout().width());
        assertEquals(3, after.layout().length());
        assertEquals(ReactorSimulation.Status.STEADY, after.outcome());

        var belowTarget = layout.copy();
        belowTarget.set(1, 1, SINGLE);
        belowTarget.set(1, 0, ReactorParts.Part.EMPTY);
        belowTarget.set(1, 2, ReactorParts.Part.EMPTY);
        var kept = search.best;
        search.tryLayout(belowTarget);
        assertSame(kept, search.best, "a candidate that loses the target must be rejected");
        assertTrue(search.best.exportRate() >= 1000);
    }

    // ------------------------------------------------------------------ T4

    @Test
    @Timeout(30)
    void topKRefinementSeedsDoNotShareTheirSeenSet() {
        var strong = centreRodWith(DOUBLE, VENT, VENT, 2);
        var crossing = centreRodWith(SINGLE, VENT, VENT, 2);
        var request = request(0, false, SINGLE_DOUBLE);

        // Seed #1 evaluates a strong layout, then a mutation that is pruned because it is weaker.
        var first = ReactorDesigner.Search.forRefinement(ReactorConfig.DEFAULT, FUEL, ItemStack.EMPTY,
                request, budget(), false);
        first.tryLayout(strong.copy());
        assertNotNull(first.best, "seed #1 should evaluate its own layout");
        var seedBest = first.best;
        first.tryLayout(crossing.copy());
        assertSame(seedBest, first.best, "the weaker crossing mutation should be pruned, not accepted");
        assertTrue(seedBest.exportRate() > 256);

        // Seed #2 must still be able to evaluate a layout that seed #1 already visited.
        var second = ReactorDesigner.Search.forRefinement(ReactorConfig.DEFAULT, FUEL, ItemStack.EMPTY,
                request, budget(), false);
        second.tryLayout(crossing.copy());
        assertNotNull(second.best,
                "seed #2's base layout must not be hidden by seed #1's pruned mutation");
        assertEquals(256, second.best.exportRate(), "seed #2 should evaluate its own base layout");
        assertEquals(ReactorSimulation.Status.STEADY, second.best.outcome());
    }

    @Test
    @Timeout(30)
    void budgetCutoffCandidateIsNotRecordedInTheSharedSeen() {
        // This layout needs many ticks to settle (heat climbs toward ~300), so a one-tick budget
        // stops it mid-simulation instead of letting it turn steady immediately.
        var layout = cube(3, 3, 2);
        layout.set(1, 1, SINGLE);
        layout.set(1, 0, REFLECTOR);
        layout.set(1, 2, REFLECTOR);
        layout.set(0, 1, VENT);
        var request = request(0, false, SINGLE_DOUBLE);
        // LayoutKey is a package-private implementation detail of the dedupe contract, so the
        // shared set is created as an opaque raw set and handed to the package constructor.
        @SuppressWarnings({"rawtypes", "unchecked"})
        var seen = new HashSet();

        // 3x3 area (9 cells) plus one simulation tick (4 filled cells) exhausts the budget.
        var tiny = new ReactorDesigner.Budget(9 + 4, System.nanoTime() + TimeUnit.SECONDS.toNanos(30),
                () -> false);
        var cutOff = new ReactorDesigner.Search(ReactorConfig.DEFAULT, FUEL, ItemStack.EMPTY, request,
                tiny, false, false, null, seen);
        cutOff.tryLayout(layout.copy());
        assertNull(cutOff.best, "a candidate cut off mid-simulation is not a result");
        assertTrue(seen.isEmpty(), "a cut-off candidate must not poison the shared seen");

        // The same layout can then be evaluated fully and is recorded normally.
        var ample = new ReactorDesigner.Search(ReactorConfig.DEFAULT, FUEL, ItemStack.EMPTY, request,
                budget(), false, false, null, seen);
        ample.tryLayout(layout.copy());
        assertNotNull(ample.best, "the shared seen must not hide the fully evaluated candidate");
        assertEquals(1, seen.size(), "a fully evaluated candidate is recorded once");

        // The shared seen still deduplicates, which is the mechanism per-seed storage isolates.
        var duplicate = new ReactorDesigner.Search(ReactorConfig.DEFAULT, FUEL, ItemStack.EMPTY, request,
                budget(), false, false, null, seen);
        duplicate.tryLayout(layout.copy());
        assertNull(duplicate.best, "an already-recorded shared key should still be pruned");
        assertEquals(1, seen.size());
    }

    private static String describe(ReactorDesigner.Design design) {
        var layout = design.layout();
        return layout.width() + "x" + layout.length() + "x" + layout.height()
                + " export=" + design.exportRate();
    }
}
