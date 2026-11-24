package com.justenoughoritech.client;

import com.justenoughoritech.network.ReactorConfigPayload;
import com.justenoughoritech.sim.ReactorConfig;
import com.justenoughoritech.sim.ReactorDesigner;
import com.justenoughoritech.sim.ReactorInputs;
import com.justenoughoritech.sim.ReactorLayout;
import com.justenoughoritech.sim.ReactorParts;
import com.justenoughoritech.sim.ReactorSimulation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientComponentsTest {
    private static final ReactorParts.Part SINGLE =
            new ReactorParts.Part("test:single", "Single", null, ReactorParts.Kind.ROD, 1, 1);
    private static final ReactorParts.Part VENT =
            new ReactorParts.Part("test:vent", "Vent", null, ReactorParts.Kind.VENT, 0, 0);

    private static ReactorConfigPayload payload(String version, boolean compatible, int maxSize, int maxHeat, String issue) {
        return new ReactorConfigPayload(version, compatible, maxSize, 256, 20, 6, 80, maxHeat, 400, 30_000,
                true, 1200, List.of(new ReactorConfigPayload.FuelOption("minecraft:coal", 500)),
                List.of("minecraft:ice"), issue);
    }

    @BeforeEach
    @AfterEach
    void resetClientState() {
        ClientRuntimeConfig.reset();
        var wb = Workbench.INSTANCE;
        wb.layout.resize(3, 3, 64);
        wb.layout.setHeight(3, 64);
        wb.layout.setEnergyPorts(1);
        wb.layout.clear();
        wb.parts = List.of(ReactorParts.Part.EMPTY);
        wb.partsLoaded = false;
        wb.selectedPart = 0;
        wb.selectedFuel = 0;
        wb.selectedCoolant = 0;
        wb.selectedLayer = 0;
        wb.gridOffsetX = 0;
        wb.gridOffsetZ = 0;
        wb.cursorX = 0;
        wb.cursorZ = 0;
        wb.speed = 1;
        wb.edited();
    }

    @Test
    void clientRuntimeConfigHandlesAllCompatibilityStatesAndFieldMapping() {
        assertEquals(ClientRuntimeConfig.Compatibility.WAITING, ClientRuntimeConfig.compatibility());
        assertEquals(ReactorConfig.DEFAULT, ClientRuntimeConfig.config());
        assertEquals("2.0.0-exp14", ClientRuntimeConfig.latestCompatible());
        assertNull(ClientRuntimeConfig.runtimeIssue());

        // accept(null) must be ignored without wiping existing snapshot.
        var exp12 = payload("2.0.0-exp12", true, 64, 2500, "");
        ClientRuntimeConfig.accept(exp12);
        ClientRuntimeConfig.accept(null);
        assertSame(exp12, ClientRuntimeConfig.snapshot());
        assertEquals(ClientRuntimeConfig.Compatibility.VERIFIED, ClientRuntimeConfig.compatibility());
        assertNull(ClientRuntimeConfig.runtimeIssue());

        // Every single config field maps faithfully.
        var cfg = ClientRuntimeConfig.config();
        assertEquals(64, cfg.maxSize());
        assertEquals(256, cfg.rfPerPulse());
        assertEquals(20, cfg.absorberRate());
        assertEquals(6, cfg.ventBaseRate());
        assertEquals(80, cfg.ventRelativeRate());
        assertEquals(2500, cfg.maxHeat());
        assertEquals(400, cfg.maxUnstableTicks());
        assertEquals(30_000, cfg.maxEnergyOutputPerPort());
        assertTrue(cfg.safeMode());
        assertEquals(1200, cfg.safeModeCooldown());

        // exp14 is also VERIFIED.
        ClientRuntimeConfig.accept(payload("2.0.0-exp14", true, 14, 2000, ""));
        assertEquals(ClientRuntimeConfig.Compatibility.VERIFIED, ClientRuntimeConfig.compatibility());
        assertEquals(14, ClientRuntimeConfig.config().maxSize());

        // Unknown compatible version is UNVERIFIED, not INCOMPATIBLE.
        ClientRuntimeConfig.accept(payload("2.0.0-exp15", true, 64, 2000, ""));
        assertEquals(ClientRuntimeConfig.Compatibility.UNVERIFIED, ClientRuntimeConfig.compatibility());

        // Runtime failure overrides version compatibility and exposes runtimeIssue.
        ClientRuntimeConfig.setRuntimeCheck(false, "gui.just_enough_oritech.missing_blocks");
        assertEquals(ClientRuntimeConfig.Compatibility.INCOMPATIBLE, ClientRuntimeConfig.compatibility());
        assertEquals("gui.just_enough_oritech.missing_blocks", ClientRuntimeConfig.runtimeIssue());

        // Explicit runtime check true restores UNVERIFIED / VERIFIED.
        ClientRuntimeConfig.setRuntimeCheck(true, null);
        assertEquals(ClientRuntimeConfig.Compatibility.UNVERIFIED, ClientRuntimeConfig.compatibility());
        assertNull(ClientRuntimeConfig.runtimeIssue());

        // Server-side incompatible payload falls back to ReactorConfig.DEFAULT and surfaces issue.
        ClientRuntimeConfig.accept(payload("2.0.0-exp14", false, 14, 1500, "gui.just_enough_oritech.physics_changed"));
        assertEquals(ClientRuntimeConfig.Compatibility.INCOMPATIBLE, ClientRuntimeConfig.compatibility());
        assertEquals(ReactorConfig.DEFAULT, ClientRuntimeConfig.config());
        assertEquals("gui.just_enough_oritech.physics_changed", ClientRuntimeConfig.runtimeIssue());
    }

    @Test
    void simulatorWidgetHeatColorExactThresholdBoundariesAndOverflowSafety() {
        // Default maxHeat = 2000: <1300 COOL, [1300, 2000) WARM, >=2000 HOT
        int cool = 0xFF418C50, warm = 0xFFBE9D3D, hot = 0xFFFF414A;
        assertEquals(cool, SimulatorWidget.heatColor(-50));
        assertEquals(cool, SimulatorWidget.heatColor(0));
        assertEquals(cool, SimulatorWidget.heatColor(1299));
        assertEquals(warm, SimulatorWidget.heatColor(1300));
        assertEquals(warm, SimulatorWidget.heatColor(1999));
        assertEquals(hot, SimulatorWidget.heatColor(2000));
        assertEquals(hot, SimulatorWidget.heatColor(Integer.MAX_VALUE));

        // Custom server maxHeat = 100: 64 COOL, 65 WARM, 99 WARM, 100 HOT
        ClientRuntimeConfig.accept(payload("2.0.0-exp14", true, 64, 100, ""));
        assertEquals(cool, SimulatorWidget.heatColor(64));
        assertEquals(warm, SimulatorWidget.heatColor(65));
        assertEquals(warm, SimulatorWidget.heatColor(99));
        assertEquals(hot, SimulatorWidget.heatColor(100));
    }

    @Test
    void textViewportScrollOffsetPingPongPhasesAndBoundaries() {
        // Guard non-positive inputs.
        assertEquals(0, TextViewport.scrollOffsetAt(5_000, 0, 0.024));
        assertEquals(0, TextViewport.scrollOffsetAt(5_000, -5, 0.024));
        assertEquals(0, TextViewport.scrollOffsetAt(5_000, 50, 0));
        assertEquals(0, TextViewport.scrollOffsetAt(5_000, 50, -0.1));
        assertEquals(0, TextViewport.scrollOffsetAt(0, 50, 0.024));
        assertEquals(0, TextViewport.scrollOffsetAt(-100, 50, 0.024));

        // max = 100, speed = 0.1 px/ms -> travel = 1000 ms, hold = 1200 ms, full cycle = 4400 ms.
        double speed = 0.1;
        int max = 100;
        assertEquals(0, TextViewport.scrollOffsetAt(1, max, speed));
        assertEquals(0, TextViewport.scrollOffsetAt(1199, max, speed));
        assertEquals(0, TextViewport.scrollOffsetAt(1200, max, speed));
        assertEquals(1, TextViewport.scrollOffsetAt(1210, max, speed));
        assertEquals(50, TextViewport.scrollOffsetAt(1700, max, speed));
        assertEquals(99, TextViewport.scrollOffsetAt(2199, max, speed));
        // End hold [2200, 3400)
        assertEquals(100, TextViewport.scrollOffsetAt(2200, max, speed));
        assertEquals(100, TextViewport.scrollOffsetAt(2800, max, speed));
        assertEquals(100, TextViewport.scrollOffsetAt(3399, max, speed));
        // Reverse travel [3400, 4400)
        assertEquals(100, TextViewport.scrollOffsetAt(3400, max, speed));
        assertEquals(99, TextViewport.scrollOffsetAt(3410, max, speed));
        assertEquals(50, TextViewport.scrollOffsetAt(3900, max, speed));
        assertEquals(1, TextViewport.scrollOffsetAt(4390, max, speed));
        // Full wrap at 4400 ms -> back to 0
        assertEquals(0, TextViewport.scrollOffsetAt(4400, max, speed));
        assertEquals(50, TextViewport.scrollOffsetAt(4400 + 1700, max, speed));
    }

    @Test
    void workbenchEditedClampsOffsetsAndIncrementsRevision() {
        var wb = Workbench.INSTANCE;
        wb.layout.resize(10, 10, 64);
        wb.layout.setHeight(8, 64);
        wb.gridOffsetX = 5;
        wb.gridOffsetZ = 5;
        wb.selectedLayer = 7;
        wb.cursorX = 9;
        wb.cursorZ = 9;
        wb.simulation = new ReactorSimulation(wb.layout, ReactorConfig.DEFAULT,
                new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000), ItemStack.EMPTY);
        wb.running = true;
        wb.issue = "gui.just_enough_oritech.need_rod";
        wb.designStatus = Component.literal("old");
        int revBefore = wb.revision();

        wb.layout.resize(3, 4, 64);
        wb.layout.setHeight(2, 64);
        wb.edited();

        assertEquals(revBefore + 1, wb.revision());
        assertEquals(0, wb.gridOffsetX);
        assertEquals(0, wb.gridOffsetZ);
        assertEquals(1, wb.selectedLayer);
        assertEquals(2, wb.cursorX);
        assertEquals(3, wb.cursorZ);
        assertFalse(wb.running);
        assertNull(wb.simulation);
        assertNull(wb.issue);
        assertNull(wb.designStatus);
    }

    @Test
    void workbenchPartCyclingWrapsInBothDirections() {
        var wb = Workbench.INSTANCE;
        wb.parts = List.of(ReactorParts.Part.EMPTY, SINGLE, VENT);
        wb.selectedPart = 0;
        assertEquals(ReactorParts.Part.EMPTY, wb.selected());

        wb.cyclePart(1);
        assertEquals(SINGLE, wb.selected());
        wb.cyclePart(1);
        assertEquals(VENT, wb.selected());
        wb.cyclePart(1);
        assertEquals(ReactorParts.Part.EMPTY, wb.selected());

        wb.cyclePart(-1);
        assertEquals(VENT, wb.selected());
        wb.selectedPart = -4;
        assertEquals(VENT, wb.selected());
    }

    @Test
    void workbenchApplyDesignUpdatesLayoutRevisionAndStatusMessage() {
        var wb = Workbench.INSTANCE;
        var built = new ReactorLayout();
        built.resize(4, 4, 64);
        built.setHeight(5, 64);
        built.setEnergyPorts(2);
        built.set(1, 1, SINGLE);
        built.set(1, 0, VENT);

        var safeDesign = new ReactorDesigner.Design(built, 5000, 120, 5,
                ReactorSimulation.Status.STEADY, 3, 100, 5, 0.0, new int[16]);
        int rev = wb.revision();
        wb.applyDesign(safeDesign, 4000);
        assertEquals(rev + 1, wb.revision());
        assertEquals(4, wb.layout.width());
        assertEquals(5, wb.layout.height());
        assertEquals(2, wb.layout.energyPorts());
        assertEquals(SINGLE, wb.layout.get(1, 1));
        assertNotNull(wb.designStatus);
        assertEquals("gui.just_enough_oritech.auto_found",
                ((TranslatableContents) wb.designStatus.getContents()).getKey());

        // Target == 0 and exact exportRate == target both report auto_found (not auto_goal_missed).
        wb.applyDesign(safeDesign, 0);
        assertEquals("gui.just_enough_oritech.auto_found",
                ((TranslatableContents) wb.designStatus.getContents()).getKey());
        wb.applyDesign(safeDesign, 5000);
        assertEquals("gui.just_enough_oritech.auto_found",
                ((TranslatableContents) wb.designStatus.getContents()).getKey());

        // Safe design that missed a higher target sets auto_goal_missed.
        wb.applyDesign(safeDesign, 5001);
        assertEquals("gui.just_enough_oritech.auto_goal_missed",
                ((TranslatableContents) wb.designStatus.getContents()).getKey());

        // Dangerous design sets auto_risky_applied.
        var riskyDesign = new ReactorDesigner.Design(built, 20_000, 3000, 5,
                ReactorSimulation.Status.MELTDOWN, 650, 100, 5, 0.0, new int[16]);
        wb.applyDesign(riskyDesign, 10_000);
        assertEquals("gui.just_enough_oritech.auto_risky_applied",
                ((TranslatableContents) wb.designStatus.getContents()).getKey());
    }

    @Test
    void workbenchTickPauseResetAndCancelAutoDesignLifecycle() {
        var wb = Workbench.INSTANCE;
        // pause() and tick() when simulation == null are safe no-ops.
        wb.running = true;
        wb.tick();
        assertTrue(wb.running);
        wb.pause();
        assertFalse(wb.running);

        // Uncooled rod stays RUNNING for >10 ticks: verifies exact speed stepping, !running no-op, and running stays true.
        wb.layout.set(1, 1, SINGLE);
        wb.simulation = new ReactorSimulation(wb.layout, ReactorConfig.DEFAULT,
                new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000), ItemStack.EMPTY);
        wb.simulation.start();
        wb.running = false;
        wb.speed = 2;
        wb.tick();
        assertEquals(0, wb.simulation.ticks(), "tick() must do nothing while !running");
        wb.running = true;
        wb.tick();
        assertEquals(2, wb.simulation.ticks(), "speed=2 must advance exactly 2 ticks");
        assertTrue(wb.running, "running must remain true while simulation is still RUNNING");

        // Cooled rod ticks until steady automatically stops wb.running.
        wb.layout.set(1, 0, VENT);
        wb.simulation = new ReactorSimulation(wb.layout, ReactorConfig.DEFAULT,
                new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000), ItemStack.EMPTY);
        wb.simulation.start();
        wb.running = true;
        wb.speed = 2;
        for (int i = 0; i < 10 && wb.running; i++) wb.tick();
        assertFalse(wb.running);
        assertEquals(ReactorSimulation.Status.STEADY, wb.simulation.status());

        // Pause and reset clear running state cleanly.
        wb.simulation = new ReactorSimulation(wb.layout, ReactorConfig.DEFAULT,
                new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000), ItemStack.EMPTY);
        wb.simulation.start();
        wb.running = true;
        wb.pause();
        assertFalse(wb.running);
        assertEquals(ReactorSimulation.Status.READY, wb.simulation.status());

        wb.reset();
        assertNull(wb.simulation);
        assertNull(wb.issue);
        assertFalse(wb.running);

        // Cancelling when no search is in flight is a safe no-op.
        wb.cancelAutoDesign();
        assertFalse(wb.designing());

        // Cancelling an in-flight search sets cancel flag, cancels future, increments generation, and notifies callback with null.
        var flag = new AtomicBoolean(false);
        var future = new CompletableFuture<ReactorDesigner.Result>();
        var callbackCount = new AtomicInteger(0);
        wb.designing = true;
        wb.autoDesignCancelled = flag;
        wb.autoDesignFuture = future;
        wb.autoDesignCallback = res -> {
            assertNull(res);
            callbackCount.incrementAndGet();
        };
        long genBefore = wb.autoDesignGeneration;
        assertTrue(wb.designing());

        // ClientRuntimeConfig.accept() must invalidate workbench and cancel in-flight autoDesign!
        wb.designStatus = Component.literal("stale");
        ClientRuntimeConfig.accept(payload("2.0.0-exp14", true, 64, 2000, ""));
        assertTrue(flag.get(), "cancel flag must be set on server snapshot replacement");
        assertTrue(future.isCancelled(), "in-flight future must be cancelled");
        assertEquals(1, callbackCount.get(), "owner callback must receive null exactly once");
        assertFalse(wb.designing());
        assertEquals(genBefore + 1, wb.autoDesignGeneration);
        assertNull(wb.designStatus);
    }

    @Test
    void workbenchRefreshInputsHandlesIncompatibleAndCachingAndStartGuards() {
        var wb = Workbench.INSTANCE;
        // WAITING state: refreshInputs and start are safe no-ops.
        wb.refreshInputs();
        wb.start();
        assertFalse(wb.running);
        assertNull(wb.simulation);

        // Incompatible server payload marks client INCOMPATIBLE, sets issue, and updates runtimeCheck.
        var bad = payload("2.0.0-exp14", false, 64, 2000, "gui.just_enough_oritech.no_fuel");
        ClientRuntimeConfig.accept(bad);
        wb.refreshInputs();
        assertEquals("gui.just_enough_oritech.no_fuel", wb.issue);
        assertFalse(wb.inputs.available());
        assertEquals(ClientRuntimeConfig.Compatibility.INCOMPATIBLE, ClientRuntimeConfig.compatibility());
        assertEquals("gui.just_enough_oritech.no_fuel", ClientRuntimeConfig.runtimeIssue());

        // Caching skips redundant work when same snapshot and force == false, re-evaluates when force == true.
        wb.issue = "cleared";
        wb.refreshInputs(false);
        assertEquals("cleared", wb.issue, "cached snapshot should not re-run without force");
        wb.refreshInputs(true);
        assertEquals("gui.just_enough_oritech.no_fuel", wb.issue, "forced refresh must re-evaluate");

        // start() must refuse to start while INCOMPATIBLE (with partsLoaded to avoid MC registry lookup).
        wb.partsLoaded = true;
        wb.start();
        assertFalse(wb.running);
        assertNull(wb.simulation);
    }

    @Test
    void textViewportKeyDistinguishesCoordinatesIncludingNegatives() {
        assertEquals(TextViewport.key(10, 20), TextViewport.key(10, 20));
        assertNotEquals(TextViewport.key(10, 20), TextViewport.key(20, 10));
        assertNotEquals(TextViewport.key(0, -1), TextViewport.key(-1, 0));
        assertNotEquals(TextViewport.key(1, -1), TextViewport.key(0, -1));
    }

    @Test
    void workbenchBlueprintStatusResetsOnEditAndDisconnectAndDegreesMatchRotations() {
        var wb = Workbench.INSTANCE;
        var layout = new com.justenoughoritech.printer.PrinterLayout(2, 2, 2, 1, List.of("a", "b", "c", "d"));
        wb.paperStaged = true;
        wb.lastWrittenBlueprint = new com.justenoughoritech.printer.BlueprintData(layout, java.util.Optional.empty());
        wb.blueprintStatus = Component.literal("written");
        wb.blueprintStatusError = false;

        wb.edited();
        assertNull(wb.lastWrittenBlueprint);
        assertNull(wb.blueprintStatus);
        assertFalse(wb.blueprintStatusError);
        assertTrue(wb.paperStaged, "editing keeps paper ready to write the updated layout");

        wb.lastWrittenBlueprint = new com.justenoughoritech.printer.BlueprintData(layout, java.util.Optional.empty());
        wb.blueprintStatus = Component.literal("err");
        wb.blueprintStatusError = true;
        wb.invalidateServerState();
        assertNull(wb.lastWrittenBlueprint);
        assertNull(wb.blueprintStatus);
        assertFalse(wb.blueprintStatusError);

        assertEquals(0, com.justenoughoritech.printer.BlueprintData.rotationDegrees(null));
        assertEquals(0, com.justenoughoritech.printer.BlueprintData.rotationDegrees(net.minecraft.world.level.block.Rotation.NONE));
        assertEquals(90, com.justenoughoritech.printer.BlueprintData.rotationDegrees(net.minecraft.world.level.block.Rotation.CLOCKWISE_90));
        assertEquals(180, com.justenoughoritech.printer.BlueprintData.rotationDegrees(net.minecraft.world.level.block.Rotation.CLOCKWISE_180));
        assertEquals(270, com.justenoughoritech.printer.BlueprintData.rotationDegrees(net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90));
    }
}
