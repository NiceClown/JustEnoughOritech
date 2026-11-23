package com.justenoughoritech.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.justenoughoritech.client.ClientRuntimeConfig;
import com.justenoughoritech.network.PhysicsCompatibility;
import com.justenoughoritech.network.ReactorConfigPayload;
import com.justenoughoritech.sim.ReactorConfig;
import com.justenoughoritech.sim.ReactorInputs;
import com.justenoughoritech.sim.ReactorLayout;
import com.justenoughoritech.sim.ReactorParts;
import com.justenoughoritech.sim.ReactorSimulation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-version Oritech compatibility tests covering real JAR bytecode/data inspection (exp12, exp14,
 * legacy 1.2.10/1.2.11/1.2.13, oritechthings, applied_oritech, ATM11 & FTB Evolution 2 configs) plus
 * self-contained cross-version physics, config, and version-status verification.
 */
class OritechMultiVersionCompatTest {
    private static final Path PRISM_INSTANCES = resolvePrismInstancesDir();
    private static final Path ATM11_DIR = PRISM_INSTANCES.resolve(Path.of("All the Mods 11 - ATM11(1)", "minecraft"));
    private static final Path FTB_EVO2_DIR = PRISM_INSTANCES.resolve(Path.of("FTB Evolution 2", "minecraft"));
    private static final Path EXP12_JAR = ATM11_DIR.resolve(Path.of("mods", "oritech-2.0.0-exp12.jar"));
    private static final Path EXP14_JAR = FTB_EVO2_DIR.resolve(Path.of("mods", "oritech-2.0.0-exp14.jar"));
    private static final Path ORITECH_THINGS_JAR = FTB_EVO2_DIR.resolve(Path.of("mods", "oritechthings-0.1.9.jar"));
    private static final Path APPLIED_ORITECH_JAR = FTB_EVO2_DIR.resolve(Path.of("mods", "applied_oritech-1.0.7fix-26.1.2.jar"));
    private static final Path LEGACY_1_2_10_JAR = PRISM_INSTANCES.resolve(
            Path.of("All the Mods 10 Aeronautics ATM10A", "minecraft", "mods", "oritech-neoforge-1.21.1-1.2.10.jar"));
    private static final Path LEGACY_1_2_11_JAR = PRISM_INSTANCES.resolve(
            Path.of("All the Mods 10 - ATM10", "minecraft", "mods", "oritech-neoforge-1.21.1-1.2.11.jar"));
    private static final Path LEGACY_1_2_13_JAR = PRISM_INSTANCES.resolve(
            Path.of("atm10aero+mymode", "minecraft", "mods", "oritech-neoforge-1.21.1-1.2.13.jar"));

