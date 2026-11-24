package com.justenoughoritech.sim;

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorSimulationTest {
    private static ReactorParts.Part part(String id, ReactorParts.Kind kind, int rods, int pulses) {
        return new ReactorParts.Part(id, id, null, kind, rods, pulses);
    }

    @Test
    void passiveFixedPointStabilisesQuickly() {
        var layout = new ReactorLayout();
        layout.resize(3, 3, 66);
        layout.setHeight(1, 66);
        layout.set(1, 1, part("test:single", ReactorParts.Kind.ROD, 1, 1));
        layout.set(1, 0, part("test:vent", ReactorParts.Kind.VENT, 0, 0));
        var simulation = new ReactorSimulation(layout, ReactorConfig.DEFAULT,
                new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000), ItemStack.EMPTY);
        simulation.start();
        for (int tick = 0; tick < 8 && simulation.status() == ReactorSimulation.Status.RUNNING; tick++)
            simulation.tick();
        assertEquals(ReactorSimulation.Status.STEADY, simulation.status());
        assertEquals(0.0, simulation.steadyCoolantPerSecond());
        var rod = simulation.cell(1, 1);
        var vent = simulation.cell(1, 0);
        assertEquals(ReactorConfig.DEFAULT.rfPerPulse(), simulation.cellGeneration(rod));
        assertEquals(4, simulation.cellHeatGeneration(rod));
        assertEquals(0L, simulation.cellGeneration(vent));
        assertTrue(simulation.fuelPerSecond() > 0.0);
        assertEquals(simulation.fuelPerSecond() * 60.0, simulation.fuelPerMinute(), 1e-6);
        assertEquals(simulation.fuelPerSecond() * 3600.0, simulation.fuelPerHour(), 1e-6);
        assertEquals(0.0, simulation.coolantPerSecond());
        assertEquals("∞", ReactorSimulation.formatDuration(Double.POSITIVE_INFINITY));
        assertEquals("1.50 kRF".replace("50", "5"), ReactorSimulation.formatEnergy(1_500L));
    }

    @Test
    void megaReactorExceeding100MillionRfPerTickSimulatesCleanly() {
        var layout = new ReactorLayout();
        layout.resize(80, 80, 128);
        layout.setHeight(80, 128);
        var quadRod = part("oritech:reactor_quad_rod", ReactorParts.Kind.ROD, 4, 4);
        var vent = part("oritech:reactor_vent", ReactorParts.Kind.VENT, 0, 0);
        for (int z = 0; z < 80; z++) {
            for (int x = 0; x < 80; x++) {
                layout.set(x, z, ((x + z) & 1) == 0 ? quadRod : vent);
            }
        }
        var simulation = new ReactorSimulation(layout, ReactorConfig.DEFAULT,
                new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000), ItemStack.EMPTY);
        simulation.start();
        for (int tick = 0; tick < 5 && simulation.status() == ReactorSimulation.Status.RUNNING; tick++) {
            simulation.tick();
        }
        assertTrue(simulation.generation() > 100_000_000L,
                "Expected >100M RF/t mega-reactor output, got " + simulation.generation());
    }
}
