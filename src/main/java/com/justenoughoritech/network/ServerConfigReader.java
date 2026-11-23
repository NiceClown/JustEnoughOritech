package com.justenoughoritech.network;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Reads Oritech runtime config, server recipes and tags without linking to Oritech's Java API. */
public final class ServerConfigReader {
    static final List<String> REQUIRED_BLOCKS = List.of(
            "nuclear_reactor_controller", "reactor_wall", "reactor_fuel_port",
            "reactor_coolant_absorber_port", "reactor_energy_port", "reactor_rod",
            "reactor_heat_pipe", "reactor_heat_absorber", "reactor_heat_vent", "reactor_neutron_reflector"
    );

    record ConfigValues(int maxSize, int rfPerPulse, int absorberRate, int ventBaseRate,
                        int ventRelativeRate, int maxHeat, int maxUnstableTicks,
                        int outputPerPort, boolean safeMode, int safeModeCooldown) {}

    private ServerConfigReader() {}

    public static ReactorConfigPayload read(MinecraftServer server) {
        String version = clampVersion(oritechVersion());
        try {
            if (server == null) throw new IllegalStateException("Server is null");
            Class<?> config = Class.forName("rearth.oritech.config.OritechConfig");
            ConfigValues values = readConfig(config);
            var fuels = discoverFuels(server.getRecipeManager());
            var coolants = discoverCoolants();
            return buildPayload(version, values, fuels, coolants, hasRequiredBlocks(), PhysicsCompatibility.matchesExp12());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return new ReactorConfigPayload(version, false, 0, 0, 0, 0, 0, 0, 0, 0, false, 0,
                    List.of(), List.of(), "gui.just_enough_oritech.missing_data");
        }
    }

    /**
     * Oritech's {@code maxSize} bounds the outer multiblock ({@code expandWall} loops {@code 1 <= i < MAX_SIZE},
     * yielding at most {@code MAX_SIZE} outer blocks per axis), so the interior core fits at most {@code maxSize - 2}.
     */
    static int maxInteriorSize(int rawMaxSize) {
        return rawMaxSize >= 3 ? rawMaxSize - 2 : 0;
    }

    static ConfigValues readConfig(Class<?> config) throws ReflectiveOperationException {
        int maxSize = maxInteriorSize(integer(config, "maxSize"));
        int rfPerPulse = integer(config, "rfPerPulse");
        int absorberRate = integer(config, "absorberRate");
        int ventBaseRate = integer(config, "ventBaseRate");
        int ventRelativeRate = integer(config, "ventRelativeRate");
        int maxHeat = integer(config, "maxHeat");
        int maxUnstableTicks = integer(config, "maxUnstableTicks");
        int output = integer(config, "reactorMaxEnergyOutput");
        boolean safeMode = bool(config, "safeMode");
        int safeModeCooldown = integer(config, "safeModeCooldown");
        return new ConfigValues(maxSize, rfPerPulse, absorberRate, ventBaseRate, ventRelativeRate,
                maxHeat, maxUnstableTicks, output, safeMode, safeModeCooldown);
    }

    static String validate(ConfigValues values, List<ReactorConfigPayload.FuelOption> fuels,
                           List<String> coolants, boolean hasBlocks, boolean physicsMatches) {
        if (values.maxSize() <= 0 || values.rfPerPulse() <= 0 || values.absorberRate() <= 0
                || values.ventBaseRate() < 0 || values.ventRelativeRate() <= 0
                || values.maxHeat() <= 0 || values.maxUnstableTicks() < 0
                || values.outputPerPort() <= 0 || values.safeModeCooldown() < 0)
            return "gui.just_enough_oritech.config_invalid";
        if (fuels.isEmpty()) return "gui.just_enough_oritech.no_fuel";
        if (coolants.isEmpty()) return "gui.just_enough_oritech.no_coolant";
        if (!hasBlocks) return "gui.just_enough_oritech.missing_blocks";
        if (!physicsMatches) return "gui.just_enough_oritech.physics_changed";
        return null;
    }

