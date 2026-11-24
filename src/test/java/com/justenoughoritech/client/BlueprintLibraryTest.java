package com.justenoughoritech.client;

import com.justenoughoritech.printer.PrinterLayout;
import com.justenoughoritech.sim.ReactorConfig;
import com.justenoughoritech.sim.ReactorInputs;
import com.justenoughoritech.sim.ReactorParts;
import com.justenoughoritech.sim.ReactorSimulation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueprintLibraryTest {
    private static final List<ReactorParts.Part> MOCK_PARTS = List.of(
            ReactorParts.Part.EMPTY,
            new ReactorParts.Part("oritech:reactor_rod", "Rod", null, ReactorParts.Kind.ROD, 1, 1),
            new ReactorParts.Part("oritech:reactor_double_rod", "Double Rod", null, ReactorParts.Kind.ROD, 2, 4),
            new ReactorParts.Part("oritech:reactor_quad_rod", "Quad Rod", null, ReactorParts.Kind.ROD, 4, 12),
            new ReactorParts.Part("oritech:reactor_heat_vent", "Vent", null, ReactorParts.Kind.VENT, 0, 0),
            new ReactorParts.Part("oritech:reactor_heat_pipe", "Pipe", null, ReactorParts.Kind.PIPE, 0, 0),
            new ReactorParts.Part("oritech:reactor_absorber_block", "Absorber", null, ReactorParts.Kind.ABSORBER, 0, 0),
            new ReactorParts.Part("oritech:reactor_neutron_reflector", "Reflector", null, ReactorParts.Kind.REFLECTOR, 0, 0)
    );

    @Test
    void builtinPresetsAreValidForFtbMaxSize14AndStandard64() {
        var std = BlueprintLibrary.presets(64, MOCK_PARTS);
        assertEquals(4, std.size());
        for (var entry : std) {
            assertTrue(entry.builtin());
            assertTrue(entry.layout().width() <= 14);
        }
        var ftb = BlueprintLibrary.presets(14, MOCK_PARTS);
        assertEquals(4, ftb.size());
    }

    @Test
    void allBuiltinPresetsReachSteadyWithoutOverheatingOnDefaultAndFtbConfigs() {
        var partMap = MOCK_PARTS.stream().collect(Collectors.toMap(ReactorParts.Part::id, Function.identity()));
        var ftbCfg = new ReactorConfig(14, 38, 16, 4, 100, 4000, 600, 75_000, false, 2400);
        for (ReactorConfig cfg : new ReactorConfig[]{ReactorConfig.DEFAULT, ftbCfg}) {
            var presets = BlueprintLibrary.presets(cfg.maxSize(), MOCK_PARTS);
            assertEquals(4, presets.size());
            for (var entry : presets) {
                var layout = PrinterLayout.CODEC.decode(
                                com.mojang.serialization.JsonOps.INSTANCE,
                                PrinterLayout.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, entry.layout()).getOrThrow())
                        .getOrThrow().getFirst();
                var resolved = new com.justenoughoritech.sim.ReactorLayout();
                resolved.resize(layout.width(), layout.length(), cfg.maxSize());
                resolved.setHeight(layout.height(), cfg.maxSize());
                resolved.setEnergyPorts(layout.ports());
                for (int z = 0; z < layout.length(); z++) {
                    for (int x = 0; x < layout.width(); x++) {
                        resolved.set(x, z, partMap.get(layout.cells().get(z * layout.width() + x)));
                    }
                }
                assertTrue(resolved.validationErrors(cfg.maxSize()).isEmpty(),
                        "Preset invalid under maxSize=" + cfg.maxSize() + ": " + entry.name());
                var sim = new ReactorSimulation(resolved, cfg,
                        new ReactorInputs.Fuel(ItemStack.EMPTY, 40_000), ItemStack.EMPTY);
                sim.start();
                for (int tick = 0; tick < 5_000 && sim.status() == ReactorSimulation.Status.RUNNING; tick++) {
                    sim.tick();
                }
                assertEquals(ReactorSimulation.Status.STEADY, sim.status(),
                        "Preset did not reach STEADY: " + entry.name() + " (maxHeatSeen=" + sim.maxHeatSeen() + ")");
                assertTrue(sim.generation() > 0, "Preset must generate RF/t: " + entry.name());
                assertTrue(sim.maxHeatSeen() <= cfg.maxHeat() * 65 / 100,
                        "Preset heat too high: " + entry.name() + " peak=" + sim.maxHeatSeen() + "/" + cfg.maxHeat());
            }
        }
    }

    @Test
    void savesLoadsRenamesDeletesAndRecoversFromCorruptJson(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("blueprints.json");
        var lib = new BlueprintLibrary(file);
        assertTrue(lib.saved().isEmpty());

        var preset = BlueprintLibrary.presets(64, MOCK_PARTS).getFirst().layout();
        assertTrue(lib.add("My Starter", preset));
        assertEquals(1, lib.saved().size());
        assertEquals("My Starter", lib.saved().getFirst().name());

        // Re-adding same case-insensitive name overwrites in place.
        assertTrue(lib.add("my starter", preset));
        assertEquals(1, lib.saved().size());

        // Reloading from disk restores saved entry.
        var reloaded = new BlueprintLibrary(file);
        assertEquals(1, reloaded.saved().size());
        assertEquals("my starter", reloaded.saved().getFirst().name());
        assertEquals(preset, reloaded.saved().getFirst().layout());

        assertTrue(reloaded.rename(0, "Renamed Reactor"));
        assertEquals("Renamed Reactor", reloaded.saved().getFirst().name());

        assertTrue(reloaded.remove(0));
        assertTrue(reloaded.saved().isEmpty());
        assertFalse(reloaded.remove(0));

        // Corrupt JSON does not crash.
        Files.writeString(file, "{not-an-array");
        reloaded.load();
        assertTrue(reloaded.saved().isEmpty());
    }
}
