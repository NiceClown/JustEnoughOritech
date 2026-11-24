package com.justenoughoritech.printer;

import com.justenoughoritech.sim.ReactorLayout;
import com.justenoughoritech.sim.ReactorParts;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorWorldScannerTest {
    private static final ReactorParts.Part ROD =
            new ReactorParts.Part("oritech:reactor_rod", "Rod", null, ReactorParts.Kind.ROD, 1, 1);
    private static final ReactorParts.Part VENT =
            new ReactorParts.Part("oritech:reactor_heat_vent", "Vent", null, ReactorParts.Kind.VENT, 0, 0);
    private static final Map<String, ReactorParts.Part> PARTS = Map.of(
            ROD.id(), ROD,
            VENT.id(), VENT
    );

    @Test
    void scansConstructedReactorIncludingNonWestControllerAndPorts() {
        var layout = new ReactorLayout();
        layout.resize(4, 3, 64);
        layout.setHeight(3, 64);
        layout.setEnergyPorts(3);
        layout.set(1, 1, ROD);
        layout.set(1, 0, VENT);

        var worldBlocks = new HashMap<BlockPos, String>();
        var origin = new BlockPos(100, 64, -20);
        BlockPos controllerPos = null;
        for (var p : layout.placements()) {
            BlockPos pos = origin.offset(p.x(), p.y(), p.z());
            worldBlocks.put(pos, p.blockId());
            if (ReactorLayout.CONTROLLER.equals(p.blockId())) controllerPos = pos;
        }

        var res = ReactorWorldScanner.scan(pos -> worldBlocks.getOrDefault(pos, ""), controllerPos, 64, PARTS);
        assertTrue(res.isPresent());
        assertEquals(origin, res.get().origin());
        assertEquals(4, res.get().layout().width());
        assertEquals(3, res.get().layout().length());
        assertEquals(3, res.get().layout().height());
        assertEquals(3, res.get().layout().energyPorts());
        assertEquals(ROD.id(), res.get().layout().get(1, 1).id());
        assertEquals(VENT.id(), res.get().layout().get(1, 0).id());

        // Move controller to opposite (East) wall and verify scanner still reconstructs identical bounds.
        worldBlocks.put(controllerPos, ReactorLayout.WALL);
        BlockPos eastController = origin.offset(5, 2, 2);
        worldBlocks.put(eastController, ReactorLayout.CONTROLLER);
        var resEast = ReactorWorldScanner.scan(pos -> worldBlocks.getOrDefault(pos, ""), eastController, 64, PARTS);
        assertTrue(resEast.isPresent());
        assertEquals(origin, resEast.get().origin());
        assertEquals(4, resEast.get().layout().width());
        assertEquals(3, resEast.get().layout().length());
        assertEquals(3, resEast.get().layout().height());
    }

    @Test
    void rejectsMissingShellOrOversizedReactor() {
        var worldBlocks = new HashMap<BlockPos, String>();
        var origin = new BlockPos(0, 64, 0);
        var layout = new ReactorLayout();
        layout.resize(3, 3, 64);
        layout.setHeight(3, 64);
        layout.set(1, 1, ROD);
        BlockPos controllerPos = null;
        for (var p : layout.placements()) {
            BlockPos pos = origin.offset(p.x(), p.y(), p.z());
            worldBlocks.put(pos, p.blockId());
            if (ReactorLayout.CONTROLLER.equals(p.blockId())) controllerPos = pos;
        }

        // Reject when maxInterior is smaller than 3.
        assertTrue(ReactorWorldScanner.scan(pos -> worldBlocks.getOrDefault(pos, ""), controllerPos, 2, PARTS).isEmpty());

        // Break an outer corner so shell integrity check fails.
        worldBlocks.remove(origin);
        assertTrue(ReactorWorldScanner.scan(pos -> worldBlocks.getOrDefault(pos, ""), controllerPos, 64, PARTS).isEmpty());
    }
}