    private static Path resolvePrismInstancesDir() {
        String override = System.getProperty("jeo.prism.instances");
        if (override != null && !override.isBlank()) return Path.of(override);
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) return Path.of(appData, "PrismLauncher", "instances");
        return Path.of(System.getProperty("user.home", "."), "AppData", "Roaming", "PrismLauncher", "instances");
    }

    private static final List<String> WATCHED_REACTOR_CLASSES = List.of(
            "rearth/oritech/block/entity/reactor/NuclearReactorControllerBlockEntity.class",
            "rearth/oritech/block/entity/reactor/ReactorFuelPortEntity.class",
            "rearth/oritech/block/entity/reactor/ReactorCoolantAbsorberPortEntity.class",
            "rearth/oritech/block/blocks/reactor/BaseReactorBlock.class",
            "rearth/oritech/block/blocks/reactor/NuclearReactorControllerBlock.class",
            "rearth/oritech/block/blocks/reactor/ReactorRodBlock.class",
            "rearth/oritech/block/blocks/reactor/ReactorAbsorberBlock.class",
            "rearth/oritech/block/blocks/reactor/ReactorHeatPipeBlock.class",
            "rearth/oritech/block/blocks/reactor/ReactorHeatVentBlock.class",
            "rearth/oritech/block/blocks/reactor/ReactorNeutronReflectorBlock.class",
            "rearth/oritech/block/blocks/reactor/ReactorFuelPortBlock.class",
            "rearth/oritech/block/blocks/reactor/ReactorCoolantAbsorberPortBlock.class",
            "rearth/oritech/block/blocks/reactor/ReactorEnergyPortBlock.class",
            "rearth/oritech/block/blocks/reactor/ReactorWallBlock.class"
    );

    private static final List<String> REFLECTED_CONFIG_FIELDS = List.of(
            "maxSize", "rfPerPulse", "absorberRate", "ventBaseRate", "ventRelativeRate",
            "maxHeat", "maxUnstableTicks", "reactorMaxEnergyOutput", "safeMode", "safeModeCooldown"
    );

    @AfterEach
    void resetClientRuntime() {
        ClientRuntimeConfig.reset();
    }

    // --- Real JAR bytecode, schema, and data inspection tests ---

    @Test
    void exp12AndExp14JarsHaveIdenticalReactorBytecodeAndValidReflectionSchemas() throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(EXP12_JAR) && Files.isRegularFile(EXP14_JAR),
                "Real exp12 and exp14 Oritech JARs not present on this machine");

        try (ZipFile z12 = new ZipFile(EXP12_JAR.toFile());
             ZipFile z14 = new ZipFile(EXP14_JAR.toFile())) {

            assertEquals("2.0.0-exp12", readModVersion(z12));
            assertEquals("2.0.0-exp14", readModVersion(z14));

            // 1. All 14 watched reactor classes must be byte-for-byte identical between exp12 and exp14.
            for (String path : WATCHED_REACTOR_CLASSES) {
                byte[] b12 = readEntryBytes(z12, path);
                byte[] b14 = readEntryBytes(z14, path);
                assertNotNull(b12, "Missing in exp12: " + path);
                assertNotNull(b14, "Missing in exp14: " + path);
                assertArrayEquals(b12, b14, "Bytecode diverged between exp12 and exp14: " + path);
            }

            // 2. Public PhysicsCompatibility.matchesExp12() passes on BOTH real jars via isolated TCCL.
            assertTrue(verifyPublicPhysicsCompatibility(z12), "exp12 should pass PhysicsCompatibility.matchesExp12()");
            assertTrue(verifyPublicPhysicsCompatibility(z14), "exp14 should pass PhysicsCompatibility.matchesExp12()");

            // 3. Reflected config, recipe, tag, and rod-block methods exist with expected descriptors in BOTH jars.
            for (ZipFile jar : List.of(z12, z14)) {
                verifyOritech200ClassSchemas(jar);
                verifyOritech200DataAssets(jar);
            }
        }
    }

    @Test
    void legacy12xOritechJarsAreCleanlyIncompatibleWith200Schema() throws IOException {
        List<Path> legacyJars = List.of(LEGACY_1_2_10_JAR, LEGACY_1_2_11_JAR, LEGACY_1_2_13_JAR).stream()
                .filter(Files::isRegularFile).toList();
        Assumptions.assumeFalse(legacyJars.isEmpty(), "No legacy 1.2.x Oritech JARs present");

        for (Path jarPath : legacyJars) {
            try (ZipFile zip = new ZipFile(jarPath.toFile())) {
                // Legacy 1.21.1 Oritech placed OritechConfig under rearth.oritech.init and named controller ReactorControllerBlockEntity.
                assertNull(zip.getEntry("rearth/oritech/config/OritechConfig.class"), jarPath.getFileName().toString());
                assertNotNull(zip.getEntry("rearth/oritech/init/OritechConfig.class"), jarPath.getFileName().toString());
                assertNull(zip.getEntry("rearth/oritech/block/entity/reactor/NuclearReactorControllerBlockEntity.class"),
                        jarPath.getFileName().toString());
                assertNotNull(zip.getEntry("rearth/oritech/block/entity/reactor/ReactorControllerBlockEntity.class"),
                        jarPath.getFileName().toString());
                assertFalse(verifyPublicPhysicsCompatibility(zip),
                        "Legacy 1.2.x jar must NOT pass exp12 fingerprint: " + jarPath.getFileName());
            }
        }
    }

    @Test
    void ftbEvolution2OritechAddonsDoNotMixinReactorClasses() throws IOException {
        List<Path> addonJars = List.of(ORITECH_THINGS_JAR, APPLIED_ORITECH_JAR).stream()
                .filter(Files::isRegularFile).toList();
        Assumptions.assumeFalse(addonJars.isEmpty(), "Oritech addon JARs not present");

        for (Path jarPath : addonJars) {
            try (ZipFile zip = new ZipFile(jarPath.toFile())) {
                String mixinFile = jarPath.getFileName().toString().startsWith("oritechthings")
                        ? "oritechthings.mixins.json" : "applied_oritech.mixins.json";
                String json = readEntryString(zip, mixinFile);
                assertNotNull(json, "Missing mixin config in " + jarPath.getFileName());
                assertFalse(json.toLowerCase().contains("reactor"),
                        "Addon mixin unexpectedly references reactor: " + jarPath.getFileName());
            }
        }
    }

    @Test
    void realAtm11AndFtbEvolution2ConfigsProduceValidDistinctReactorProfiles() throws IOException {
        Path atmConfig = ATM11_DIR.resolve(Path.of("config", "oritech-common.toml"));
        Path ftbConfig = FTB_EVO2_DIR.resolve(Path.of("config", "oritech-common.toml"));
        Assumptions.assumeTrue(Files.isRegularFile(atmConfig) && Files.isRegularFile(ftbConfig),
                "ATM11 / FTB Evolution 2 oritech-common.toml files not present");

        assertAtm11AndFtbProfiles(Files.readString(atmConfig), Files.readString(ftbConfig));
    }

    @Test
    void inlineAtm11AndFtbEvolution2TomlProfilesParseAndEnforceInteriorLimits() {
        String atmToml = """
                [reactor]
                    safeMode = false
                    safeModeCooldown = 2400
                    maxSize = 66
                    reactorMaxEnergyStored = 50000000
                    reactorMaxEnergyOutput = 25000
                    rfPerPulse = 128
                    absorberRate = 16
                    ventBaseRate = 4
                    ventRelativeRate = 100
                    maxHeat = 2000
                    maxUnstableTicks = 600
                [arcane]
                    stabilized_enchanterCostMultiplier = 5
                """;
        String ftbToml = """
                [reactor]
                    safeMode = false
                    safeModeCooldown = 2400
                    maxSize = 16
                    reactorMaxEnergyStored = 500000000
                    reactorMaxEnergyOutput = 75000
                    rfPerPulse = 38
                    absorberRate = 16
                    ventBaseRate = 4
                    ventRelativeRate = 100
                    maxHeat = 4000
                    maxUnstableTicks = 400
                [arcane]
                    stabilized_enchanterCostMultiplier = 5
                """;
        assertAtm11AndFtbProfiles(atmToml, ftbToml);
    }

    private static void assertAtm11AndFtbProfiles(String atmToml, String ftbToml) {
        Map<String, String> atm = parseReactorSection(atmToml);
        Map<String, String> ftb = parseReactorSection(ftbToml);

        // ATM11 keeps Oritech defaults (outer 66 -> interior 64)
        assertEquals(66, Integer.parseInt(atm.get("maxSize")));
        assertEquals(128, Integer.parseInt(atm.get("rfPerPulse")));
        assertEquals(2000, Integer.parseInt(atm.get("maxHeat")));
        assertEquals(25_000, Integer.parseInt(atm.get("reactorMaxEnergyOutput")));
        assertEquals(600, Integer.parseInt(atm.get("maxUnstableTicks")));

        // FTB Evolution 2 overrides reactor balance heavily (outer 16 -> interior 14!)
        assertEquals(16, Integer.parseInt(ftb.get("maxSize")));
        assertEquals(38, Integer.parseInt(ftb.get("rfPerPulse")));
        assertEquals(4000, Integer.parseInt(ftb.get("maxHeat")));
        assertEquals(75_000, Integer.parseInt(ftb.get("reactorMaxEnergyOutput")));
        assertEquals(400, Integer.parseInt(ftb.get("maxUnstableTicks")));

        // Confirm outer-to-interior conversion matches Oritech expandWall (outer = interior + 2 <= maxSize)
        int ftbInteriorLimit = Integer.parseInt(ftb.get("maxSize")) - 2;
        assertEquals(14, ftbInteriorLimit);
        var layoutAtLimit = singleRodLayout(ftbInteriorLimit, ftbInteriorLimit, ftbInteriorLimit);
        assertTrue(layoutAtLimit.validationErrors(ftbInteriorLimit).isEmpty());
        assertEquals(16, layoutAtLimit.width() + 2);

        var oversizedLayout = singleRodLayout(15, 15, 15);
        assertFalse(oversizedLayout.validationErrors(ftbInteriorLimit).isEmpty(),
                "15x15x15 interior (17x17x17 outer) exceeds FTB Evolution 2 maxSize=16 and must be rejected");
    }

    // --- Self-contained multi-version physics & compatibility matrix tests (always run) ---

    @Test
    void clientRuntimeConfigVersionMatrixHandlesExp12Exp14FutureAndLegacyVersions() {
        var fuel = List.of(new ReactorConfigPayload.FuelOption("oritech:uranium_pellet", 4000));
        var coolant = List.of("minecraft:ice");

        // Verified exp12 (ATM11)
        ClientRuntimeConfig.accept(new ReactorConfigPayload("2.0.0-exp12", true,
                64, 128, 16, 4, 100, 2000, 600, 25_000, false, 2400, fuel, coolant, ""));
        assertEquals(ClientRuntimeConfig.Compatibility.VERIFIED, ClientRuntimeConfig.compatibility());
        assertEquals(64, ClientRuntimeConfig.config().maxSize());
        assertEquals(128, ClientRuntimeConfig.config().rfPerPulse());

        // Verified exp14 (FTB Evolution 2 overrides)
        ClientRuntimeConfig.accept(new ReactorConfigPayload("2.0.0-exp14", true,
                14, 38, 16, 4, 100, 4000, 400, 75_000, false, 2400, fuel, coolant, ""));
        assertEquals(ClientRuntimeConfig.Compatibility.VERIFIED, ClientRuntimeConfig.compatibility());
        assertEquals(14, ClientRuntimeConfig.config().maxSize());
        assertEquals(38, ClientRuntimeConfig.config().rfPerPulse());
        assertEquals(4000, ClientRuntimeConfig.config().maxHeat());
        assertEquals(75_000, ClientRuntimeConfig.config().maxEnergyOutputPerPort());

        // Future compatible Oritech build (same physics fingerprint, newer version string -> UNVERIFIED)
        ClientRuntimeConfig.accept(new ReactorConfigPayload("2.0.0-exp15", true,
                64, 128, 16, 4, 100, 2000, 600, 25_000, false, 2400, fuel, coolant, ""));
        assertEquals(ClientRuntimeConfig.Compatibility.UNVERIFIED, ClientRuntimeConfig.compatibility());

        // Future incompatible Oritech build (physics fingerprint changed -> INCOMPATIBLE)
        ClientRuntimeConfig.accept(new ReactorConfigPayload("2.0.0-exp15", false,
                64, 128, 16, 4, 100, 2000, 600, 25_000, false, 2400, fuel, coolant,
                "gui.just_enough_oritech.physics_changed"));
        assertEquals(ClientRuntimeConfig.Compatibility.INCOMPATIBLE, ClientRuntimeConfig.compatibility());
        assertEquals("gui.just_enough_oritech.physics_changed", ClientRuntimeConfig.runtimeIssue());

        // Legacy 1.2.13 (missing 2.0.0 config class -> INCOMPATIBLE with missing_data)
        ClientRuntimeConfig.accept(new ReactorConfigPayload("1.2.13", false,
                0, 0, 0, 0, 0, 0, 0, 0, false, 0, List.of(), List.of(), "gui.just_enough_oritech.missing_data"));
        assertEquals(ClientRuntimeConfig.Compatibility.INCOMPATIBLE, ClientRuntimeConfig.compatibility());
        assertEquals("gui.just_enough_oritech.missing_data", ClientRuntimeConfig.runtimeIssue());
    }

    @Test
    void simulationMatchesOritechBytecodePhysicsAcrossAtm11FtbEvo2AndSafeModeConfigs() {
        var singleRod = part("oritech:reactor_rod", ReactorParts.Kind.ROD, 1, 1);
        var doubleRod = part("oritech:reactor_double_rod", ReactorParts.Kind.ROD, 2, 4);
        var quadRod = part("oritech:reactor_quad_rod", ReactorParts.Kind.ROD, 4, 12);
        var reflector = part("oritech:reactor_neutron_reflector", ReactorParts.Kind.REFLECTOR, 0, 0);
        var pipe = part("oritech:reactor_heat_pipe", ReactorParts.Kind.PIPE, 0, 0);
        var vent = part("oritech:reactor_heat_vent", ReactorParts.Kind.VENT, 0, 0);
        var absorber = part("oritech:reactor_heat_absorber", ReactorParts.Kind.ABSORBER, 0, 0);

        // 1. Verify exact pulse & generation formulas from NuclearReactorControllerBlockEntity.rebuildSimulation & serverTick:
        // quadRod (4 rods, 12 internal) next to doubleRod (2 rods, 4 internal) and reflector (+4 for quad):
        //   quad pulses = 12 + 2 (double) + 4 (reflector) = 18
        //   double pulses = 4 + 4 (quad) = 8
        var pulseLayout = new ReactorLayout();
        pulseLayout.resize(3, 1, 64);
        pulseLayout.setHeight(2, 64);
        pulseLayout.set(0, 0, reflector);
        pulseLayout.set(1, 0, quadRod);
        pulseLayout.set(2, 0, doubleRod);

        var atmConfig = new ReactorConfig(64, 128, 16, 4, 100, 2000, 600, 25_000, false, 2400);
        var ftbConfig = new ReactorConfig(14, 38, 16, 4, 100, 4000, 400, 75_000, false, 2400);

        var atmSim = new ReactorSimulation(pulseLayout, atmConfig, new ReactorInputs.Fuel(ItemStack.EMPTY, 4000), ItemStack.EMPTY);
        assertEquals(18, atmSim.cell(1, 0).pulses);
        assertEquals(8, atmSim.cell(2, 0).pulses);
        atmSim.start();
        atmSim.tick();
        // Generation = rfPerPulse * (18 + 8) * height(2) = 128 * 26 * 2 = 6656 RF/t
        assertEquals(6656L, atmSim.generation());
        // Heat tick 1: quad = (18/2)*18 + 4 = 166, double = (8/2)*8 + 4 = 36
        assertEquals(166, atmSim.cell(1, 0).heat);
        assertEquals(36, atmSim.cell(2, 0).heat);

        var ftbSim = new ReactorSimulation(pulseLayout, ftbConfig, new ReactorInputs.Fuel(ItemStack.EMPTY, 4000), ItemStack.EMPTY);
        ftbSim.start();
        ftbSim.tick();
        // FTB Evolution 2 rfPerPulse=38 -> 38 * 26 * 2 = 1976 RF/t, same heat
        assertEquals(1976L, ftbSim.generation());
        assertEquals(166, ftbSim.cell(1, 0).heat);

        // 2. Verify meltdown vs safeMode timing: unstableTicks > maxUnstableTicks (triggers on tick maxUnstableTicks + 1 over maxHeat).
        var hotLayout = new ReactorLayout();
        hotLayout.resize(1, 1, 14);
        hotLayout.setHeight(1, 14);
        hotLayout.set(0, 0, quadRod); // +76 heat/tick: exceeds maxHeat=100 on tick 2 (heat=152)

        var meltdownCfg = new ReactorConfig(14, 38, 16, 4, 100, 100, 3, 75_000, false, 2400);
        var meltdownSim = new ReactorSimulation(hotLayout, meltdownCfg, new ReactorInputs.Fuel(ItemStack.EMPTY, 4000), ItemStack.EMPTY);
        meltdownSim.start();
        meltdownSim.tick(); // heat 76 (<=100), unstable=0
        assertEquals(0, meltdownSim.unstableTicks());
        for (int i = 1; i <= 3; i++) {
            meltdownSim.tick(); // unstable = 1, 2, 3
            assertEquals(ReactorSimulation.Status.RUNNING, meltdownSim.status());
            assertEquals(i, meltdownSim.unstableTicks());
        }
        meltdownSim.tick(); // unstable = 4 > 3 -> MELTDOWN
        assertEquals(ReactorSimulation.Status.MELTDOWN, meltdownSim.status());

        var safeCfg = new ReactorConfig(14, 38, 16, 4, 100, 100, 3, 75_000, true, 1200);
        var safeSim = new ReactorSimulation(hotLayout, safeCfg, new ReactorInputs.Fuel(ItemStack.EMPTY, 4000), ItemStack.EMPTY);
        safeSim.start();
        for (int i = 0; i < 5; i++) safeSim.tick();
        assertEquals(ReactorSimulation.Status.SAFE_COOLDOWN, safeSim.status());

        // 3. Verify absorber negative-heat & coolant capacity (1000) behavior matches Oritech bytecode:
        // singleRod (+4 heat/tick) next to absorber (absorberRate=16): neighbor heat drops below 0 (4 - 16 = -12)
        // when absorber runs after rod, and height=3 (1000 % 3 != 0) reaches steady periodic state with positive coolant usage.
        var absorberLayout = new ReactorLayout();
        absorberLayout.resize(2, 1, 64);
        absorberLayout.setHeight(3, 64);
        absorberLayout.set(0, 0, singleRod);
        absorberLayout.set(1, 0, absorber);
        var absSim = new ReactorSimulation(absorberLayout, atmConfig, new ReactorInputs.Fuel(ItemStack.EMPTY, 4000), ItemStack.EMPTY);
        absSim.start();
        for (int t = 0; t < 5000 && absSim.status() == ReactorSimulation.Status.RUNNING; t++) absSim.tick();
        assertEquals(ReactorSimulation.Status.STEADY, absSim.status());
        assertTrue(absSim.steadyCoolantPerSecond() > 0.0);
        assertTrue(absSim.coolantItems() >= 2, "Should cross at least one 1000-unit coolant refill cycle");

        // 4. Verify pipe + vent passive cooling reaches STEADY cleanly under FTB Evo 2 config
        var passiveLayout = new ReactorLayout();
        passiveLayout.resize(3, 1, 14);
        passiveLayout.setHeight(2, 14);
        passiveLayout.set(0, 0, singleRod);
        passiveLayout.set(1, 0, pipe);
        passiveLayout.set(2, 0, vent);
        var passiveSim = new ReactorSimulation(passiveLayout, ftbConfig, new ReactorInputs.Fuel(ItemStack.EMPTY, 4000), ItemStack.EMPTY);
        passiveSim.start();
        for (int t = 0; t < 500 && passiveSim.status() == ReactorSimulation.Status.RUNNING; t++) passiveSim.tick();
        assertEquals(ReactorSimulation.Status.STEADY, passiveSim.status());
        assertEquals(0.0, passiveSim.steadyCoolantPerSecond());
    }

    @Test
    void oritechExpandWallMultiblockFormationMatchesMaxInteriorLimitAcrossAllRotations() {
        var rod = part("oritech:reactor_rod", ReactorParts.Kind.ROD, 1, 1);
        var absorber = part("oritech:reactor_heat_absorber", ReactorParts.Kind.ABSORBER, 0, 0);

        // FTB Evolution 2 (Oritech maxSize=16 -> maxInterior=14): 14x14x14 forms for ALL 4 rotations, 15x15x15 fails!
        var ftbMaxLayout = new ReactorLayout();
        ftbMaxLayout.resize(14, 14, 14);
        ftbMaxLayout.setHeight(14, 14);
        ftbMaxLayout.set(0, 0, rod);
        ftbMaxLayout.set(1, 0, absorber);
        for (Rotation rot : Rotation.values()) {
            assertTrue(oritechControllerInitForms(ftbMaxLayout.placements(rot), 16),
                    "14x14x14 interior (16x16x16 outer) must form under FTB Evo 2 maxSize=16 for rotation " + rot);
        }

        var ftbOversized = new ReactorLayout();
        ftbOversized.resize(15, 15, 15);
        ftbOversized.setHeight(15, 15);
        ftbOversized.set(0, 0, rod);
        for (Rotation rot : Rotation.values()) {
            assertFalse(oritechControllerInitForms(ftbOversized.placements(rot), 16),
                    "15x15x15 interior (17x17x17 outer) must FAIL Oritech expandWall under maxSize=16 for rotation " + rot);
        }

        // Minimum valid reactor: maxSize=3 -> maxInterior=1 (3x3x3 outer) forms for ALL 4 rotations, fails under maxSize=2.
        var tiny = singleRodLayout(1, 1, 1);
        for (Rotation rot : Rotation.values()) {
            assertTrue(oritechControllerInitForms(tiny.placements(rot), 3),
                    "1x1x1 interior (3x3x3 outer) must form under maxSize=3 for rotation " + rot);
            assertFalse(oritechControllerInitForms(tiny.placements(rot), 2),
                    "1x1x1 interior cannot form under maxSize=2 for rotation " + rot);
        }
    }

    /**
     * Exact Java replica of Oritech 2.0.0 {@code NuclearReactorControllerBlockEntity.init} and {@code expandWall}
     * verified from {@code javap -c -p} bytecode (identical in exp12 and exp14).
     */
    private static boolean oritechControllerInitForms(List<ReactorLayout.Placement> placements, int oritechMaxSize) {
        var world = new HashMap<BlockPos, String>(placements.size());
        BlockPos controllerPos = null;
        for (var p : placements) {
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            world.put(pos, p.blockId());
            if (ReactorLayout.CONTROLLER.equals(p.blockId())) controllerPos = pos;
        }
        if (controllerPos == null) return false;

        // Exact expandWall sequence from NuclearReactorControllerBlockEntity.init()
        BlockPos areaMin = expandWall(world, controllerPos, new Vec3i(0, -1, 0), true, oritechMaxSize);
        areaMin = expandWall(world, areaMin, new Vec3i(0, 0, -1), false, oritechMaxSize);
        areaMin = expandWall(world, areaMin, new Vec3i(-1, 0, 0), false, oritechMaxSize);
        areaMin = expandWall(world, areaMin, new Vec3i(0, 0, -1), false, oritechMaxSize);

        BlockPos areaMax = areaMin;
        areaMax = expandWall(world, areaMax, new Vec3i(0, 1, 0), false, oritechMaxSize);
        areaMax = expandWall(world, areaMax, new Vec3i(0, 0, 1), false, oritechMaxSize);
        areaMax = expandWall(world, areaMax, new Vec3i(1, 0, 0), false, oritechMaxSize);

        if (areaMin.equals(controllerPos) || areaMax.equals(controllerPos) || areaMin.equals(areaMax)
                || areaMin.getX() == areaMax.getX() || areaMin.getY() == areaMax.getY() || areaMin.getZ() == areaMax.getZ()) {
            return false;
        }

        // Verify shell edges and walls
        for (int y = areaMin.getY(); y <= areaMax.getY(); y++) {
            for (int z = areaMin.getZ(); z <= areaMax.getZ(); z++) {
                for (int x = areaMin.getX(); x <= areaMax.getX(); x++) {
                    int boundaryAxes = (x == areaMin.getX() || x == areaMax.getX() ? 1 : 0)
                            + (y == areaMin.getY() || y == areaMax.getY() ? 1 : 0)
                            + (z == areaMin.getZ() || z == areaMax.getZ() ? 1 : 0);
                    String id = world.get(new BlockPos(x, y, z));
                    if (boundaryAxes >= 2) {
                        if (!ReactorLayout.WALL.equals(id)) return false;
                    } else if (boundaryAxes == 1) {
                        if (id != null && !Set.of(ReactorLayout.WALL, ReactorLayout.CONTROLLER,
                                ReactorLayout.ENERGY_PORT, ReactorLayout.FUEL_PORT,
                                ReactorLayout.COOLANT_PORT, "oritech:reactor_heat_vent",
                                "oritech:reactor_redstone_port").contains(id)) {
                            return false;
                        }
                    }
                }
            }
        }

        // Verify uniform interior columns and required ceiling ports
        int stackHeight = areaMax.getY() - areaMin.getY() - 1;
        if (stackHeight <= 0) return false;
        for (int z = areaMin.getZ() + 1; z <= areaMax.getZ() - 1; z++) {
            for (int x = areaMin.getX() + 1; x <= areaMax.getX() - 1; x++) {
                String base = world.get(new BlockPos(x, areaMin.getY() + 1, z));
                if (base == null) continue;
                for (int dy = 1; dy < stackHeight; dy++) {
                    if (!base.equals(world.get(new BlockPos(x, areaMin.getY() + 1 + dy, z)))) return false;
                }
                String ceiling = world.get(new BlockPos(x, areaMin.getY() + 1 + stackHeight, z));
                if (base.contains("_rod") && !ReactorLayout.FUEL_PORT.equals(ceiling)) return false;
                if ("oritech:reactor_heat_absorber".equals(base) && !ReactorLayout.COOLANT_PORT.equals(ceiling)) return false;
            }
        }
        return true;
    }

    private static BlockPos expandWall(Map<BlockPos, String> world, BlockPos start, Vec3i dir,
                                       boolean allowAnyReactorBlock, int maxSize) {
        BlockPos last = start;
        for (int i = 1; i < maxSize; i++) {
            BlockPos next = start.offset(dir.multiply(i));
            String id = world.get(next);
            if (!allowAnyReactorBlock && !ReactorLayout.WALL.equals(id)) return last;
            if (allowAnyReactorBlock && id == null) return last;
            last = next;
        }
        return last;
    }

    // --- Helpers ---

    private static void verifyOritech200ClassSchemas(ZipFile zip) throws IOException {
        // OritechConfig has all 10 reflected public static fields
        ClassModel configModel = parseClass(zip, "rearth/oritech/config/OritechConfig.class");
        Set<String> configFields = configModel.fields().stream()
                .map(f -> f.fieldName().stringValue()).collect(Collectors.toSet());
        for (String field : REFLECTED_CONFIG_FIELDS) {
            assertTrue(configFields.contains(field), "OritechConfig missing field: " + field);
        }

        // RecipeContent has REACTOR
        ClassModel recipeContent = parseClass(zip, "rearth/oritech/init/recipes/RecipeContent.class");
        assertTrue(recipeContent.fields().stream().anyMatch(f -> f.fieldName().stringValue().equals("REACTOR")));

        // OritechRecipe has itemInputs(), time(), fluidInput()
        ClassModel recipeModel = parseClass(zip, "rearth/oritech/init/recipes/OritechRecipe.class");
        Set<String> recipeMethods = recipeModel.methods().stream()
                .map(m -> m.methodName().stringValue() + m.methodType().stringValue()).collect(Collectors.toSet());
        assertTrue(recipeMethods.contains("itemInputs()Ljava/util/List;"));
        assertTrue(recipeMethods.contains("time()I"));
        assertTrue(recipeMethods.contains("fluidInput()Ljava/util/Optional;"));

        // TagContent has REACTOR_COOLANT
        ClassModel tagContent = parseClass(zip, "rearth/oritech/init/TagContent.class");
        assertTrue(tagContent.fields().stream().anyMatch(f -> f.fieldName().stringValue().equals("REACTOR_COOLANT")));

        // ReactorRodBlock has getRodCount()I and getInternalPulseCount()I (used by ReactorParts)
        ClassModel rodModel = parseClass(zip, "rearth/oritech/block/blocks/reactor/ReactorRodBlock.class");
        Set<String> rodMethods = rodModel.methods().stream()
                .map(m -> m.methodName().stringValue() + m.methodType().stringValue()).collect(Collectors.toSet());
        assertTrue(rodMethods.contains("getRodCount()I"));
        assertTrue(rodMethods.contains("getInternalPulseCount()I"));

        // NuclearReactorControllerBlockEntity defines MAX_SIZE and expandWall
        ClassModel ctrlModel = parseClass(zip, "rearth/oritech/block/entity/reactor/NuclearReactorControllerBlockEntity.class");
        assertTrue(ctrlModel.fields().stream().map(FieldModel::fieldName).anyMatch(n -> n.stringValue().equals("MAX_SIZE")));
        assertTrue(ctrlModel.methods().stream().map(MethodModel::methodName).anyMatch(n -> n.stringValue().equals("expandWall")));
    }

    private static void verifyOritech200DataAssets(ZipFile zip) throws IOException {
        // Default coolant tag contains ice variants
        String coolantTagJson = readEntryString(zip, "data/oritech/tags/item/reactor_coolant_items.json");
        assertNotNull(coolantTagJson);
        JsonArray coolants = JsonParser.parseString(coolantTagJson).getAsJsonObject().getAsJsonArray("values");
        Set<String> coolantValues = coolants.asList().stream().map(JsonElement::getAsString).collect(Collectors.toSet());
        assertTrue(coolantValues.containsAll(Set.of("minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice")));

        // Steel tag exists (used by Oritech's own recipes & recommended for reactor_printer recipe)
        String steelTagJson = readEntryString(zip, "data/c/tags/item/ingots/steel.json");
        assertNotNull(steelTagJson);
        assertTrue(steelTagJson.contains("oritech:steel_ingot"));

        // All 4 default reactor fuel recipes exist with expected durations
        Map<String, Integer> expectedFuels = Map.of(
                "data/oritech/recipe/reactorgen/pellet.json", 4000,
                "data/oritech/recipe/reactorgen/smallpellet.json", 400,
                "data/oritech/recipe/reactorgen/plutoniumpellet.json", 40_000,
                "data/oritech/recipe/reactorgen/smallplutoniumpellet.json", 4000
        );
        for (var entry : expectedFuels.entrySet()) {
            String recipeJson = readEntryString(zip, entry.getKey());
            assertNotNull(recipeJson, "Missing fuel recipe: " + entry.getKey());
            JsonObject obj = JsonParser.parseString(recipeJson).getAsJsonObject();
            assertEquals("oritech:reactor", obj.get("type").getAsString());
            assertEquals(1, obj.getAsJsonArray("itemInputs").size());
            assertEquals(entry.getValue(), obj.get("time").getAsInt());
            assertFalse(obj.has("fluidInput"));
        }
    }

    private static boolean verifyPublicPhysicsCompatibility(ZipFile zip) throws IOException {
        var resources = new HashMap<String, byte[]>();
        for (String path : WATCHED_REACTOR_CLASSES) {
            byte[] data = readEntryBytes(zip, path);
            if (data != null) resources.put(path, data);
        }
        String controllerClassName = "rearth.oritech.block.entity.reactor.NuclearReactorControllerBlockEntity";
        boolean hasController = resources.containsKey(
                "rearth/oritech/block/entity/reactor/NuclearReactorControllerBlockEntity.class");
        byte[] stub = ClassFile.of().build(ClassDesc.of(controllerClassName), cb -> {});
        ClassLoader loader = new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (hasController && controllerClassName.equals(name)) {
                    return defineClass(name, stub, 0, stub.length);
                }
                throw new ClassNotFoundException(name);
            }

            @Override public InputStream getResourceAsStream(String name) {
                byte[] data = resources.get(name);
                return data == null ? null : new ByteArrayInputStream(data);
            }
        };

        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            return PhysicsCompatibility.matchesExp12();
        } finally {
            Thread.currentThread().setContextClassLoader(prev);
        }
    }

    private static ClassModel parseClass(ZipFile zip, String path) throws IOException {
        byte[] bytes = readEntryBytes(zip, path);
        assertNotNull(bytes, "Missing class in JAR: " + path);
        return ClassFile.of().parse(bytes);
    }

    private static byte[] readEntryBytes(ZipFile zip, String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static String readEntryString(ZipFile zip, String path) throws IOException {
        byte[] bytes = readEntryBytes(zip, path);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readModVersion(ZipFile zip) throws IOException {
        String toml = readEntryString(zip, "META-INF/neoforge.mods.toml");
        assertNotNull(toml);
        Matcher m = Pattern.compile("(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"").matcher(toml);
        assertTrue(m.find(), "Version not found in neoforge.mods.toml");
        return m.group(1);
    }

    private static Map<String, String> parseReactorSection(String toml) {
        var result = new HashMap<String, String>();
        boolean inReactor = false;
        for (String rawLine : toml.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                inReactor = line.equals("[reactor]");
                continue;
            }
            if (!inReactor) continue;
            int eq = line.indexOf('=');
            if (eq > 0) {
                result.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return result;
    }

    private static ReactorParts.Part part(String id, ReactorParts.Kind kind, int rods, int pulses) {
        return new ReactorParts.Part(id, id, null, kind, rods, pulses);
    }

    private static ReactorLayout singleRodLayout(int width, int length, int height) {
        var layout = new ReactorLayout();
        layout.resize(width, length, Math.max(width, length));
        layout.setHeight(height, height);
        layout.set(0, 0, part("oritech:reactor_rod", ReactorParts.Kind.ROD, 1, 1));
        return layout;
    }
}