    static ReactorConfigPayload buildPayload(String version, ConfigValues values,
                                             List<ReactorConfigPayload.FuelOption> fuels,
                                             List<String> coolants,
                                             boolean hasBlocks, boolean physicsMatches) {
        String safeVersion = clampVersion(version);
        List<ReactorConfigPayload.FuelOption> cappedFuels = capResources(fuels);
        List<String> cappedCoolants = capResources(coolants);
        String issue = validate(values, cappedFuels, cappedCoolants, hasBlocks, physicsMatches);
        return new ReactorConfigPayload(safeVersion, issue == null, values.maxSize(), values.rfPerPulse(),
                values.absorberRate(), values.ventBaseRate(), values.ventRelativeRate(), values.maxHeat(),
                values.maxUnstableTicks(), values.outputPerPort(), values.safeMode(),
                values.safeModeCooldown(), cappedFuels, cappedCoolants, issue);
    }

    static List<ReactorConfigPayload.FuelOption> extractFuels(Collection<?> recipes, Object reactorType,
                                                              Function<Object, List<String>> ingredientItemIds)
            throws ReflectiveOperationException {
        var fuels = new TreeMap<String, Integer>();
        for (Object recipe : recipes) {
            if (recipe == null) continue;
            Method getTypeMethod = recipe.getClass().getMethod("getType");
            if (!Objects.equals(getTypeMethod.invoke(recipe), reactorType)) continue;
            Method inputsMethod = recipe.getClass().getMethod("itemInputs");
            Method durationMethod = recipe.getClass().getMethod("time");
            Method fluidMethod = recipe.getClass().getMethod("fluidInput");
            Object rawInputs = inputsMethod.invoke(recipe);
            Object rawFluid = fluidMethod.invoke(recipe);
            Object rawDuration = durationMethod.invoke(recipe);
            if (!(rawDuration instanceof Number number))
                throw new IllegalArgumentException("Expected numeric reactor recipe duration");
            int duration = number.intValue();
            if (!(rawInputs instanceof List<?> inputs) || inputs.size() != 1 || duration <= 0) continue;
            if (rawFluid instanceof Optional<?> optional) {
                if (optional.isPresent()) continue;
            } else if (rawFluid != null) {
                continue;
            }
            List<String> ids = ingredientItemIds.apply(inputs.getFirst());
            if (ids == null) continue;
            for (String id : ids) {
                if (id == null || id.isBlank() || id.equals("minecraft:air")) continue;
                fuels.putIfAbsent(id, duration);
            }
        }
        var result = new ArrayList<ReactorConfigPayload.FuelOption>(fuels.size());
        fuels.forEach((id, duration) -> result.add(new ReactorConfigPayload.FuelOption(id, duration)));
        return List.copyOf(result);
    }

    static Optional<RecipeType<?>> readReactorRecipeType(Class<?> recipeContentClass)
            throws ReflectiveOperationException {
        Object supplier = recipeContentClass.getField("REACTOR").get(null);
        if (!(supplier instanceof Supplier<?> s))
            throw new IllegalStateException("Expected Supplier for RecipeContent.REACTOR");
        Object rawType = s.get();
        return rawType instanceof RecipeType<?> type ? Optional.of(type) : Optional.empty();
    }

    private static List<ReactorConfigPayload.FuelOption> discoverFuels(RecipeManager manager)
            throws ReflectiveOperationException {
        Class<?> content = Class.forName("rearth.oritech.init.recipes.RecipeContent");
        Optional<RecipeType<?>> reactorType = readReactorRecipeType(content);
        if (reactorType.isEmpty()) return List.of();
        var sortedHolders = new ArrayList<RecipeHolder<?>>(manager.getRecipes());
        sortedHolders.sort(Comparator.comparing(holder -> holder.id().toString()));
        var recipes = new ArrayList<Object>(sortedHolders.size());
        for (RecipeHolder<?> holder : sortedHolders) recipes.add(holder.value());
        return extractFuels(recipes, reactorType.get(), rawIngredient -> {
            if (!(rawIngredient instanceof Ingredient ingredient)) return List.of();
            var ids = new ArrayList<String>();
            for (Holder<Item> item : ingredient.items().toList()) {
                if (item == null || item.value() == Items.AIR) continue;
                Identifier key = BuiltInRegistries.ITEM.getKey(item.value());
                if (key != null && !key.equals(BuiltInRegistries.ITEM.getDefaultKey())) ids.add(key.toString());
            }
            return ids;
        });
    }

