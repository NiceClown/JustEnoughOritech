package com.justenoughoritech.sim;

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct unit tests for ReactorDesigner's internal comparators, shortlist, geometry, and refinement seams. */
class ReactorDesignerInternalsTest {
    private static final ReactorInputs.Fuel FUEL = new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000);
    private static final ReactorParts.Part EMPTY = ReactorParts.Part.EMPTY;
    private static final ReactorParts.Part SINGLE = part("test:single", ReactorParts.Kind.ROD, 1, 1);
    private static final ReactorParts.Part DOUBLE = part("test:double", ReactorParts.Kind.ROD, 2, 4);
    private static final ReactorParts.Part QUAD = part("test:quad", ReactorParts.Kind.ROD, 4, 12);
    private static final ReactorParts.Part PIPE = part("test:pipe", ReactorParts.Kind.PIPE, 0, 0);
    private static final ReactorParts.Part VENT = part("test:vent", ReactorParts.Kind.VENT, 0, 0);
    private static final ReactorParts.Part ABSORBER = part("test:absorber", ReactorParts.Kind.ABSORBER, 0, 0);
    private static final ReactorParts.Part REFLECTOR = part("test:reflector", ReactorParts.Kind.REFLECTOR, 0, 0);

    private static ReactorParts.Part part(String id, ReactorParts.Kind kind, int rods, int pulses) {
        return new ReactorParts.Part(id, id, null, kind, rods, pulses);
    }

    private static ReactorLayout box(int w, int l, int h) {
        var layout = new ReactorLayout();
        layout.resize(w, l, 64);
        layout.setHeight(h, 64);
        return layout;
    }

    private static ReactorDesigner.Design design(ReactorLayout layout, long export, int peakHeat,
                                                 ReactorSimulation.Status outcome, int ticks,
                                                 long buildBlocks, long fuelPerTick, double coolant) {
        return new ReactorDesigner.Design(layout, export, peakHeat, 1, outcome, ticks, buildBlocks, fuelPerTick, coolant,
                new int[layout.width() * layout.length()]);
    }

    private static ReactorDesigner.Budget budget() {
        return new ReactorDesigner.Budget(5_000_000L,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(30), () -> false);
    }

    @Test
    void betterComparatorEnforcesEveryTieBreakerInStrictOrder() {
        var cube3 = box(3, 3, 3);
        var cube4 = box(4, 4, 4);
        var base = design(cube3, 1000, 500, ReactorSimulation.Status.STEADY, 10, 100, 10, 1.0);

        // Null previous always loses.
        assertTrue(ReactorDesigner.better(base, null, 0));

        // Target == 0: higher export always wins, lower export loses regardless of cheaper metrics.
        assertTrue(ReactorDesigner.better(
                design(cube4, 1001, 1900, ReactorSimulation.Status.STEADY, 5, 999, 99, 9.0), base, 0));
        assertFalse(ReactorDesigner.better(
                design(cube3, 999, 10, ReactorSimulation.Status.STEADY, 5, 10, 1, 0.0), base, 0));

        // Positive target: meeting target beats missing target; higher below-target beats lower below-target.
        var below1 = design(cube3, 500, 100, ReactorSimulation.Status.STEADY, 10, 50, 5, 0.0);
        var below2 = design(cube3, 800, 100, ReactorSimulation.Status.STEADY, 10, 90, 9, 0.0);
        assertTrue(ReactorDesigner.better(below2, below1, 1000));
        assertFalse(ReactorDesigner.better(below1, below2, 1000));
        assertTrue(ReactorDesigner.better(base, below2, 1000));
        assertFalse(ReactorDesigner.better(below2, base, 1000));

        // Dangerous candidates tie-break on surviving ticks before shape/fuel/etc.
        var dShort = design(cube3, 1500, 2500, ReactorSimulation.Status.MELTDOWN, 620, 100, 10, 0.0);
        var dLong = design(cube4, 1500, 2500, ReactorSimulation.Status.MELTDOWN, 700, 200, 20, 0.0);
        assertTrue(ReactorDesigner.better(dLong, dShort, 1000));
        assertFalse(ReactorDesigner.better(dShort, dLong, 1000));

        // Both meet target (1000): shapeCost -> fuelPerTick -> buildBlocks -> coolantPerSecond -> peakHeat -> higher export.
        var worseShape = design(cube4, 2000, 10, ReactorSimulation.Status.STEADY, 10, 10, 1, 0.0);
        assertTrue(ReactorDesigner.better(base, worseShape, 1000));
        assertFalse(ReactorDesigner.better(worseShape, base, 1000));

        var lessFuel = design(cube3, 1000, 900, ReactorSimulation.Status.STEADY, 10, 200, 9, 5.0);
        assertTrue(ReactorDesigner.better(lessFuel, base, 1000));
        assertFalse(ReactorDesigner.better(base, lessFuel, 1000));

        var fewerBlocks = design(cube3, 1000, 900, ReactorSimulation.Status.STEADY, 10, 99, 10, 5.0);
        assertTrue(ReactorDesigner.better(fewerBlocks, base, 1000));
        assertFalse(ReactorDesigner.better(base, fewerBlocks, 1000));

        var lessCoolant = design(cube3, 1000, 900, ReactorSimulation.Status.STEADY, 10, 100, 10, 0.5);
        assertTrue(ReactorDesigner.better(lessCoolant, base, 1000));
        assertFalse(ReactorDesigner.better(base, lessCoolant, 1000));

        var coolerPeak = design(cube3, 1000, 499, ReactorSimulation.Status.STEADY, 10, 100, 10, 1.0);
        assertTrue(ReactorDesigner.better(coolerPeak, base, 1000));
        assertFalse(ReactorDesigner.better(base, coolerPeak, 1000));

        var higherExportTie = design(cube3, 1500, 500, ReactorSimulation.Status.STEADY, 10, 100, 10, 1.0);
        assertTrue(ReactorDesigner.better(higherExportTie, base, 1000));
        assertFalse(ReactorDesigner.better(base, higherExportTie, 1000));

        // Exact tie returns false (not strictly better).
        assertFalse(ReactorDesigner.better(base, base, 1000));
    }

    @Test
    void shortlistFiltersDangerousDeduplicatesByKeySortsAndEvictsAbove32() {
        var shortlist = new ReactorDesigner.Shortlist(0);
        shortlist.offer(null);
        shortlist.offer(design(box(3, 3, 3), 9999, 2500, ReactorSimulation.Status.MELTDOWN, 700, 10, 1, 0.0));
        assertTrue(shortlist.top(5).isEmpty());

        // Key reflects min x max x height and part composition.
        var l1 = box(3, 5, 2);
        l1.set(0, 0, SINGLE);
        l1.set(1, 0, VENT);
        l1.set(2, 0, ABSORBER);
        l1.set(0, 1, PIPE);
        l1.set(1, 1, REFLECTOR);
        assertEquals("3x5x2:1:1:1:1:1:test:single", ReactorDesigner.Shortlist.key(l1));

        // Transposed layout produces identical shortlist key.
        var l2 = box(5, 3, 2);
        l2.set(0, 0, SINGLE);
        l2.set(0, 1, VENT);
        l2.set(0, 2, ABSORBER);
        l2.set(1, 0, PIPE);
        l2.set(1, 1, REFLECTOR);
        assertEquals(ReactorDesigner.Shortlist.key(l1), ReactorDesigner.Shortlist.key(l2));

        // Same key keeps only the better candidate.
        var weaker = design(l1, 500, 100, ReactorSimulation.Status.STEADY, 5, 50, 2, 0.0);
        var stronger = design(l2, 900, 100, ReactorSimulation.Status.STEADY, 5, 50, 2, 0.0);
        shortlist.offer(weaker);
        shortlist.offer(stronger);
        assertEquals(1, shortlist.top(10).size());
        assertEquals(900, shortlist.top(10).getFirst().exportRate());
        // Offering weaker again must not overwrite stronger.
        shortlist.offer(weaker);
        assertEquals(900, shortlist.top(10).getFirst().exportRate());

        // Insert 40 distinct shapes -> shortlist evicts worst entries down to 32 and returns top K sorted.
        var big = new ReactorDesigner.Shortlist(0);
        for (int i = 1; i <= 40; i++) {
            var shape = box(3, 3, i);
            shape.set(1, 1, SINGLE);
            big.offer(design(shape, i * 100L, 100, ReactorSimulation.Status.STEADY, 5, 100, 1, 0.0));
        }
        var top5 = big.top(5);
        assertEquals(5, top5.size());
        assertEquals(4000, top5.get(0).exportRate());
        assertEquals(3900, top5.get(1).exportRate());
        assertEquals(3600, top5.get(4).exportRate());
        assertEquals(32, big.top(100).size());
        // The evicted bottom 8 (100..800) must no longer be present.
        assertEquals(900, big.top(100).getLast().exportRate());
    }

    @Test
    void layoutKeyCanonicalizesTransposedDimensionsAndDistinguishesHeight() {
        var wide = box(4, 2, 3);
        wide.set(0, 1, SINGLE);
        wide.set(3, 0, VENT);

        var tall = box(2, 4, 3);
        tall.set(1, 0, SINGLE);
        tall.set(0, 3, VENT);

        assertEquals(ReactorDesigner.LayoutKey.of(wide), ReactorDesigner.LayoutKey.of(tall));

        var tallerHeight = tall.copy();
        tallerHeight.setHeight(4, 64);
        assertNotEquals(ReactorDesigner.LayoutKey.of(wide), ReactorDesigner.LayoutKey.of(tallerHeight));
    }

    @Test
    void thermallyImpossibleChecksUncooledRodsExactCapacityAndRiskModes() {
        var cfg = new ReactorConfig(64, 128, 10, 4, 100, 2000, 600, 25_000, false, 2400);
        var normal = new ReactorDesigner.Request(0, ReactorDesigner.Risk.NORMAL, Set.of("test:single", "test:quad"), false);
        var cautious = new ReactorDesigner.Request(0, ReactorDesigner.Risk.CAUTIOUS, Set.of("test:single", "test:quad"), false);
        var aggressive = new ReactorDesigner.Request(0, ReactorDesigner.Risk.AGGRESSIVE, Set.of("test:single", "test:quad"), false);

        // Rod with only a reflector (no vent/pipe/absorber) is impossible in NORMAL, allowed in AGGRESSIVE.
        var uncooled = box(2, 1, 1);
        uncooled.set(0, 0, SINGLE);
        uncooled.set(1, 0, REFLECTOR);
        var simUncooled = new ReactorSimulation(uncooled, cfg, FUEL, ItemStack.EMPTY);
        assertTrue(ReactorDesigner.thermallyImpossible(simUncooled, uncooled, cfg, normal));
        assertFalse(ReactorDesigner.thermallyImpossible(simUncooled, uncooled, cfg, aggressive));

        // Exact capacity boundary: QUAD generates 76 heat/tick.
        // At NORMAL maxHeat=2000: 1 vent removes 2000/100 + 4 = 24.
        // 3 vents = 72 (< 76 -> impossible); 3 vents + 1 absorber touching QUAD = 72 + 10 = 82 (>= 76 -> possible).
        var threeVents = box(3, 3, 1);
        threeVents.set(1, 1, QUAD);
        threeVents.set(0, 1, VENT);
        threeVents.set(2, 1, VENT);
        threeVents.set(1, 0, VENT);
        var sim3v = new ReactorSimulation(threeVents, cfg, FUEL, ItemStack.EMPTY);
        assertTrue(ReactorDesigner.thermallyImpossible(sim3v, threeVents, cfg, normal));

        // Adding 1 absorber touching QUAD raises max cooling from 72 to 82 >= 76.
        threeVents.set(1, 2, ABSORBER);
        var sim3v1a = new ReactorSimulation(threeVents, cfg, FUEL, ItemStack.EMPTY);
        assertFalse(ReactorDesigner.thermallyImpossible(sim3v1a, threeVents, cfg, normal));

        // Under CAUTIOUS (65% -> maxHeat=1300): 1 vent removes 1300/100 + 4 = 17.
        // 3 vents + 1 absorber = 51 + 10 = 61 < 76 -> impossible under CAUTIOUS!
        assertTrue(ReactorDesigner.thermallyImpossible(sim3v1a, threeVents, cfg, cautious));

        // Absorber not adjacent to any ROD or PIPE contributes 0 absorbed capacity.
        var isolatedAbsorber = box(3, 3, 1);
        isolatedAbsorber.set(0, 0, QUAD); // 76 heat
        isolatedAbsorber.set(1, 0, VENT); // 24
        isolatedAbsorber.set(0, 1, VENT); // 24 (total 48)
        isolatedAbsorber.set(2, 2, ABSORBER); // adjacent to nothing -> 0
        var simIso = new ReactorSimulation(isolatedAbsorber, cfg, FUEL, ItemStack.EMPTY);
        assertTrue(ReactorDesigner.thermallyImpossible(simIso, isolatedAbsorber, cfg, normal));
    }

    @Test
    void balancedShapeCostRodPowerCenteredAxisCountsAndFootprints() {
        // balanced: exact 2:1 boundary
        assertTrue(ReactorDesigner.balanced(box(3, 6, 3)));
        assertFalse(ReactorDesigner.balanced(box(3, 7, 3)));
        assertTrue(ReactorDesigner.balanced(box(6, 3, 3)));
        assertFalse(ReactorDesigner.balanced(box(3, 3, 7)));

        // shapeCost: volume * (max/min)^2
        assertEquals(27.0, ReactorDesigner.shapeCost(3, 3, 3), 1e-6);
        assertEquals(3 * 6 * 3 * 4.0, ReactorDesigner.shapeCost(3, 6, 3), 1e-6);
        assertEquals(ReactorDesigner.shapeCost(4, 5, 3), ReactorDesigner.shapeCost(box(4, 5, 3)), 1e-6);

        // rodPower
        assertEquals(128 * 12 * 5L, ReactorDesigner.rodPower(ReactorConfig.DEFAULT, QUAD, 5));
        assertEquals(1L, ReactorDesigner.rodPower(ReactorConfig.DEFAULT, EMPTY, 5));

        // centeredAxis symmetry and bounds
        assertEquals(List.of(0), ReactorDesigner.centeredAxis(1, 1));
        assertEquals(List.of(0, 1), ReactorDesigner.centeredAxis(2, 3));
        assertEquals(List.of(1, 2), ReactorDesigner.centeredAxis(4, 3));
        assertEquals(List.of(0, 2, 4), ReactorDesigner.centeredAxis(5, 2));
        assertEquals(List.of(1, 4), ReactorDesigner.centeredAxis(6, 3));
        assertEquals(List.of(1, 4, 7), ReactorDesigner.centeredAxis(9, 3));

        // rodGroups are sorted center-to-edge and cover mirror pairs
        var groups = ReactorDesigner.rodGroups(box(5, 5, 1), 2, 2);
        assertEquals(4, groups.size());
        assertEquals(List.of(new ReactorDesigner.Cell(2, 2)), groups.getFirst(), "center group must come first");
        assertEquals(4, groups.getLast().size(), "corner group has 4 mirrored cells");

        // counts for target == 0 vs target > 0
        var maxOnly = ReactorDesigner.counts(groups, 0, 128);
        assertTrue(maxOnly.contains(4));
        assertTrue(maxOnly.contains(1));
        var targeted = ReactorDesigner.counts(groups, 500, 128);
        assertTrue(targeted.contains(1));
        assertTrue(targeted.contains(4));
        assertTrue(targeted.size() >= 3);

        // footprints includes base when near-square, and side x (side+1) near expected
        var base = box(3, 4, 3);
        var fps = ReactorDesigner.footprints(ReactorConfig.DEFAULT, QUAD, 50_000, base);
        assertEquals(new ReactorDesigner.Footprint(3, 4), fps.getFirst());
        assertTrue(fps.contains(new ReactorDesigner.Footprint(4, 4)));
        assertTrue(fps.stream().anyMatch(fp -> fp.length() == fp.width() + 1));
    }

    @Test
    void refinementMutationOperatorsWorkOnBoundaryCoreCheckerboardRadialAndRings() {
        // Empty layout -> operators return false cleanly
        var empty = box(5, 5, 1);
        assertFalse(ReactorDesigner.replaceRods(empty, DOUBLE));
        assertFalse(ReactorDesigner.replaceRodRing(empty, DOUBLE, true));
        assertFalse(ReactorDesigner.mixRods(empty, DOUBLE, SINGLE, true));

        // 5x5 grid with 3x3 rod lattice at coordinates {0, 2, 4} x {0, 2, 4} (all even world coordinates!)
        var lattice = box(5, 5, 1);
        for (int z : new int[]{0, 2, 4}) for (int x : new int[]{0, 2, 4}) lattice.set(x, z, SINGLE);

        // replaceRodRing(core=false) changes ONLY center rod (2, 2), leaving 8 boundary rods SINGLE!
        var coreOnly = lattice.copy();
        assertTrue(ReactorDesigner.replaceRodRing(coreOnly, DOUBLE, false));
        assertEquals(DOUBLE, coreOnly.get(2, 2));
        assertEquals(SINGLE, coreOnly.get(0, 0));
        assertEquals(SINGLE, coreOnly.get(4, 4));
        assertFalse(ReactorDesigner.replaceRodRing(coreOnly, DOUBLE, false), "second call is idempotent");

        // replaceRodRing(boundary=true) changes the 8 boundary rods, leaving center intact!
        var boundaryOnly = lattice.copy();
        assertTrue(ReactorDesigner.replaceRodRing(boundaryOnly, QUAD, true));
        assertEquals(QUAD, boundaryOnly.get(0, 0));
        assertEquals(QUAD, boundaryOnly.get(2, 0));
        assertEquals(SINGLE, boundaryOnly.get(2, 2));

        // mixRods checkerboard uses lattice ordinal indices (NOT world coords!), so {0,2,4} alternates properly!
        var checker = lattice.copy();
        assertTrue(ReactorDesigner.mixRods(checker, DOUBLE, SINGLE, true));
        assertEquals(DOUBLE, checker.get(0, 0)); // (0+0)%2 == 0 -> inner
        assertEquals(SINGLE, checker.get(2, 0)); // (1+0)%2 == 1 -> outer
        assertEquals(DOUBLE, checker.get(4, 0)); // (2+0)%2 == 0 -> inner
        assertEquals(SINGLE, checker.get(0, 2)); // (0+1)%2 == 1 -> outer
        assertEquals(DOUBLE, checker.get(2, 2)); // (1+1)%2 == 0 -> inner

        // mixRods radial puts inner near center (2,2) and outer on corners (0,0)/(4,4)
        var radial = lattice.copy();
        assertTrue(ReactorDesigner.mixRods(radial, QUAD, SINGLE, false));
        assertEquals(QUAD, radial.get(2, 2));
        assertEquals(SINGLE, radial.get(0, 0));
        assertEquals(SINGLE, radial.get(4, 4));

        // replaceNearestRing + replaceSecondRing + replacePipeRing
        var ringed = box(5, 5, 1);
        ringed.set(2, 2, SINGLE);
        assertTrue(ReactorDesigner.replaceNearestRing(ringed, VENT));
        assertEquals(VENT, ringed.get(1, 2));
        assertEquals(VENT, ringed.get(3, 2));
        assertEquals(VENT, ringed.get(2, 1));
        assertEquals(VENT, ringed.get(2, 3));
        assertEquals(EMPTY, ringed.get(0, 2));
        assertFalse(ReactorDesigner.replaceNearestRing(ringed, VENT));

        // Atomic replacePipeRing converts first ring VENT -> PIPE and populates second ring with ABSORBER
        assertTrue(ReactorDesigner.replacePipeRing(ringed, PIPE, ABSORBER));
        assertEquals(PIPE, ringed.get(1, 2));
        assertEquals(ABSORBER, ringed.get(0, 2));
        assertEquals(ABSORBER, ringed.get(1, 1));
        assertEquals(SINGLE, ringed.get(2, 2));

        // Second ring can also be swapped ABSORBER -> VENT directly
        assertTrue(ReactorDesigner.replaceSecondRing(ringed, VENT));
        assertEquals(VENT, ringed.get(0, 2));
        assertEquals(VENT, ringed.get(1, 1));
        assertFalse(ReactorDesigner.replaceSecondRing(ringed, VENT));

        // coolingAllowed checks all 3 modes
        assertTrue(ReactorDesigner.coolingAllowed(ReactorDesigner.CoolingMode.AUTO, ReactorParts.Kind.VENT));
        assertTrue(ReactorDesigner.coolingAllowed(ReactorDesigner.CoolingMode.AUTO, ReactorParts.Kind.ABSORBER));
        assertTrue(ReactorDesigner.coolingAllowed(ReactorDesigner.CoolingMode.PASSIVE, ReactorParts.Kind.VENT));
        assertFalse(ReactorDesigner.coolingAllowed(ReactorDesigner.CoolingMode.PASSIVE, ReactorParts.Kind.ABSORBER));
        assertFalse(ReactorDesigner.coolingAllowed(ReactorDesigner.CoolingMode.WITH_ICE, ReactorParts.Kind.VENT));
        assertTrue(ReactorDesigner.coolingAllowed(ReactorDesigner.CoolingMode.WITH_ICE, ReactorParts.Kind.ABSORBER));
    }

    @Test
    void placeOuterReflectorsOuterCoolingAndPlaceSidesHandleBothAxesAndOffCenterRods() {
        // Off-center rods along X: (1, 2) pushes outward to (0, 2), (3, 2) pushes outward to (4, 2), center (2, 2) places both sides!
        var alongX = box(5, 5, 1);
        alongX.set(1, 1, SINGLE);
        alongX.set(3, 1, SINGLE);
        alongX.set(2, 3, SINGLE);
        ReactorDesigner.placeOuterReflectors(alongX, REFLECTOR, true);
        assertEquals(REFLECTOR, alongX.get(0, 1), "left off-center rod gets reflector on x-1");
        assertEquals(REFLECTOR, alongX.get(4, 1), "right off-center rod gets reflector on x+1");
        assertEquals(REFLECTOR, alongX.get(1, 3), "center-line rod gets reflector on left");
        assertEquals(REFLECTOR, alongX.get(3, 3), "center-line rod gets reflector on right");

        // Off-center rods along Z
        var alongZ = box(5, 5, 1);
        alongZ.set(1, 1, SINGLE);
        alongZ.set(1, 3, SINGLE);
        alongZ.set(3, 2, SINGLE);
        ReactorDesigner.placeOuterReflectors(alongZ, REFLECTOR, false);
        assertEquals(REFLECTOR, alongZ.get(1, 0), "top off-center rod gets reflector on z-1");
        assertEquals(REFLECTOR, alongZ.get(1, 4), "bottom off-center rod gets reflector on z+1");
        assertEquals(REFLECTOR, alongZ.get(3, 1), "center-line rod gets reflector on z-1");
        assertEquals(REFLECTOR, alongZ.get(3, 3), "center-line rod gets reflector on z+1");

        // placeOuterCooling off-center on both axes
        var coolX = box(5, 5, 1);
        coolX.set(1, 2, SINGLE);
        coolX.set(3, 2, SINGLE);
        ReactorDesigner.placeOuterCooling(coolX, ABSORBER, true);
        assertEquals(ABSORBER, coolX.get(0, 2));
        assertEquals(ABSORBER, coolX.get(4, 2));

        var coolZ = box(5, 5, 1);
        coolZ.set(2, 1, SINGLE);
        coolZ.set(2, 3, SINGLE);
        ReactorDesigner.placeOuterCooling(coolZ, ABSORBER, false);
        assertEquals(ABSORBER, coolZ.get(2, 0));
        assertEquals(ABSORBER, coolZ.get(2, 4));

        // placeSides respects boundary x==0, x==width-1, z==0, z==length-1 without OOB
        var edge = box(2, 2, 1);
        ReactorDesigner.placeSides(edge, 0, 0, VENT, true);
        assertEquals(VENT, edge.get(1, 0));
        ReactorDesigner.placeSides(edge, 0, 0, ABSORBER, false);
        assertEquals(ABSORBER, edge.get(0, 1));
    }

    @Test
    @Timeout(30)
    void refineBestAndLocalSearchExerciseUpgradesDowngradesAndCoolingSwaps() {
        // 5x5 grid with 4 single rods surrounded by pipes + vents: refineBest upgrades rods & cooling
        var seedLayout = box(5, 5, 2);
        seedLayout.set(1, 1, SINGLE);
        seedLayout.set(3, 1, SINGLE);
        seedLayout.set(1, 3, SINGLE);
        seedLayout.set(3, 3, SINGLE);
        ReactorDesigner.fillNeighbors(seedLayout, ReactorParts.Kind.ROD, VENT, 1, 1);

        var req = new ReactorDesigner.Request(0, ReactorDesigner.Risk.NORMAL,
                Set.of("test:single", "test:double", "test:quad"), false, ReactorDesigner.CoolingMode.AUTO);
        var search = ReactorDesigner.Search.forRefinement(ReactorConfig.DEFAULT, FUEL, ItemStack.EMPTY, req, budget(), false);
        search.tryLayout(seedLayout.copy());
        assertNotNull(search.best);
        long beforeExport = search.best.exportRate();

        ReactorDesigner.refineBest(search.best, List.of(QUAD, DOUBLE, SINGLE), VENT, ABSORBER, PIPE, REFLECTOR, search);
        assertTrue(search.best.exportRate() > beforeExport,
                "refineBest should upgrade single-rod seed to higher output: " + beforeExport + " -> " + search.best.exportRate());

        // localSearch combined upgrade (rod upgrade + vent/absorber) and pipe -> vent/absorber cooling upgrade
        var pipeSeed = box(3, 3, 2);
        pipeSeed.set(1, 1, SINGLE);
        pipeSeed.set(1, 0, PIPE);
        pipeSeed.set(1, 2, PIPE);
        pipeSeed.set(0, 1, VENT);
        pipeSeed.set(2, 1, VENT);
        pipeSeed.set(0, 0, VENT);
        pipeSeed.set(2, 0, VENT);
        pipeSeed.set(0, 2, VENT);
        pipeSeed.set(2, 2, VENT);

        var localSearch = ReactorDesigner.Search.forRefinement(ReactorConfig.DEFAULT, FUEL, ItemStack.EMPTY, req, budget(), false);
        localSearch.tryLayout(pipeSeed.copy());
        assertNotNull(localSearch.best);
        long pipeBefore = localSearch.best.exportRate();
        ReactorDesigner.localSearch(List.of(SINGLE, DOUBLE, QUAD), VENT, ABSORBER, REFLECTOR, localSearch);
        assertTrue(localSearch.best.exportRate() > pipeBefore,
                "localSearch should upgrade rod and/or pipes for higher export");

        // Null / exhausted guard in refineBest and localSearch
        var exhaustedBudget = new ReactorDesigner.Budget(0, System.nanoTime() + 1_000_000_000L, () -> false);
        var exhaustedSearch = ReactorDesigner.Search.forRefinement(ReactorConfig.DEFAULT, FUEL, ItemStack.EMPTY, req, exhaustedBudget, false);
        ReactorDesigner.refineBest(null, List.of(SINGLE), VENT, ABSORBER, PIPE, REFLECTOR, exhaustedSearch);
        ReactorDesigner.localSearch(List.of(SINGLE), VENT, ABSORBER, REFLECTOR, exhaustedSearch);
        assertNull(exhaustedSearch.best);
    }

    @Test
    void budgetRespectsThreadInterruptFlag() {
        var b = new ReactorDesigner.Budget(10_000, System.nanoTime() + TimeUnit.SECONDS.toNanos(30), () -> false);
        assertFalse(b.exhausted());
        Thread.currentThread().interrupt();
        try {
            assertTrue(b.exhausted(), "interrupted thread must exhaust budget immediately");
        } finally {
            assertTrue(Thread.interrupted(), "clear interrupt flag after test");
        }
    }
}
