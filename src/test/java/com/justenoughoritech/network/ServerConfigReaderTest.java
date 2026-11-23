package com.justenoughoritech.network;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigReaderTest {

    // --- Synthetic Oritech config schema fixtures ---

    public static final class Exp12DefaultConfig {
        public static final Supplier<Integer> maxSize = () -> 66;
        public static final Supplier<Integer> rfPerPulse = () -> 128;
        public static final Supplier<Integer> absorberRate = () -> 16;
        public static final Supplier<Integer> ventBaseRate = () -> 4;
        public static final Supplier<Integer> ventRelativeRate = () -> 100;
        public static final Supplier<Integer> maxHeat = () -> 2000;
        public static final Supplier<Integer> maxUnstableTicks = () -> 600;
        public static final Supplier<Integer> reactorMaxEnergyOutput = () -> 25_000;
        public static final Supplier<Boolean> safeMode = () -> false;
        public static final Supplier<Integer> safeModeCooldown = () -> 2400;
    }

    public static final class FtbEvolution2Exp14Config {
        public static final Supplier<Integer> maxSize = () -> 16;
        public static final Supplier<Integer> rfPerPulse = () -> 38;
        public static final Supplier<Integer> absorberRate = () -> 16;
        public static final Supplier<Integer> ventBaseRate = () -> 4;
        public static final Supplier<Integer> ventRelativeRate = () -> 100;
        public static final Supplier<Integer> maxHeat = () -> 4000;
        public static final Supplier<Integer> maxUnstableTicks = () -> 400;
        public static final Supplier<Integer> reactorMaxEnergyOutput = () -> 75_000;
        public static final Supplier<Boolean> safeMode = () -> true;
        public static final Supplier<Integer> safeModeCooldown = () -> 0;
    }

    /** Simulates a non-Supplier config holder exposing only a public get() method (like custom config wrappers). */
    public static final class ReflectiveHolder<T> {
        private final T value;
        public ReflectiveHolder(T value) { this.value = value; }
        public T get() { return value; }
    }

    /** Private Supplier implementation to verify non-public Supplier classes do not fail reflective access. */
    private static final class PrivateSupplier<T> implements Supplier<T> {
        private final T value;
        PrivateSupplier(T value) { this.value = value; }
        @Override public T get() { return value; }
    }

    public static final class MixedHolderSchemaConfig {
        public static final ReflectiveHolder<Long> maxSize = new ReflectiveHolder<>(34L);
        public static final PrivateSupplier<Short> rfPerPulse = new PrivateSupplier<>((short) 64);
        public static final PrivateSupplier<Byte> absorberRate = new PrivateSupplier<>((byte) 12);
        public static final ReflectiveHolder<Integer> ventBaseRate = new ReflectiveHolder<>(0);
        public static final ReflectiveHolder<Integer> ventRelativeRate = new ReflectiveHolder<>(50);
        public static final ReflectiveHolder<Integer> maxHeat = new ReflectiveHolder<>(3000);
        public static final ReflectiveHolder<Integer> maxUnstableTicks = new ReflectiveHolder<>(0);
        public static final ReflectiveHolder<Long> reactorMaxEnergyOutput = new ReflectiveHolder<>(100_000L);
        public static final ReflectiveHolder<Boolean> safeMode = new ReflectiveHolder<>(true);
        public static final PrivateSupplier<Integer> safeModeCooldown = new PrivateSupplier<>(1200);
    }

    public static final class MissingFieldConfig {
        public static final Supplier<Integer> maxSize = () -> 66;
        // Intentionally missing rfPerPulse and remaining reactor fields
    }

    public static final class NullHolderConfig {
        public static final Supplier<Integer> maxSize = null;
    }

    public static final class NullValueConfig {
        public static final Supplier<Integer> maxSize = () -> null;
    }

    public static final class UnloadedNeoForgeConfig {
        public static final Supplier<Integer> maxSize = () -> {
            throw new IllegalStateException("Cannot get config value before config is loaded.");
        };
    }

    public static final class OverflowLongConfig {
        public static final Supplier<Long> maxSize = () -> (long) Integer.MAX_VALUE + 10L;
    }

    public static final class UnderflowLongConfig {
        public static final Supplier<Long> maxSize = () -> (long) Integer.MIN_VALUE - 1L;
    }

    public static final class FloatingPointConfig {
        public static final Supplier<Double> maxSize = () -> 66.5d;
    }

    public static final class WrongBoolTypeConfig {
        public static final Supplier<Integer> maxSize = () -> 66;
        public static final Supplier<Integer> rfPerPulse = () -> 128;
        public static final Supplier<Integer> absorberRate = () -> 16;
        public static final Supplier<Integer> ventBaseRate = () -> 4;
        public static final Supplier<Integer> ventRelativeRate = () -> 100;
        public static final Supplier<Integer> maxHeat = () -> 2000;
        public static final Supplier<Integer> maxUnstableTicks = () -> 600;
        public static final Supplier<Integer> reactorMaxEnergyOutput = () -> 25_000;
        public static final Supplier<String> safeMode = () -> "true";
        public static final Supplier<Integer> safeModeCooldown = () -> 2400;
    }

    // --- Synthetic recipe fixtures ---

    public record SyntheticOritechRecipe(Object type, List<?> itemInputs, int time, Object fluidInput) {
        public Object getType() { return type; }
    }

    public static final class MalformedRecipeMissingTime {
        private final Object type;
        public MalformedRecipeMissingTime(Object type) { this.type = type; }
        public Object getType() { return type; }
        public List<?> itemInputs() { return List.of("oritech:uranium_pellet"); }
        public Object fluidInput() { return Optional.empty(); }
    }

    public static final class MalformedRecipeStringDuration {
        private final Object type;
        public MalformedRecipeStringDuration(Object type) { this.type = type; }
        public Object getType() { return type; }
        public List<?> itemInputs() { return List.of("oritech:uranium_pellet"); }
        public Object time() { return "4000"; }
        public Object fluidInput() { return Optional.empty(); }
    }

    // --- Tests ---

    @Test
    void maxInteriorSizeConvertsOuterMultiblockLimitWithoutUnderflow() {
        assertEquals(64, ServerConfigReader.maxInteriorSize(66)); // ATM11 / Oritech default
        assertEquals(14, ServerConfigReader.maxInteriorSize(16)); // FTB Evolution 2
        assertEquals(1, ServerConfigReader.maxInteriorSize(3));   // Minimum valid 3x3x3 outer -> 1x1x1 core
        assertEquals(0, ServerConfigReader.maxInteriorSize(2));   // Outer 2x2x2 has zero interior
        assertEquals(0, ServerConfigReader.maxInteriorSize(1));
        assertEquals(0, ServerConfigReader.maxInteriorSize(0));
        assertEquals(0, ServerConfigReader.maxInteriorSize(-5));
        assertEquals(0, ServerConfigReader.maxInteriorSize(Integer.MIN_VALUE));
        assertEquals(Integer.MAX_VALUE - 2, ServerConfigReader.maxInteriorSize(Integer.MAX_VALUE));
    }

    @Test
    void readConfigParsesExp12DefaultAndFtbEvolution2AndMixedHolders() throws Exception {
        ServerConfigReader.ConfigValues exp12 = ServerConfigReader.readConfig(Exp12DefaultConfig.class);
        assertEquals(new ServerConfigReader.ConfigValues(64, 128, 16, 4, 100, 2000, 600, 25_000, false, 2400), exp12);

        ServerConfigReader.ConfigValues ftb = ServerConfigReader.readConfig(FtbEvolution2Exp14Config.class);
        assertEquals(new ServerConfigReader.ConfigValues(14, 38, 16, 4, 100, 4000, 400, 75_000, true, 0), ftb);

        ServerConfigReader.ConfigValues mixed = ServerConfigReader.readConfig(MixedHolderSchemaConfig.class);
        assertEquals(new ServerConfigReader.ConfigValues(32, 64, 12, 0, 50, 3000, 0, 100_000, true, 1200), mixed);
    }

    @Test
    void readConfigRejectsMalformedOrUnloadedSchemasCleanly() {
        assertThrows(NoSuchFieldException.class, () -> ServerConfigReader.readConfig(MissingFieldConfig.class));
        assertThrows(IllegalStateException.class, () -> ServerConfigReader.readConfig(NullHolderConfig.class));
        assertThrows(IllegalArgumentException.class, () -> ServerConfigReader.readConfig(NullValueConfig.class));
        assertThrows(IllegalStateException.class, () -> ServerConfigReader.readConfig(UnloadedNeoForgeConfig.class));
        assertThrows(IllegalArgumentException.class, () -> ServerConfigReader.readConfig(OverflowLongConfig.class));
        assertThrows(IllegalArgumentException.class, () -> ServerConfigReader.readConfig(UnderflowLongConfig.class));
        assertThrows(IllegalArgumentException.class, () -> ServerConfigReader.readConfig(FloatingPointConfig.class));
        assertThrows(IllegalArgumentException.class, () -> ServerConfigReader.readConfig(WrongBoolTypeConfig.class));
    }

    @Test
    void validateEnforcesExactBoundariesAndIssuePriorityOrder() {
        var fuels = List.of(new ReactorConfigPayload.FuelOption("oritech:uranium_pellet", 4000));
        var coolants = List.of("minecraft:ice");

        // Minimum valid values (1 for strictly positive fields, 0 for non-negative fields)
        var minValid = new ServerConfigReader.ConfigValues(1, 1, 1, 0, 1, 1, 0, 1, false, 0);
        assertNull(ServerConfigReader.validate(minValid, fuels, coolants, true, true));

        // Each single invalid boundary must trigger config_invalid
        List<ServerConfigReader.ConfigValues> invalidConfigs = List.of(
                new ServerConfigReader.ConfigValues(0, 1, 1, 0, 1, 1, 0, 1, false, 0),
                new ServerConfigReader.ConfigValues(-1, 1, 1, 0, 1, 1, 0, 1, false, 0),
                new ServerConfigReader.ConfigValues(1, 0, 1, 0, 1, 1, 0, 1, false, 0),
                new ServerConfigReader.ConfigValues(1, 1, 0, 0, 1, 1, 0, 1, false, 0),
                new ServerConfigReader.ConfigValues(1, 1, 1, -1, 1, 1, 0, 1, false, 0),
                new ServerConfigReader.ConfigValues(1, 1, 1, 0, 0, 1, 0, 1, false, 0),
                new ServerConfigReader.ConfigValues(1, 1, 1, 0, 1, 0, 0, 1, false, 0),
                new ServerConfigReader.ConfigValues(1, 1, 1, 0, 1, 1, -1, 1, false, 0),
                new ServerConfigReader.ConfigValues(1, 1, 1, 0, 1, 1, 0, 0, false, 0),
                new ServerConfigReader.ConfigValues(1, 1, 1, 0, 1, 1, 0, 1, true, -1)
        );
        for (var invalid : invalidConfigs) {
            assertEquals("gui.just_enough_oritech.config_invalid",
                    ServerConfigReader.validate(invalid, fuels, coolants, true, true),
                    "Expected config_invalid for " + invalid);
        }

        // Strict issue priority: config_invalid > no_fuel > no_coolant > missing_blocks > physics_changed
        assertEquals("gui.just_enough_oritech.config_invalid",
                ServerConfigReader.validate(invalidConfigs.getFirst(), List.of(), List.of(), false, false));
        assertEquals("gui.just_enough_oritech.no_fuel",
                ServerConfigReader.validate(minValid, List.of(), List.of(), false, false));
        assertEquals("gui.just_enough_oritech.no_coolant",
                ServerConfigReader.validate(minValid, fuels, List.of(), false, false));
        assertEquals("gui.just_enough_oritech.missing_blocks",
                ServerConfigReader.validate(minValid, fuels, coolants, false, false));
        assertEquals("gui.just_enough_oritech.physics_changed",
                ServerConfigReader.validate(minValid, fuels, coolants, true, false));
    }

    @Test
    void buildPayloadClampsLongVersionAndCapsOversizedResourceLists() {
        var values = new ServerConfigReader.ConfigValues(14, 38, 16, 4, 100, 4000, 400, 75_000, false, 2400);
        var manyFuels = new ArrayList<ReactorConfigPayload.FuelOption>();
        var manyCoolants = new ArrayList<String>();
        for (int i = 0; i < 4500; i++) {
            manyFuels.add(new ReactorConfigPayload.FuelOption("mod:fuel_" + i, 100 + i));
            manyCoolants.add("mod:coolant_" + i);
        }

        String longVersion = "2.0.0-exp15+git." + "a".repeat(80);
        ReactorConfigPayload payload = ServerConfigReader.buildPayload(
                longVersion, values, manyFuels, manyCoolants, true, true);

        assertTrue(payload.compatible());
        assertEquals("", payload.compatibilityIssue());
        assertEquals(ReactorConfigPayload.MAX_VERSION_LENGTH, payload.oritechVersion().length());
        assertTrue(longVersion.startsWith(payload.oritechVersion()));
        assertEquals(ReactorConfigPayload.MAX_RESOURCES, payload.fuels().size());
        assertEquals(ReactorConfigPayload.MAX_RESOURCES, payload.coolants().size());

        assertEquals("unknown", ServerConfigReader.clampVersion(null));
        assertEquals("unknown", ServerConfigReader.clampVersion("   "));
        assertEquals("2.0.0-exp14", ServerConfigReader.clampVersion("2.0.0-exp14"));
    }

    @Test
    void extractFuelsHandlesOritechRecipesAndFiltersInvalidOrFluidEntriesDeterministically() throws Exception {
        Object reactorType = new Object();
        Object pulverizerType = new Object();

        List<Object> recipes = List.of(
                // Valid Oritech 2.0.0 recipes in intentionally unsorted order
                new SyntheticOritechRecipe(reactorType, List.of("oritech:uranium_pellet"), 4000, Optional.empty()),
                new SyntheticOritechRecipe(reactorType, List.of("oritech:small_uranium_pellet"), 400, Optional.empty()),
                new SyntheticOritechRecipe(reactorType, List.of("oritech:plutonium_pellet"), 40_000, Optional.empty()),
                new SyntheticOritechRecipe(reactorType, List.of("oritech:small_plutonium_pellet"), 4000, null),
                // Multi-item tag ingredient (expands to 2 items + ignored air/blank)
                new SyntheticOritechRecipe(reactorType, List.of("#tag:custom_fuels"), 1200, Optional.empty()),
                // Duplicate uranium_pellet later in list must keep first duration (4000, not 9999)
                new SyntheticOritechRecipe(reactorType, List.of("oritech:uranium_pellet"), 9999, Optional.empty()),
                // Filtered cases:
                new SyntheticOritechRecipe(pulverizerType, List.of("oritech:copper_ore"), 200, Optional.empty()),
                new SyntheticOritechRecipe(reactorType, List.of(), 4000, Optional.empty()),
                new SyntheticOritechRecipe(reactorType, List.of("a:one", "a:two"), 4000, Optional.empty()),
                new SyntheticOritechRecipe(reactorType, List.of("a:zero_time"), 0, Optional.empty()),
                new SyntheticOritechRecipe(reactorType, List.of("a:neg_time"), -100, Optional.empty()),
                new SyntheticOritechRecipe(reactorType, List.of("a:fluid_optional"), 4000, Optional.of("water")),
                new SyntheticOritechRecipe(reactorType, List.of("a:fluid_legacy"), 4000, "NonEmptyFluidStack")
        );

        var fuels = ServerConfigReader.extractFuels(recipes, reactorType, raw -> {
            if ("#tag:custom_fuels".equals(raw)) {
                return List.of("mod:fuel_b", "minecraft:air", "", "mod:fuel_a");
            }
            return List.of(raw.toString());
        });

        assertEquals(List.of(
                new ReactorConfigPayload.FuelOption("mod:fuel_a", 1200),
                new ReactorConfigPayload.FuelOption("mod:fuel_b", 1200),
                new ReactorConfigPayload.FuelOption("oritech:plutonium_pellet", 40_000),
                new ReactorConfigPayload.FuelOption("oritech:small_plutonium_pellet", 4000),
                new ReactorConfigPayload.FuelOption("oritech:small_uranium_pellet", 400),
                new ReactorConfigPayload.FuelOption("oritech:uranium_pellet", 4000)
        ), fuels);
    }

    @Test
    void extractFuelsThrowsOnBrokenFutureRecipeSchema() {
        Object reactorType = new Object();
        assertThrows(NoSuchMethodException.class, () -> ServerConfigReader.extractFuels(
                List.of(new MalformedRecipeMissingTime(reactorType)), reactorType, r -> List.of(r.toString())));
        assertThrows(IllegalArgumentException.class, () -> ServerConfigReader.extractFuels(
                List.of(new MalformedRecipeStringDuration(reactorType)), reactorType, r -> List.of(r.toString())));
    }

    @Test
    void readWithNullServerReturnsMissingDataPayloadInsteadOfThrowing() {
        ReactorConfigPayload payload = ServerConfigReader.read(null);
        assertFalse(payload.compatible());
        assertEquals("unknown", payload.oritechVersion());
        assertEquals("gui.just_enough_oritech.missing_data", payload.compatibilityIssue());
        assertTrue(payload.fuels().isEmpty());
        assertTrue(payload.coolants().isEmpty());
    }

    public static final class ValidTagContent {
        public static final TagKey<Item> REACTOR_COOLANT =
                TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("oritech", "reactor_coolant_items"));
    }

    public static final class WrongTypeTagContent {
        public static final String REACTOR_COOLANT = "not_a_tag_key";
    }

    public static final class MissingFieldTagContent {}

    public static final class ValidRecipeContent {
        static final RecipeType<Recipe<?>> TYPE = new RecipeType<>() {};
        public static final Supplier<RecipeType<Recipe<?>>> REACTOR = () -> TYPE;
    }

    public static final class NonRecipeTypeRecipeContent {
        public static final Supplier<String> REACTOR = () -> "not_a_recipe_type";
    }

    public static final class NonSupplierRecipeContent {
        public static final String REACTOR = "not_a_supplier";
    }

    public static final class MissingFieldRecipeContent {}

    @Test
    void hasRequiredBlocksChecksEveryRequiredOritechBlockAndNamespace() {
        assertEquals(10, ServerConfigReader.REQUIRED_BLOCKS.size());
        Set<Identifier> all = new HashSet<>();
        for (String id : ServerConfigReader.REQUIRED_BLOCKS) {
            all.add(Identifier.fromNamespaceAndPath("oritech", id));
        }
        assertTrue(ServerConfigReader.hasRequiredBlocks(all::contains));

        // Removing any single required block must return false
        for (String id : ServerConfigReader.REQUIRED_BLOCKS) {
            Set<Identifier> missingOne = new HashSet<>(all);
            missingOne.remove(Identifier.fromNamespaceAndPath("oritech", id));
            assertFalse(ServerConfigReader.hasRequiredBlocks(missingOne::contains),
                    "Expected false when missing oritech:" + id);

            // Wrong namespace must not satisfy the check
            missingOne.add(Identifier.fromNamespaceAndPath("minecraft", id));
            assertFalse(ServerConfigReader.hasRequiredBlocks(missingOne::contains),
                    "Wrong namespace minecraft:" + id + " must not satisfy oritech:" + id);
        }
    }

    @Test
    void discoverCoolantsReturnsSortedItemsAndHandlesInvalidTagContentSchemas() throws Exception {
        List<String> items = Arrays.asList(
                "minecraft:packed_ice", null, "minecraft:air", "minecraft:stone",
                "minecraft:ice", "minecraft:blue_ice", "mod:blank_key"
        );
        Set<String> tagged = Set.of("minecraft:packed_ice", "minecraft:ice", "minecraft:blue_ice", "mod:blank_key");

        List<String> discovered = ServerConfigReader.discoverCoolants(
                ValidTagContent.class,
                items,
                "minecraft:air"::equals,
                (item, tag) -> tag.equals(ValidTagContent.REACTOR_COOLANT) && tagged.contains(item),
                item -> "mod:blank_key".equals(item) ? "   " : item
        );
        assertEquals(List.of("minecraft:blue_ice", "minecraft:ice", "minecraft:packed_ice"), discovered);

        assertEquals(List.of(), ServerConfigReader.discoverCoolants(
                WrongTypeTagContent.class, items, x -> false, (a, b) -> true, x -> x));
        assertThrows(NoSuchFieldException.class, () -> ServerConfigReader.discoverCoolants(
                MissingFieldTagContent.class, items, x -> false, (a, b) -> true, x -> x));
    }

    @Test
    void readReactorRecipeTypeValidatesSupplierAndRecipeTypeContract() throws Exception {
        Optional<RecipeType<?>> type = ServerConfigReader.readReactorRecipeType(ValidRecipeContent.class);
        assertTrue(type.isPresent());
        assertSame(ValidRecipeContent.TYPE, type.get());

        assertTrue(ServerConfigReader.readReactorRecipeType(NonRecipeTypeRecipeContent.class).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> ServerConfigReader.readReactorRecipeType(NonSupplierRecipeContent.class));
        assertThrows(NoSuchFieldException.class,
                () -> ServerConfigReader.readReactorRecipeType(MissingFieldRecipeContent.class));
    }

    @Test
    void extractFuelsHandlesNullRecipesAndNullIngredientMappingsSafely() throws Exception {
        Object reactorType = new Object();
        List<Object> recipes = Arrays.asList(
                null,
                new SyntheticOritechRecipe(reactorType, List.of("null_ids"), 4000, Optional.empty()),
                new SyntheticOritechRecipe(reactorType, List.of("oritech:uranium_pellet"), 4000, Optional.empty())
        );
        var fuels = ServerConfigReader.extractFuels(recipes, reactorType,
                raw -> "null_ids".equals(raw) ? null : List.of(raw.toString()));
        assertEquals(List.of(new ReactorConfigPayload.FuelOption("oritech:uranium_pellet", 4000)), fuels);
    }

    @Test
    void buildPayloadWithNullListsMarksNoFuelAndEmptyLists() {
        var values = new ServerConfigReader.ConfigValues(64, 128, 16, 4, 100, 2000, 600, 25_000, false, 2400);
        ReactorConfigPayload payload = ServerConfigReader.buildPayload("2.0.0-exp14", values, null, null, true, true);
        assertFalse(payload.compatible());
        assertEquals("gui.just_enough_oritech.no_fuel", payload.compatibilityIssue());
        assertTrue(payload.fuels().isEmpty());
        assertTrue(payload.coolants().isEmpty());
    }
}