    static <T> List<String> discoverCoolants(Class<?> tagContentClass, Iterable<T> items,
                                             Predicate<T> isAir, BiPredicate<T, TagKey<?>> matchesTag,
                                             Function<T, String> keyLookup) throws ReflectiveOperationException {
        Object rawTag = tagContentClass.getField("REACTOR_COOLANT").get(null);
        if (!(rawTag instanceof TagKey<?> tag)) return List.of();
        var coolants = new TreeSet<String>();
        for (T item : items) {
            if (item == null || isAir.test(item)) continue;
            if (matchesTag.test(item, tag)) {
                String key = keyLookup.apply(item);
                if (key != null && !key.isBlank()) coolants.add(key);
            }
        }
        return List.copyOf(coolants);
    }

    @SuppressWarnings("unchecked")
    private static List<String> discoverCoolants() throws ReflectiveOperationException {
        Class<?> content = Class.forName("rearth.oritech.init.TagContent");
        return discoverCoolants(
                content,
                BuiltInRegistries.ITEM,
                item -> item == Items.AIR,
                (item, tag) -> new ItemStack(item).is((TagKey<Item>) tag),
                item -> {
                    Identifier key = BuiltInRegistries.ITEM.getKey(item);
                    return key == null ? null : key.toString();
                }
        );
    }

    static boolean hasRequiredBlocks(Predicate<Identifier> containsBlock) {
        for (String id : REQUIRED_BLOCKS) {
            if (!containsBlock.test(Identifier.fromNamespaceAndPath("oritech", id))) return false;
        }
        return true;
    }

    private static boolean hasRequiredBlocks() {
        return hasRequiredBlocks(BuiltInRegistries.BLOCK::containsKey);
    }

    private static String oritechVersion() {
        try {
            ModList modList = ModList.get();
            if (modList == null) return "unknown";
            return modList.getModContainerById("oritech")
                    .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
        } catch (RuntimeException | LinkageError ignored) {
            return "unknown";
        }
    }

    static String clampVersion(String version) {
        if (version == null || version.isBlank()) return "unknown";
        return version.length() <= ReactorConfigPayload.MAX_VERSION_LENGTH
                ? version : version.substring(0, ReactorConfigPayload.MAX_VERSION_LENGTH);
    }

    private static <T> List<T> capResources(List<T> items) {
        if (items == null || items.isEmpty()) return List.of();
        return items.size() <= ReactorConfigPayload.MAX_RESOURCES
                ? List.copyOf(items) : List.copyOf(items.subList(0, ReactorConfigPayload.MAX_RESOURCES));
    }

    private static Object value(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getField(name);
        Object holder = field.get(null);
        if (holder == null) throw new IllegalStateException("Null config holder: " + name);
        if (holder instanceof Supplier<?> supplier) return supplier.get();
        return holder.getClass().getMethod("get").invoke(holder);
    }

    private static int integer(Class<?> type, String name) throws ReflectiveOperationException {
        Object raw = value(type, name);
        if (raw instanceof Integer i) return i;
        if (raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            long v = ((Number) raw).longValue();
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Out-of-range integer config value for " + name + ": " + v);
            return (int) v;
        }
        throw new IllegalArgumentException("Expected integer config value for " + name + ", got "
                + (raw == null ? "null" : raw.getClass().getName()));
    }

    private static boolean bool(Class<?> type, String name) throws ReflectiveOperationException {
        Object raw = value(type, name);
        if (raw instanceof Boolean b) return b;
        throw new IllegalArgumentException("Expected boolean config value for " + name + ", got "
                + (raw == null ? "null" : raw.getClass().getName()));
    }
}
