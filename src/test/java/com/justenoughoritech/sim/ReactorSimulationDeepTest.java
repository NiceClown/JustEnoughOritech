package com.justenoughoritech.sim;

import com.justenoughoritech.network.ReactorConfigPayload;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorSimulationDeepTest {
    private static ReactorParts.Part part(String id, ReactorParts.Kind kind, int rods, int pulses) {
        return new ReactorParts.Part(id, id, null, kind, rods, pulses);
    }

    private static final ReactorParts.Part SINGLE = part("test:single", ReactorParts.Kind.ROD, 1, 1);
    private static final ReactorParts.Part DOUBLE = part("test:double", ReactorParts.Kind.ROD, 2, 4);
    private static final ReactorParts.Part QUAD = part("test:quad", ReactorParts.Kind.ROD, 4, 12);
    private static final ReactorParts.Part PIPE = part("test:pipe", ReactorParts.Kind.PIPE, 0, 0);
    private static final ReactorParts.Part ABSORBER = part("test:absorber", ReactorParts.Kind.ABSORBER, 0, 0);
    private static final ReactorParts.Part VENT = part("test:vent", ReactorParts.Kind.VENT, 0, 0);
    private static final ReactorParts.Part REFLECTOR = part("test:reflector", ReactorParts.Kind.REFLECTOR, 0, 0);

    private static ReactorLayout grid(int w, int l, int h) {
        var layout = new ReactorLayout();
        layout.resize(w, l, 64);
        layout.setHeight(h, 64);
        return layout;
    }

    private static ReactorInputs.Fuel fuel(int duration) {
        return new ReactorInputs.Fuel(ItemStack.EMPTY, duration);
    }

    @Test
    void defaultConfigMatchesOritechInteriorLimits() {
        var def = ReactorConfig.DEFAULT;
        assertEquals(64, def.maxSize(), "Oritech default outer maxSize 66 allows 64 interior core blocks");
        assertEquals(128, def.rfPerPulse());
        assertEquals(16, def.absorberRate());
        assertEquals(4, def.ventBaseRate());
        assertEquals(100, def.ventRelativeRate());
        assertEquals(2000, def.maxHeat());
        assertEquals(600, def.maxUnstableTicks());
        assertEquals(25_000, def.maxEnergyOutputPerPort());
        assertFalse(def.safeMode());
        assertEquals(2400, def.safeModeCooldown());
    }

    @Test
    void pulsesGenerationAndExportRateExactMathForRodsReflectorsAndAdjacency() {
        var layout = grid(3, 3, 4);
        layout.set(1, 1, DOUBLE);     // internal 4 pulses, rodCount=2
        layout.set(0, 1, REFLECTOR);  // adds +2 to DOUBLE
        layout.set(1, 0, SINGLE);     // internal 1 pulse, rodCount=1; adds +1 to DOUBLE, DOUBLE adds +2 to SINGLE
        layout.set(0, 0, REFLECTOR);  // diagonal to DOUBLE (no effect), orthogonal to SINGLE (+1 to SINGLE)
        layout.setEnergyPorts(1);

        var sim = new ReactorSimulation(layout, ReactorConfig.DEFAULT, fuel(10_000), ItemStack.EMPTY);
        // DOUBLE: 4 + 2 (reflector) + 1 (single) = 7 pulses
        // SINGLE: 1 + 2 (double) + 1 (reflector) = 4 pulses
        assertEquals(7, sim.cell(1, 1).pulses);
        assertEquals(4, sim.cell(1, 0).pulses);
        assertEquals(11L * 128, sim.generationPerLayer());
        assertEquals(0, sim.generation(), "generation is 0 before first tick");
        assertEquals(4, sim.totalCells());

        sim.start();
        sim.tick();
        long expectedGen = 11L * 128 * 4;
        assertEquals(expectedGen, sim.generation());
        assertEquals(expectedGen, sim.exportRate());

        // Capped export when ports cannot carry full generation.
        var cappedConfig = new ReactorConfig(64, 128, 16, 4, 100, 2000, 600, 1000, false, 2400);
        var capped = new ReactorSimulation(layout, cappedConfig, fuel(10_000), ItemStack.EMPTY);
        capped.start();
        capped.tick();
        assertEquals(expectedGen, capped.generation());
        assertEquals(1000, capped.exportRate());
    }

    @Test
    void exactHeatFormulaAndFuelConsumptionTiming() {
        var layout = grid(1, 1, 3);
        layout.set(0, 0, DOUBLE); // pulses=4 -> heat/tick = (4/2)*4 + 4 = 12; demand = 2*3 = 6/tick
        var sim = new ReactorSimulation(layout, ReactorConfig.DEFAULT, fuel(12), ItemStack.EMPTY);
        sim.start();

        // Tick 1: fuelRemaining 0 < 6 -> fuelItems=1, fuelRemaining=12-6=6; heat=12
        sim.tick();
        assertEquals(1, sim.fuelItems());
        assertEquals(12, sim.cell(0, 0).heat);

        // Tick 2: fuelRemaining 6 >= 6 -> fuelItems=1, fuelRemaining=0; heat=24
        sim.tick();
        assertEquals(1, sim.fuelItems());
        assertEquals(24, sim.cell(0, 0).heat);

        // Tick 3: fuelRemaining 0 < 6 -> fuelItems=2, fuelRemaining=6; heat=36
        sim.tick();
        assertEquals(2, sim.fuelItems());
        assertEquals(36, sim.cell(0, 0).heat);

        // Odd pulse count verifies integer division: pulses=1 -> 1/2*1 + 4 = 4
        var oddLayout = grid(2, 1, 1);
        oddLayout.set(0, 0, SINGLE);
        oddLayout.set(1, 0, REFLECTOR); // pulses = 2 -> 2/2*2 + 4 = 6
        var oddSim = new ReactorSimulation(oddLayout, ReactorConfig.DEFAULT, fuel(100), ItemStack.EMPTY);
        oddSim.start();
        oddSim.tick();
        assertEquals(6, oddSim.cell(0, 0).heat);

        // Quad rod: pulses=12 -> 12/2*12 + 4 = 76
        var quadLayout = grid(1, 1, 1);
        quadLayout.set(0, 0, QUAD);
        var quadSim = new ReactorSimulation(quadLayout, ReactorConfig.DEFAULT, fuel(100), ItemStack.EMPTY);
        quadSim.start();
        quadSim.tick();
        assertEquals(76, quadSim.cell(0, 0).heat);
    }

    @Test
    void lifecycleReadyPauseResumeMeltdownAndSafeCooldown() {
        var layout = grid(1, 1, 1);
        layout.set(0, 0, QUAD); // +76 heat/tick
        var meltdownCfg = new ReactorConfig(64, 128, 16, 4, 100, 100, 3, 25_000, false, 2400);
        var sim = new ReactorSimulation(layout, meltdownCfg, fuel(10_000), ItemStack.EMPTY);

        assertEquals(ReactorSimulation.Status.READY, sim.status());
        sim.tick(); // no-op while READY
        assertEquals(0, sim.ticks());

        sim.start();
        assertEquals(ReactorSimulation.Status.RUNNING, sim.status());
        sim.tick(); // heat=76 <= 100, unstable=0
        assertEquals(1, sim.ticks());
        assertEquals(0, sim.unstableTicks());

        sim.pause();
        assertEquals(ReactorSimulation.Status.READY, sim.status());
        sim.tick(); // paused no-op
        assertEquals(1, sim.ticks());

        sim.start();
        sim.tick(); // heat=152 > 100, unstable=1, ticksToMeltdown = 3 - 1 + 1 = 3
        assertEquals(1, sim.unstableTicks());
        assertEquals(3, sim.ticksToMeltdown());

        sim.tick(); // unstable=2, ticksToMeltdown=2
        assertEquals(2, sim.ticksToMeltdown());
        sim.tick(); // unstable=3, ticksToMeltdown=1
        assertEquals(1, sim.ticksToMeltdown());
        assertEquals(ReactorSimulation.Status.RUNNING, sim.status());

        sim.tick(); // unstable=4 > 3 -> MELTDOWN
        assertEquals(ReactorSimulation.Status.MELTDOWN, sim.status());
        assertEquals(0, sim.ticksToMeltdown());
        int finalTicks = sim.ticks();
        sim.pause(); // cannot leave terminal state
        assertEquals(ReactorSimulation.Status.MELTDOWN, sim.status());
        sim.start(); // start() must not resurrect a MELTDOWN simulation
        assertEquals(ReactorSimulation.Status.MELTDOWN, sim.status());
        sim.tick(); // no-op in terminal state
        assertEquals(finalTicks, sim.ticks());

        // Safe mode yields SAFE_COOLDOWN instead of MELTDOWN on the exact same tick.
        var safeCfg = new ReactorConfig(64, 128, 16, 4, 100, 100, 3, 25_000, true, 2400);
        var safeSim = new ReactorSimulation(layout, safeCfg, fuel(10_000), ItemStack.EMPTY);
        safeSim.start();
        for (int i = 0; i < 10 && safeSim.status() == ReactorSimulation.Status.RUNNING; i++) safeSim.tick();
        assertEquals(ReactorSimulation.Status.SAFE_COOLDOWN, safeSim.status());
        assertEquals(finalTicks, safeSim.ticks());
    }

    @Test
    void emptyLayoutStabilizesImmediatelyOnTickOne() {
        var layout = grid(2, 2, 1);
        layout.set(0, 0, VENT);
        var sim = new ReactorSimulation(layout, ReactorConfig.DEFAULT, fuel(100), ItemStack.EMPTY);
        sim.start();
        sim.tick();
        assertEquals(ReactorSimulation.Status.STEADY, sim.status());
        assertEquals(0, sim.generation());
        assertEquals(0, sim.maxHeatSeen());
        assertEquals(0.0, sim.steadyCoolantPerSecond());
    }

    @Test
    void ventOnlyCoolsHottestNeighborAndGuardsInvalidRates() {
        // Invalid ventRelativeRate=0 and negative ventBaseRate must never throw or heat cells.
        var badCfg = new ReactorConfig(64, 128, 16, -10, 0, 2000, 600, 25_000, false, 2400);
        var layout = grid(2, 1, 1);
        layout.set(0, 0, SINGLE);
        layout.set(1, 0, VENT);
        var sim = new ReactorSimulation(layout, badCfg, fuel(1000), ItemStack.EMPTY);
        sim.start();
        sim.tick();
        assertTrue(sim.cell(0, 0).heat <= 4, "vent with negative base rate must never add heat");
    }

    @Test
    void pipeConductsHeatFromRodToVentAndStabilizes() {
        var layout = grid(3, 1, 1);
        layout.set(0, 0, SINGLE);
        layout.set(1, 0, PIPE);
        layout.set(2, 0, VENT);
        var sim = new ReactorSimulation(layout, ReactorConfig.DEFAULT, fuel(10_000), ItemStack.EMPTY);
        assertFalse(sim.usesCoolant());
        sim.start();
        for (int i = 0; i < 100 && sim.status() == ReactorSimulation.Status.RUNNING; i++) sim.tick();
        assertEquals(ReactorSimulation.Status.STEADY, sim.status());
        assertEquals(0.0, sim.steadyCoolantPerSecond());
        assertTrue(sim.maxHeatSeen() <= ReactorConfig.DEFAULT.maxHeat());
    }

    @Test
    void absorberCoolantSteadyRateExactForDivisibleAndNonDivisibleHeights() {
        // Height = 10 divides COOLANT_ITEM_CAPACITY (1000): 100 active cooling ticks per coolant item.
        // At 20 TPS -> steady rate = 20.0 / 100 = 0.2 items/s (for a rod heated every tick).
        var layout10 = grid(2, 1, 10);
        layout10.set(0, 0, DOUBLE); // +12 heat/tick -> always > 0 when absorber runs
        layout10.set(1, 0, ABSORBER); // removes 16/tick when heat > 0
        var sim10 = new ReactorSimulation(layout10, ReactorConfig.DEFAULT, fuel(10_000), ItemStack.EMPTY);
        assertTrue(sim10.usesCoolant());
        sim10.start();
        for (int i = 0; i < 3600 && sim10.status() == ReactorSimulation.Status.RUNNING; i++) sim10.tick();
        assertEquals(ReactorSimulation.Status.STEADY, sim10.status());
        assertTrue(sim10.coolantItems() >= 1);
        // Double rod adds 12/tick, absorber takes 16/tick -> active on 3 of every 4 ticks or every tick depending on negative heat.
        assertTrue(sim10.steadyCoolantPerSecond() > 0.0 && sim10.steadyCoolantPerSecond() <= 0.2001,
                "unexpected coolant rate: " + sim10.steadyCoolantPerSecond());

        // Height = 50 with absorberRate = 12 == DOUBLE (+12/tick): 1000 / 50 = 20 ticks/item -> 20/20 = 1.0 items/s!
        var balancedCfg = new ReactorConfig(64, 128, 12, 4, 100, 2000, 600, 25_000, false, 2400);
        var exactLayout = grid(2, 1, 50);
        exactLayout.set(0, 0, DOUBLE);
        exactLayout.set(1, 0, ABSORBER);
        var exactSim = new ReactorSimulation(exactLayout, balancedCfg, fuel(10_000), ItemStack.EMPTY);
        exactSim.start();
        for (int i = 0; i < 3600 && exactSim.status() == ReactorSimulation.Status.RUNNING; i++) exactSim.tick();
        assertEquals(ReactorSimulation.Status.STEADY, exactSim.status());
        assertEquals(1.0, exactSim.steadyCoolantPerSecond(), 1e-6);

        // Exact boundary coolantRemaining == layout.height() (height = 500, capacity = 1000):
        // Tick 1 refills to 1000 -> cools -> 500 left; Tick 2 has 500 == 500 -> MUST cool (not stall!), 0 left!
        var halfLayout = new ReactorLayout();
        halfLayout.resize(2, 1, 64);
        halfLayout.setHeight(500, 1000);
        halfLayout.set(0, 0, DOUBLE);
        halfLayout.set(1, 0, ABSORBER);
        var halfSim = new ReactorSimulation(halfLayout, balancedCfg, fuel(10_000), ItemStack.EMPTY);
        halfSim.start();
        for (int i = 0; i < 100 && halfSim.status() == ReactorSimulation.Status.RUNNING; i++) halfSim.tick();
        assertEquals(ReactorSimulation.Status.STEADY, halfSim.status());
        assertEquals(10.0, halfSim.steadyCoolantPerSecond(), 1e-6, "2 ticks per item at 20 TPS = 10.0 items/s");

        // Two independent rod+absorber pairs (separated by an empty column) with height=500:
        // Each consumes 1 item per 2 ticks -> delta coolantItems == 2 per 2-tick period -> 20.0 * 2 / 2 = 20.0 items/s.
        // Using delta == 2 kills `20.0 * delta` -> `20.0 / delta` mutation!
        var dualAbs = new ReactorLayout();
        dualAbs.resize(3, 2, 64);
        dualAbs.setHeight(500, 1000);
        dualAbs.set(0, 0, DOUBLE);
        dualAbs.set(0, 1, ABSORBER);
        dualAbs.set(2, 0, DOUBLE);
        dualAbs.set(2, 1, ABSORBER);
        var dualSim = new ReactorSimulation(dualAbs, balancedCfg, fuel(10_000), ItemStack.EMPTY);
        dualSim.start();
        for (int i = 0; i < 100 && dualSim.status() == ReactorSimulation.Status.RUNNING; i++) dualSim.tick();
        assertEquals(ReactorSimulation.Status.STEADY, dualSim.status());
        assertEquals(20.0, dualSim.steadyCoolantPerSecond(), 1e-6);

        // Non-divisible height = 400 (capacity 1000 -> 600 -> 200 < 400 -> stall resets to 0, next tick refills!).
        // Order-independent: within 5 ticks it must hit the stall (maxHeatSeen >= 24) and refill (coolantItems == 2).
        var stall400 = new ReactorLayout();
        stall400.resize(2, 1, 64);
        stall400.setHeight(400, 1000);
        stall400.set(0, 0, DOUBLE);
        stall400.set(1, 0, ABSORBER);
        var stall400Sim = new ReactorSimulation(stall400, balancedCfg, fuel(10_000), ItemStack.EMPTY);
        stall400Sim.start();
        for (int i = 0; i < 5; i++) stall400Sim.tick();
        assertTrue(stall400Sim.maxHeatSeen() >= 24, "stall tick must skip cooling so heat climbs to >= 24");
        assertEquals(2, stall400Sim.coolantItems(), "stall must reset remaining coolant to 0 so refill happens");

        // Non-divisible height (height = 3) with exact-match absorberRate=12:
        // Every 334th tick hits the coolant modulo stall (1000 % 3 == 1), adding +12 unrecovered heat;
        // Brent's cycle detector MUST NOT falsely mark this drifting reactor as STEADY!
        var driftLayout = grid(2, 1, 3);
        driftLayout.set(0, 0, DOUBLE);
        driftLayout.set(1, 0, ABSORBER);
        var driftSim = new ReactorSimulation(driftLayout, balancedCfg, fuel(10_000), ItemStack.EMPTY);
        driftSim.start();
        for (int i = 0; i < 1500 && driftSim.status() == ReactorSimulation.Status.RUNNING; i++) driftSim.tick();
        assertEquals(ReactorSimulation.Status.RUNNING, driftSim.status(),
                "drifting reactor under modulo stall must not be marked STEADY");
        assertTrue(driftSim.maxHeatSeen() >= 48, "heat should drift upward across repeated modulo stalls");

        // With surplus cooling margin (default absorberRate=16 > rodHeat=12), height=3 recovers after stall and stabilizes.
        var stallRecovery = new ReactorSimulation(driftLayout, ReactorConfig.DEFAULT, fuel(10_000), ItemStack.EMPTY);
        stallRecovery.start();
        for (int i = 0; i < 3600 && stallRecovery.status() == ReactorSimulation.Status.RUNNING; i++) stallRecovery.tick();
        assertEquals(ReactorSimulation.Status.STEADY, stallRecovery.status());
        assertTrue(stallRecovery.steadyCoolantPerSecond() > 0.0);
    }

    @Test
    void brentDetectorCatchesPassiveOscillationWithoutHitting3600Ticks() {
        // Two single rods (+4 heat/tick each) sharing one vent with baseRate=8, rel=10000:
        // Vent cools one rod by 8 per tick, alternating between the two rods -> period-2 cycle!
        var cfg = new ReactorConfig(64, 128, 16, 8, 10_000, 2000, 600, 25_000, false, 2400);
        var layout = grid(3, 1, 1);
        layout.set(0, 0, SINGLE);
        layout.set(1, 0, VENT);
        layout.set(2, 0, SINGLE);
        var sim = new ReactorSimulation(layout, cfg, fuel(10_000), ItemStack.EMPTY);
        sim.start();
        for (int i = 0; i < 50 && sim.status() == ReactorSimulation.Status.RUNNING; i++) sim.tick();
        assertEquals(ReactorSimulation.Status.STEADY, sim.status(), "period-2 oscillation must stabilize rapidly");
        assertTrue(sim.ticks() < 20, "expected fast cycle detection, took " + sim.ticks() + " ticks");
        assertEquals(0.0, sim.steadyCoolantPerSecond());
        sim.start(); // start() must not resurrect a STEADY simulation
        assertEquals(ReactorSimulation.Status.STEADY, sim.status());

        // Same period-2 oscillation but with maxHeat = 6 (< oscillation peak 8):
        // Unstable ticks keep resetting so it never melts down, yet checkpointPeak > maxHeat MUST block STEADY!
        var lowMaxCfg = new ReactorConfig(64, 128, 16, 8, 10_000, 6, 600, 25_000, false, 2400);
        var overHeatOsc = new ReactorSimulation(layout, lowMaxCfg, fuel(10_000), ItemStack.EMPTY);
        overHeatOsc.start();
        for (int i = 0; i < 60 && overHeatOsc.status() == ReactorSimulation.Status.RUNNING; i++) overHeatOsc.tick();
        assertEquals(ReactorSimulation.Status.RUNNING, overHeatOsc.status(),
                "periodic oscillation exceeding maxHeat must never be marked STEADY");
    }

    @Test
    void heatHistoryRingBufferWrapsChronologicallyAndSnapshotHeatIsDefensive() {
        var layout = grid(1, 1, 1);
        layout.set(0, 0, SINGLE); // +4 heat/tick
        var cfg = new ReactorConfig(64, 128, 16, 4, 100, 10_000, 600, 25_000, false, 2400);
        var sim = new ReactorSimulation(layout, cfg, fuel(10_000), ItemStack.EMPTY);
        sim.start();

        assertEquals(0, sim.heatHistory().length);
        // Short history (< 96) verifies exact ordering before ring wraparound.
        for (int i = 0; i < 5; i++) sim.tick();
        int[] shortHist = sim.heatHistory();
        assertEquals(5, shortHist.length);
        for (int i = 0; i < 5; i++) assertEquals((i + 1) * 4, shortHist[i]);

        for (int i = 5; i < 120; i++) sim.tick();
        int[] history = sim.heatHistory();
        assertEquals(96, history.length);
        // Tick 25..120 -> heat = 25*4 .. 120*4
        for (int i = 0; i < 96; i++) {
            assertEquals((25 + i) * 4, history[i], "history index " + i);
        }
        assertEquals(480, sim.maxHeatSeen());

        int[] snap = sim.snapshotHeat();
        assertEquals(1, snap.length);
        assertEquals(480, snap[0]);
        snap[0] = -999;
        assertEquals(480, sim.snapshotHeat()[0], "mutating snapshot must not affect simulation");
    }

    @Test
    void coolantAlignedHeightFindsDivisorsAndGuardsZero() {
        assertEquals(1, ReactorSimulation.coolantAlignedHeight(1, 10));
        assertEquals(4, ReactorSimulation.coolantAlignedHeight(3, 4));
        assertEquals(4, ReactorSimulation.coolantAlignedHeight(3, 10));
        assertEquals(8, ReactorSimulation.coolantAlignedHeight(6, 10));
        assertEquals(3, ReactorSimulation.coolantAlignedHeight(3, 3));
        assertEquals(20, ReactorSimulation.coolantAlignedHeight(11, 25));
        assertEquals(0, ReactorSimulation.coolantAlignedHeight(0, 10));
        assertEquals(-1, ReactorSimulation.coolantAlignedHeight(-1, 10));
    }

    @Test
    void reactorPartsHelpersHandleKindsHumanizeAndReflectionFallbacks() {
        assertEquals(ReactorParts.Kind.ROD, ReactorParts.kind("ReactorRodBlock"));
        assertEquals(ReactorParts.Kind.PIPE, ReactorParts.kind("ReactorHeatPipeBlock"));
        assertEquals(ReactorParts.Kind.ABSORBER, ReactorParts.kind("ReactorAbsorberBlock"));
        assertEquals(ReactorParts.Kind.VENT, ReactorParts.kind("ReactorHeatVentBlock"));
        assertEquals(ReactorParts.Kind.REFLECTOR, ReactorParts.kind("ReactorNeutronReflectorBlock"));
        assertEquals(ReactorParts.Kind.EMPTY, ReactorParts.kind("ReactorWallBlock"));
        assertEquals(ReactorParts.Kind.EMPTY, ReactorParts.kind(""));
        assertEquals(ReactorParts.Kind.EMPTY, ReactorParts.kind(null));

        assertEquals("Reactor Rod", ReactorParts.humanize("reactor_rod"));
        assertEquals("Nuclear Reactor Controller", ReactorParts.humanize("nuclear_reactor_controller"));
        assertEquals("Leading Double", ReactorParts.humanize("__leading__double__"));
        assertEquals("", ReactorParts.humanize(""));
        assertEquals("", ReactorParts.humanize(null));
        assertEquals("Empty", ReactorParts.Part.EMPTY.toString());

        record FakeBlock(int getRodCount, Long longVal, String textVal, Integer nullVal) {}
        var fake = new FakeBlock(4, 12L, "notanumber", null);
        assertEquals(4, ReactorParts.readInt(fake, "getRodCount", 99));
        assertEquals(12, ReactorParts.readInt(fake, "longVal", 99));
        assertEquals(99, ReactorParts.readInt(fake, "textVal", 99));
        assertEquals(99, ReactorParts.readInt(fake, "nullVal", 99));
        assertEquals(99, ReactorParts.readInt(fake, "missingMethod", 99));
    }

    @Test
    void pipeExactConductionMathAndEqualHeatNoTransfer() {
        // Test exact diff/4 + 10 conduction on a controlled 2-cell layout (ROD + PIPE).
        // QUAD generates +76 heat/tick. On tick 1: depending on HashMap order either:
        // - ROD first (heat=76), then PIPE pulls min(76/4 + 10, 76) = 29 -> rod=47, pipe=29; OR
        // - PIPE first (diff=0 -> 0 moved), then ROD=76; tick 2 PIPE pulls 29 -> rod=47+76=123, pipe=29.
        var layout = grid(2, 1, 1);
        layout.set(0, 0, QUAD);
        layout.set(1, 0, PIPE);
        var cfg = new ReactorConfig(64, 128, 16, 4, 100, 10_000, 600, 25_000, false, 2400);
        var sim = new ReactorSimulation(layout, cfg, fuel(10_000), ItemStack.EMPTY);
        sim.start();
        sim.tick();
        int rod1 = sim.cell(0, 0).heat;
        int pipe1 = sim.cell(1, 0).heat;
        assertEquals(76, rod1 + pipe1, "pipe conserves total heat across cells");
        assertTrue((rod1 == 47 && pipe1 == 29) || (rod1 == 76 && pipe1 == 0),
                "unexpected tick-1 heat split rod=" + rod1 + " pipe=" + pipe1);
        sim.tick();
        int rod2 = sim.cell(0, 0).heat;
        int pipe2 = sim.cell(1, 0).heat;
        assertEquals(152, rod2 + pipe2, "total heat conserved after tick 2");
        // Exact tick-2 split for diff/4+10 (kills diff*4 and n.heat+heat mutations):
        // ROD-first order: (47+76)-33=90, 29+33=62. PIPE-first order: (76-29)+76=123, 0+29=29.
        assertTrue((rod2 == 90 && pipe2 == 62) || (rod2 == 123 && pipe2 == 29),
                "unexpected tick-2 heat split rod=" + rod2 + " pipe=" + pipe2);

        // Small diff (SINGLE +4/tick): min(4/4 + 10, 4) = 4 (clamped to diff).
        var small = grid(2, 1, 1);
        small.set(0, 0, SINGLE);
        small.set(1, 0, PIPE);
        var smallSim = new ReactorSimulation(small, cfg, fuel(10_000), ItemStack.EMPTY);
        smallSim.start();
        smallSim.tick();
        smallSim.tick();
        // 3-cell chain QUAD - PIPE1 - PIPE2 verifies pipe pulls ONLY from hotter neighbors (never colder)
        // and conserves total heat without producing negative heat.
        var chain = grid(3, 1, 1);
        chain.set(0, 0, QUAD);
        chain.set(1, 0, PIPE);
        chain.set(2, 0, PIPE);
        var chainSim = new ReactorSimulation(chain, cfg, fuel(10_000), ItemStack.EMPTY);
        chainSim.start();
        for (int t = 1; t <= 4; t++) {
            chainSim.tick();
            int h0 = chainSim.cell(0, 0).heat;
            int h1 = chainSim.cell(1, 0).heat;
            int h2 = chainSim.cell(2, 0).heat;
            assertEquals(76 * t, h0 + h1 + h2, "heat conserved on tick " + t);
            assertTrue(h0 >= 0 && h1 >= 0 && h2 >= 0, "pipe must never create negative heat");
        }
        assertTrue(chainSim.cell(0, 0).heat > chainSim.cell(1, 0).heat + chainSim.cell(2, 0).heat,
                "QUAD must stay hottest while pipes conduct a bounded fraction");
        assertTrue(chainSim.cell(1, 0).heat > 0 && chainSim.cell(2, 0).heat > 0);
    }

    @Test
    void ventPicksStrictlyHottestNeighborAndExactRemovalMath() {
        // Place SINGLE (+4/tick) and QUAD (+76/tick) flanking a single VENT with ventBaseRate=4, rel=10.
        // After heat builds, QUAD is much hotter than SINGLE, so VENT must cool QUAD exclusively,
        // leaving SINGLE accumulating +4/tick monotonically!
        var cfg = new ReactorConfig(64, 128, 16, 4, 10, 10_000, 600, 25_000, false, 2400);
        var layout = grid(3, 1, 1);
        layout.set(0, 0, SINGLE);
        layout.set(1, 0, VENT);
        layout.set(2, 0, QUAD);
        var sim = new ReactorSimulation(layout, cfg, fuel(10_000), ItemStack.EMPTY);
        sim.start();
        for (int i = 0; i < 5; i++) sim.tick();
        // SINGLE may have been cooled at most once on tick 1 if visited before QUAD, never once QUAD > SINGLE.
        assertTrue(sim.cell(0, 0).heat >= 16, "vent must target hottest neighbor (QUAD), not SINGLE: " + sim.cell(0, 0).heat);
        assertTrue(sim.cell(2, 0).heat < 5 * 76, "vent must actively cool QUAD");

        // Exact vent formula: max=200, rel=100, base=4 -> removed = 200/100 + 4 = 6.
        // SINGLE (+4/tick) + REFLECTOR (+2 -> pulses=2 -> +6 heat/tick) + VENT (rel=100, base=4):
        // Steady fixed point is when max/100 + 4 == 6 -> max/100 == 2 -> heat in [200..299]!
        var exactLayout = grid(3, 1, 1);
        exactLayout.set(0, 0, REFLECTOR);
        exactLayout.set(1, 0, SINGLE);
        exactLayout.set(2, 0, VENT);
        var exactSim = new ReactorSimulation(exactLayout, ReactorConfig.DEFAULT, fuel(10_000), ItemStack.EMPTY);
        exactSim.start();
        for (int i = 0; i < 500 && exactSim.status() == ReactorSimulation.Status.RUNNING; i++) exactSim.tick();
        assertEquals(ReactorSimulation.Status.STEADY, exactSim.status());
        assertTrue(exactSim.maxHeatSeen() >= 200 && exactSim.maxHeatSeen() <= 206,
                "fixed point for +6 heat vs max/100+4 must sit around 200, got " + exactSim.maxHeatSeen());
    }

    @Test
    void idleAbsorberDoesNotConsumeCoolantAndExactMaxHeatBoundaryIsStable() {
        // Active rod separated from an idle absorber+pipe+vent group so simulation ticks > 1 with activeRods > 0:
        // Idle absorber next to 0-heat pipe must NOT pull heat negative or drain coolant; idle vent must NOT pull negative!
        var idleLayout = new ReactorLayout();
        idleLayout.resize(4, 1, 64);
        idleLayout.setHeight(500, 1000);
        idleLayout.set(0, 0, SINGLE);   // heats +4/tick (activeRods=1)
        // gap at (1,0) EMPTY
        idleLayout.set(2, 0, PIPE);     // stays at heat 0
        idleLayout.set(3, 0, ABSORBER); // adjacent only to 0-heat PIPE
        var idleCfg = new ReactorConfig(64, 128, 16, 4, 100, 10_000, 600, 25_000, false, 2400);
        var idleSim = new ReactorSimulation(idleLayout, idleCfg, fuel(10_000), ItemStack.EMPTY);
        idleSim.start();
        for (int i = 0; i < 10; i++) idleSim.tick();
        assertEquals(0, idleSim.cell(2, 0).heat, "absorber must not cool 0-heat neighbor below 0");
        assertEquals(1, idleSim.coolantItems(), "idle absorber with removed==0 must never drain coolant");

        // Isolated vent next to 0-heat pipe also keeps pipe at 0.
        var idleVent = grid(4, 1, 1);
        idleVent.set(0, 0, SINGLE);
        idleVent.set(2, 0, PIPE);
        idleVent.set(3, 0, VENT);
        var idleVentSim = new ReactorSimulation(idleVent, idleCfg, fuel(10_000), ItemStack.EMPTY);
        idleVentSim.start();
        for (int i = 0; i < 5; i++) idleVentSim.tick();
        assertEquals(0, idleVentSim.cell(2, 0).heat);

        // Exact maxHeat boundary: SINGLE (+4/tick) + VENT (base=4, rel=100) stabilizes at heat=4.
        // With maxHeat = 4 (exact equality hottest == maxHeat): unstableTicks MUST stay 0 and status STEADY!
        var eqCfg = new ReactorConfig(64, 128, 16, 4, 100, 4, 0, 25_000, false, 2400);
        var eqLayout = grid(2, 1, 1);
        eqLayout.set(0, 0, SINGLE);
        eqLayout.set(1, 0, VENT);
        var eqSim = new ReactorSimulation(eqLayout, eqCfg, fuel(10_000), ItemStack.EMPTY);
        eqSim.start();
        for (int i = 0; i < 10 && eqSim.status() == ReactorSimulation.Status.RUNNING; i++) eqSim.tick();
        assertEquals(ReactorSimulation.Status.STEADY, eqSim.status(), "hottest == maxHeat must remain stable");
        assertEquals(0, eqSim.unstableTicks());
        assertEquals(4, eqSim.maxHeatSeen());

        // With maxHeat = 3 (< hottest 4) and maxUnstableTicks = 0: immediate meltdown on tick 1!
        var overCfg = new ReactorConfig(64, 128, 16, 4, 100, 3, 0, 25_000, false, 2400);
        var overSim = new ReactorSimulation(eqLayout, overCfg, fuel(10_000), ItemStack.EMPTY);
        overSim.start();
        overSim.tick();
        assertEquals(ReactorSimulation.Status.MELTDOWN, overSim.status());
        assertEquals(1, overSim.unstableTicks());

        // Fuel and coolant getters return configured values.
        var f = fuel(777);
        var gSim = new ReactorSimulation(eqLayout, eqCfg, f, ItemStack.EMPTY);
        assertEquals(f, gSim.fuel());
        assertEquals(ItemStack.EMPTY, gSim.coolant());
    }

    @Test
    void reactorInputsAvailableAndPartsHierarchyNameEdgeCases() {
        var validInputs = new ReactorInputs(List.of(fuel(200)), List.of(ItemStack.EMPTY), null);
        assertTrue(validInputs.available());
        assertFalse(new ReactorInputs(List.of(fuel(200)), List.of(ItemStack.EMPTY), "err").available());
        assertFalse(new ReactorInputs(List.of(), List.of(ItemStack.EMPTY), null).available());
        assertFalse(new ReactorInputs(List.of(fuel(200)), List.of(), null).available());

        assertEquals("", ReactorParts.hierarchyName(Object.class));
        assertEquals("", ReactorParts.hierarchyName(String.class));
        assertEquals("", ReactorParts.hierarchyName(null));
    }

    @Test
    void reactorInputsParseRejectsMalformedIdsWithoutThrowing() {
        assertNotNull(ReactorInputs.parse("a:b"));
        assertEquals("a:b", ReactorInputs.parse("a:b").toString());
        assertNotNull(ReactorInputs.parse("minecraft:coal"));
        assertEquals("oritech:uranium_pellet", ReactorInputs.parse("oritech:uranium_pellet").toString());

        assertNull(ReactorInputs.parse(null));
        assertNull(ReactorInputs.parse(""));
        assertNull(ReactorInputs.parse("nocolon"));
        assertNull(ReactorInputs.parse(":missing_ns"));
        assertNull(ReactorInputs.parse("missing_path:"));
        assertNull(ReactorInputs.parse("UPPER:invalid"));
        assertNull(ReactorInputs.parse("minecraft:bad space"));
        assertNull(ReactorInputs.parse("a:b:c"));

        var fromNull = ReactorInputs.fromServer(null);
        assertFalse(fromNull.available());
        assertNotNull(fromNull.error());

        // Malformed payload IDs are skipped safely, yielding no_fuel instead of crashing.
        var badPayload = new ReactorConfigPayload("2.0.0-exp14", true, 64, 128, 16, 4, 100, 2000, 600,
                25_000, false, 2400,
                List.of(new ReactorConfigPayload.FuelOption("BAD ID:!!!", 200),
                        new ReactorConfigPayload.FuelOption("minecraft:coal", 0)),
                List.of("ALSO BAD!!!"), "");
        var parsed = ReactorInputs.fromServer(badPayload);
        assertFalse(parsed.available());
        assertEquals("gui.just_enough_oritech.no_fuel", parsed.error());
    }
}
