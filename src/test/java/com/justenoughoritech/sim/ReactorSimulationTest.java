package com.justenoughoritech.sim;

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    }
}
