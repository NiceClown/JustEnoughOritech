package com.justenoughoritech.sim;

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorDesignerDeepTest {
    private static final ReactorInputs.Fuel FUEL = new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000);
    private static final Set<String> ALL_RODS = Set.of("test:single", "test:double", "test:quad");

    private static ReactorParts.Part part(String id, ReactorParts.Kind kind, int rods, int pulses) {
        return new ReactorParts.Part(id, id, null, kind, rods, pulses);
    }

    private static final ReactorParts.Part SINGLE = part("test:single", ReactorParts.Kind.ROD, 1, 1);
    private static final ReactorParts.Part DOUBLE = part("test:double", ReactorParts.Kind.ROD, 2, 4);
    private static final ReactorParts.Part QUAD = part("test:quad", ReactorParts.Kind.ROD, 4, 12);
    private static final ReactorParts.Part PIPE = part("test:pipe", ReactorParts.Kind.PIPE, 0, 0);
    private static final ReactorParts.Part REFLECTOR = part("test:reflector", ReactorParts.Kind.REFLECTOR, 0, 0);
    private static final ReactorParts.Part ABSORBER = part("test:absorber", ReactorParts.Kind.ABSORBER, 0, 0);
    private static final ReactorParts.Part VENT = part("test:vent", ReactorParts.Kind.VENT, 0, 0);

    private static List<ReactorParts.Part> parts(boolean includeVent, boolean includeAbsorber) {
        var list = new ArrayList<ReactorParts.Part>();
        list.add(ReactorParts.Part.EMPTY);
        list.add(SINGLE);
        list.add(DOUBLE);
        list.add(QUAD);
        list.add(PIPE);
        list.add(REFLECTOR);
        if (includeVent) list.add(VENT);
        if (includeAbsorber) list.add(ABSORBER);
        return list;
    }

    private static ReactorLayout box(int w, int l, int h) {
        var layout = new ReactorLayout();
        layout.resize(w, l, 64);
        layout.setHeight(h, 64);
        return layout;
    }

    private static void verifyIndependently(ReactorDesigner.Design design, ReactorConfig config,
                                            ReactorDesigner.Risk risk, boolean expectSafe) {
        assertNotNull(design);
        var layout = design.layout();
        assertTrue(layout.validationErrors(config.maxSize()).isEmpty(),
                "design layout must pass validation: " + layout.validationErrors(config.maxSize()));

        long totalBlocks = layout.blockCounts().values().stream().mapToLong(Integer::longValue).sum();
        assertEquals(totalBlocks, design.buildBlocks(), "buildBlocks must match ReactorLayout.blockCounts sum");

        var sim = new ReactorSimulation(layout, config, FUEL, ItemStack.EMPTY);
        sim.start();
        for (int t = 0; t < 3_600 && sim.status() == ReactorSimulation.Status.RUNNING; t++) sim.tick();

        if (expectSafe) {
            assertFalse(design.dangerous());
            assertEquals(ReactorSimulation.Status.STEADY, sim.status());
            assertEquals(design.exportRate(), sim.exportRate());
            assertEquals(design.peakHeat(), sim.maxHeatSeen());
            assertTrue((long) sim.maxHeatSeen() * 100 <= (long) config.maxHeat() * risk.maximumHeatPercent,
                    "peakHeat " + sim.maxHeatSeen() + " exceeds risk budget");
            assertEquals(design.coolantPerSecond(), sim.steadyCoolantPerSecond(), 1e-6);
        } else {
            assertTrue(design.dangerous());
            assertTrue(sim.status() == ReactorSimulation.Status.MELTDOWN
                    || sim.status() == ReactorSimulation.Status.SAFE_COOLDOWN);
            assertEquals(design.exportRate(), sim.exportRate());
        }
    }

    @Test
    void growthLimitHonorsServerMaxUpTo256() {
        assertEquals(64, ReactorDesigner.growthLimit(64));
        assertEquals(66, ReactorDesigner.growthLimit(66));
        assertEquals(100, ReactorDesigner.growthLimit(100));
        assertEquals(256, ReactorDesigner.growthLimit(256));
        assertEquals(256, ReactorDesigner.growthLimit(300));
        assertEquals(14, ReactorDesigner.growthLimit(14));
        assertEquals(1, ReactorDesigner.growthLimit(1));
        assertEquals(1, ReactorDesigner.growthLimit(0));
    }

    @Test
    void rejectsInvalidAndNullInputsCleanly() {
        var validLayout = box(5, 5, 3);
        var validParts = parts(true, true);
        var validReq = new ReactorDesigner.Request(1000, ReactorDesigner.Risk.NORMAL, ALL_RODS, false);
        assertEquals(ReactorDesigner.CoolingMode.AUTO, validReq.coolingMode());

        // Null guards
        assertNull(ReactorDesigner.find(null, ReactorConfig.DEFAULT, validParts, FUEL, ItemStack.EMPTY, validReq).safe());
        assertNull(ReactorDesigner.find(validLayout, null, validParts, FUEL, ItemStack.EMPTY, validReq).safe());
        assertNull(ReactorDesigner.find(validLayout, ReactorConfig.DEFAULT, null, FUEL, ItemStack.EMPTY, validReq).safe());
        assertNull(ReactorDesigner.find(validLayout, ReactorConfig.DEFAULT, validParts, null, ItemStack.EMPTY, validReq).safe());
        assertNull(ReactorDesigner.find(validLayout, ReactorConfig.DEFAULT, validParts, FUEL, ItemStack.EMPTY, null).safe());

        // Oversized layout along each axis (> maxSize rejected, == maxSize accepted)
        var smallCap = new ReactorConfig(4, 128, 16, 4, 100, 2000, 600, 25_000, false, 2400);
        assertNull(ReactorDesigner.find(box(5, 3, 3), smallCap, validParts, FUEL, ItemStack.EMPTY, validReq).safe());
        assertNull(ReactorDesigner.find(box(3, 5, 3), smallCap, validParts, FUEL, ItemStack.EMPTY, validReq).safe());
        assertNull(ReactorDesigner.find(box(3, 3, 5), smallCap, validParts, FUEL, ItemStack.EMPTY, validReq).safe());
        assertNotNull(ReactorDesigner.find(box(4, 4, 4), smallCap, validParts, FUEL, ItemStack.EMPTY, validReq).safe(),
                "layout with dimensions exactly equal to maxSize must be accepted");

        // Negative target & empty allowed rods
        var negReq = new ReactorDesigner.Request(-1, ReactorDesigner.Risk.NORMAL, ALL_RODS, false);
        assertNull(ReactorDesigner.find(validLayout, ReactorConfig.DEFAULT, validParts, FUEL, ItemStack.EMPTY, negReq).safe());
        var noRods = new ReactorDesigner.Request(0, ReactorDesigner.Risk.NORMAL, Set.of(), false);
        assertNull(ReactorDesigner.find(validLayout, ReactorConfig.DEFAULT, validParts, FUEL, ItemStack.EMPTY, noRods).safe());

        // Bad config parameters
        var zeroRf = new ReactorConfig(64, 0, 16, 4, 100, 2000, 600, 25_000, false, 2400);
        assertNull(ReactorDesigner.find(validLayout, zeroRf, validParts, FUEL, ItemStack.EMPTY, validReq).safe());
        var zeroPort = new ReactorConfig(64, 128, 16, 4, 100, 2000, 600, 0, false, 2400);
        assertNull(ReactorDesigner.find(validLayout, zeroPort, validParts, FUEL, ItemStack.EMPTY, validReq).safe());
        var zeroHeat = new ReactorConfig(64, 128, 16, 4, 100, 0, 600, 25_000, false, 2400);
        assertNull(ReactorDesigner.find(validLayout, zeroHeat, validParts, FUEL, ItemStack.EMPTY, validReq).safe());
    }

    @Test
    @Timeout(30)
    void passiveModeNeverPlacesAbsorbersAndFailsWithoutVents() {
        var passiveReq = new ReactorDesigner.Request(0, ReactorDesigner.Risk.NORMAL, ALL_RODS, false,
                ReactorDesigner.CoolingMode.PASSIVE);
        var res = ReactorDesigner.find(box(5, 5, 3), ReactorConfig.DEFAULT, parts(true, true),
                FUEL, ItemStack.EMPTY, passiveReq);
        verifyIndependently(res.safe(), ReactorConfig.DEFAULT, ReactorDesigner.Risk.NORMAL, true);
        assertEquals(0.0, res.safe().coolantPerSecond());
        var layout = res.safe().layout();
        for (int z = 0; z < layout.length(); z++) {
            for (int x = 0; x < layout.width(); x++) {
                assertFalse(layout.get(x, z).kind() == ReactorParts.Kind.ABSORBER,
                        "PASSIVE mode must never place heat absorbers");
            }
        }
        assertNull(res.dangerous());

        // Without vents, PASSIVE mode has no legal cooler.
        var noVent = ReactorDesigner.find(box(5, 5, 3), ReactorConfig.DEFAULT, parts(false, true),
                FUEL, ItemStack.EMPTY, passiveReq);
        assertNull(noVent.safe());
        assertNull(noVent.dangerous());
    }

    @Test
    @Timeout(30)
    void withIceModeRequiresAbsorbersAndFailsWithoutThem() {
        var iceReq = new ReactorDesigner.Request(0, ReactorDesigner.Risk.NORMAL, ALL_RODS, false,
                ReactorDesigner.CoolingMode.WITH_ICE);
        var res = ReactorDesigner.find(box(5, 5, 3), ReactorConfig.DEFAULT, parts(true, true),
                FUEL, ItemStack.EMPTY, iceReq);
        verifyIndependently(res.safe(), ReactorConfig.DEFAULT, ReactorDesigner.Risk.NORMAL, true);
        boolean hasAbsorber = false;
        var layout = res.safe().layout();
        for (int z = 0; z < layout.length(); z++) {
            for (int x = 0; x < layout.width(); x++) {
                if (layout.get(x, z).kind() == ReactorParts.Kind.ABSORBER) hasAbsorber = true;
            }
        }
        assertTrue(hasAbsorber, "WITH_ICE mode must include at least one absorber");
        assertTrue(res.safe().coolantPerSecond() > 0.0);

        // Without absorbers, WITH_ICE has no legal cooler.
        var noAbsorber = ReactorDesigner.find(box(5, 5, 3), ReactorConfig.DEFAULT, parts(true, false),
                FUEL, ItemStack.EMPTY, iceReq);
        assertNull(noAbsorber.safe());
        assertNull(noAbsorber.dangerous());
    }

    @Test
    @Timeout(30)
    void aggressiveModeOffersDangerousFallbackOnlyWhenTargetUnmetAndRespectsSafeMode() {
        // 3x3x2 fixed cannot safely reach 100_000 RF/t, so AGGRESSIVE finds a higher-output risky option.
        var unmetReq = new ReactorDesigner.Request(100_000, ReactorDesigner.Risk.AGGRESSIVE, ALL_RODS, false,
                ReactorDesigner.CoolingMode.AUTO);
        var meltdownRes = ReactorDesigner.find(box(3, 3, 2), ReactorConfig.DEFAULT, parts(true, true),
                FUEL, ItemStack.EMPTY, unmetReq);
        verifyIndependently(meltdownRes.safe(), ReactorConfig.DEFAULT, ReactorDesigner.Risk.AGGRESSIVE, true);
        verifyIndependently(meltdownRes.dangerous(), ReactorConfig.DEFAULT, ReactorDesigner.Risk.AGGRESSIVE, false);
        assertTrue(meltdownRes.dangerous().exportRate() > meltdownRes.safe().exportRate());
        assertEquals(ReactorSimulation.Status.MELTDOWN, meltdownRes.dangerous().outcome());

        // Under safeMode = true, the dangerous candidate stops in SAFE_COOLDOWN instead of MELTDOWN.
        var safeModeCfg = new ReactorConfig(64, 128, 16, 4, 100, 2000, 600, 25_000, true, 2400);
        var safeModeRes = ReactorDesigner.find(box(3, 3, 2), safeModeCfg, parts(true, true),
                FUEL, ItemStack.EMPTY, unmetReq);
        assertNotNull(safeModeRes.dangerous());
        assertEquals(ReactorSimulation.Status.SAFE_COOLDOWN, safeModeRes.dangerous().outcome());

        // When the target IS met safely, dangerous must be suppressed (null).
        var metReq = new ReactorDesigner.Request(500, ReactorDesigner.Risk.AGGRESSIVE, ALL_RODS, false,
                ReactorDesigner.CoolingMode.AUTO);
        var metRes = ReactorDesigner.find(box(3, 3, 2), ReactorConfig.DEFAULT, parts(true, true),
                FUEL, ItemStack.EMPTY, metReq);
        assertNotNull(metRes.safe());
        assertTrue(metRes.safe().exportRate() >= 500);
        assertNull(metRes.dangerous(), "dangerous candidate must be cleared once target is safely met");
    }

    @Test
    @Timeout(30)
    void minimalFootprintsOneByOneAndThreeByOne() {
        // 1x1x1 and symmetric 2x1x1 cannot fit a rod + cooler -> no safe design; AGGRESSIVE finds a dangerous 1-rod design.
        var normal = ReactorDesigner.find(box(1, 1, 1), ReactorConfig.DEFAULT, parts(true, true),
                FUEL, ItemStack.EMPTY, new ReactorDesigner.Request(0, ReactorDesigner.Risk.NORMAL, ALL_RODS, false));
        assertNull(normal.safe());
        assertNull(normal.dangerous());

        var aggressive = ReactorDesigner.find(box(1, 1, 1), ReactorConfig.DEFAULT, parts(true, true),
                FUEL, ItemStack.EMPTY, new ReactorDesigner.Request(1000, ReactorDesigner.Risk.AGGRESSIVE, ALL_RODS, false));
        assertNull(aggressive.safe());
        verifyIndependently(aggressive.dangerous(), ReactorConfig.DEFAULT, ReactorDesigner.Risk.AGGRESSIVE, false);

        // 3x1x1 and 1x3x1 are the smallest 4-way symmetric layouts with 1 center rod + 2 flanking coolers.
        var threeByOne = ReactorDesigner.find(box(3, 1, 1), ReactorConfig.DEFAULT, parts(true, true),
                FUEL, ItemStack.EMPTY, new ReactorDesigner.Request(0, ReactorDesigner.Risk.NORMAL, ALL_RODS, false));
        verifyIndependently(threeByOne.safe(), ReactorConfig.DEFAULT, ReactorDesigner.Risk.NORMAL, true);

        var oneByThree = ReactorDesigner.find(box(1, 3, 1), ReactorConfig.DEFAULT, parts(true, true),
                FUEL, ItemStack.EMPTY, new ReactorDesigner.Request(0, ReactorDesigner.Risk.NORMAL, ALL_RODS, false));
        verifyIndependently(oneByThree.safe(), ReactorConfig.DEFAULT, ReactorDesigner.Risk.NORMAL, true);
    }

    @Test
    @Timeout(30)
    void ftbEvolution2InteriorLimit14IsRespectedUnderResize() {
        // FTB Evolution 2 configures Oritech outer maxSize=16 -> interior maxSize=14.
        var ftbConfig = new ReactorConfig(14, 128, 16, 4, 100, 2000, 600, 25_000, false, 2400);
        var req = new ReactorDesigner.Request(50_000, ReactorDesigner.Risk.NORMAL, ALL_RODS, true,
                ReactorDesigner.CoolingMode.AUTO);
        var res = ReactorDesigner.find(box(3, 3, 3), ftbConfig, parts(true, true), FUEL, ItemStack.EMPTY, req);
        verifyIndependently(res.safe(), ftbConfig, ReactorDesigner.Risk.NORMAL, true);
        assertTrue(res.safe().exportRate() >= 50_000);
        assertTrue(res.safe().layout().width() <= 14);
        assertTrue(res.safe().layout().length() <= 14);
        assertTrue(res.safe().layout().height() <= 14);
    }

    @Test
    void designDefensivelyCopiesHeatArrayAndLocalSearchSurvivesEmptyHeat() {
        int[] src = {10, 20, 30, 40};
        var layout = box(2, 2, 1);
        layout.set(0, 0, SINGLE);
        layout.set(1, 0, VENT);
        layout.set(0, 1, VENT);
        layout.set(1, 1, SINGLE);

        var design = new ReactorDesigner.Design(layout, 256, 40, 2,
                ReactorSimulation.Status.STEADY, 5, 30, 2, 0.0, src);
        src[0] = 999;
        assertEquals(10, design.heat()[0], "input array mutation must not leak into Design");
        int[] returned = design.heat();
        returned[1] = 888;
        assertEquals(20, design.heat()[1], "returned array mutation must not leak into Design");

        var nullHeat = new ReactorDesigner.Design(layout, 256, 40, 2,
                ReactorSimulation.Status.STEADY, 5, 30, 2, 0.0, null);
        assertArrayEquals(new int[0], nullHeat.heat());

        // localSearch with empty heat[] must not throw ArrayIndexOutOfBoundsException.
        var budget = new ReactorDesigner.Budget(100_000L,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10), () -> false);
        var search = ReactorDesigner.Search.forRefinement(ReactorConfig.DEFAULT, FUEL, ItemStack.EMPTY,
                new ReactorDesigner.Request(0, ReactorDesigner.Risk.NORMAL, ALL_RODS, false), budget, false);
        search.best = nullHeat;
        ReactorDesigner.localSearch(List.of(SINGLE, DOUBLE), VENT, ABSORBER, REFLECTOR, search);
        assertNotNull(search.best);
    }

    @Test
    void budgetChildAbsorbDeadlineAndCancellationWorkAccurately() {
        var cancel = new AtomicBoolean(false);
        long future = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        var parent = new ReactorDesigner.Budget(1000, future, cancel::get);
        assertFalse(parent.exhausted());
        assertEquals(1000, parent.remaining());

        var child = parent.child(400);
        assertEquals(400, child.remaining());
        child.spend(150);
        assertEquals(250, child.remaining());
        parent.absorb(child);
        assertEquals(850, parent.remaining(), "unused child budget must remain in parent");

        var overspent = parent.child(100);
        overspent.spend(2000);
        parent.absorb(overspent);
        assertEquals(0, parent.remaining());
        assertTrue(parent.exhausted());

        // Past deadline
        var expired = new ReactorDesigner.Budget(1000, System.nanoTime() - 1, () -> false);
        assertTrue(expired.exhausted());

        // Cancelled
        var cancellable = new ReactorDesigner.Budget(1000, future, cancel::get);
        assertFalse(cancellable.exhausted());
        cancel.set(true);
        assertTrue(cancellable.exhausted());
    }

    @Test
    void requestDefensivelyCopiesAllowedRods() {
        var mutable = new HashSet<String>(List.of("test:single"));
        var req = new ReactorDesigner.Request(100, ReactorDesigner.Risk.CAUTIOUS, mutable, true);
        mutable.add("test:quad");
        assertEquals(Set.of("test:single"), req.allowedRods());
    }
}
