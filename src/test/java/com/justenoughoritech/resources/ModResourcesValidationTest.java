package com.justenoughoritech.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.justenoughoritech.sim.ReactorDesigner;
import com.justenoughoritech.sim.ReactorSimulation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validates mod assets, data, mixin config, manifest, and en_us/ru_ru translation integrity. */
class ModResourcesValidationTest {
    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path JAVA_SRC = Path.of("src/main/java");
    private static final Path EN_US = RESOURCES.resolve("assets/just_enough_oritech/lang/en_us.json");
    private static final Path RU_RU = RESOURCES.resolve("assets/just_enough_oritech/lang/ru_ru.json");
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[sdf]");

    private static Map<String, String> readLang(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        // Check for duplicate keys before JsonParser silently collapses them.
        var seen = new HashSet<String>();
        var keyPattern = Pattern.compile("^\\s*\"([^\"]+)\"\\s*:", Pattern.MULTILINE);
        var matcher = keyPattern.matcher(raw);
        while (matcher.find()) {
            String key = matcher.group(1);
            assertTrue(seen.add(key), "Duplicate translation key in " + file.getFileName() + ": " + key);
        }
        JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
        var map = new HashMap<String, String>();
        for (var entry : obj.entrySet()) {
            assertTrue(entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString(),
                    "Non-string lang value for " + entry.getKey());
            String value = entry.getValue().getAsString();
            assertFalse(value.isBlank(), "Blank translation value for " + entry.getKey() + " in " + file.getFileName());
            map.put(entry.getKey(), value);
        }
        return map;
    }

    @Test
    void allResourceJsonFilesAreValidJson() throws IOException {
        try (var stream = Files.walk(RESOURCES)) {
            var jsons = stream.filter(p -> p.toString().endsWith(".json")).toList();
            assertTrue(jsons.size() >= 8, "expected all mod JSON resources to exist");
            for (Path json : jsons) {
                JsonElement parsed = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8));
                assertNotNull(parsed, "Failed to parse " + json);
                assertTrue(parsed.isJsonObject(), "Expected root JSON object in " + json);
            }
        }
    }

    @Test
    void enUsAndRuRuHaveExactKeyAndPlaceholderParity() throws IOException {
        var en = readLang(EN_US);
        var ru = readLang(RU_RU);

        var onlyEn = new TreeSet<>(en.keySet());
        onlyEn.removeAll(ru.keySet());
        var onlyRu = new TreeSet<>(ru.keySet());
        onlyRu.removeAll(en.keySet());
        assertTrue(onlyEn.isEmpty(), "Keys missing from ru_ru.json: " + onlyEn);
        assertTrue(onlyRu.isEmpty(), "Keys missing from en_us.json: " + onlyRu);

        for (String key : en.keySet()) {
            int enCount = countPlaceholders(en.get(key));
            int ruCount = countPlaceholders(ru.get(key));
            assertEquals(enCount, ruCount, "Placeholder count mismatch for key " + key);
        }
    }

    @Test
    void everyTranslationKeyReferencedInJavaExistsInBothLangFiles() throws IOException {
        var en = readLang(EN_US);
        var ru = readLang(RU_RU);
        var referenced = new TreeSet<String>();

        // Explicit full keys and helper calls across Java sources.
        var fullKey = Pattern.compile("(?:gui|jei|key|block)\\.just_enough_oritech\\.[a-zA-Z0-9_.]+");
        var trCall = Pattern.compile("\\btr\\(\"([^\"]+)\"");
        var controlCall = Pattern.compile("\\b(?:control|stepControl)\\([^,]+,[^,]+,(?:[^,]+,)?\"([^\"]+)\"");
        var arrowCall = Pattern.compile("\\barrow\\([^,]+,[^,]+,[^,]+,\"([^\"]+)\"");
        var layoutHelper = Pattern.compile("\\b(?:field|button|message|apply)\\([^,]+,(?:[^,]+,)?\"([^\"]+)\"");

        try (var stream = Files.walk(JAVA_SRC)) {
            for (Path javaFile : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(javaFile, StandardCharsets.UTF_8);
                collect(fullKey, src, "", referenced);
                collect(trCall, src, "gui.just_enough_oritech.", referenced);
                for (String k : matches(controlCall, src)) {
                    referenced.add("gui.just_enough_oritech." + k);
                    referenced.add("gui.just_enough_oritech.control." + k);
                }
                collect(arrowCall, src, "gui.just_enough_oritech.control.", referenced);
                collect(layoutHelper, src, "gui.just_enough_oritech.", referenced);
            }
        }
        // Drop prefix fragments like "gui.just_enough_oritech.printer_help."
        referenced.removeIf(k -> k.endsWith("."));

        // Dynamic keys constructed from enums and numeric states.
        for (var status : ReactorSimulation.Status.values()) {
            referenced.add("gui.just_enough_oritech.state." + status.name().toLowerCase(Locale.ROOT));
        }
        for (var risk : ReactorDesigner.Risk.values()) {
            String lower = risk.name().toLowerCase(Locale.ROOT);
            referenced.add("gui.just_enough_oritech.auto_risk." + lower);
            referenced.add("gui.just_enough_oritech.auto_risk_note." + lower);
        }
        for (var mode : ReactorDesigner.CoolingMode.values()) {
            referenced.add("gui.just_enough_oritech.auto_cooling." + mode.name().toLowerCase(Locale.ROOT));
        }
        for (int state = 0; state <= 6; state++) {
            referenced.add("gui.just_enough_oritech.printer_state." + state);
        }
        for (String step : Set.of("preview", "attach", "keys", "send", "power")) {
            referenced.add("gui.just_enough_oritech.printer_help." + step);
        }
        for (String keyName : Set.of("open_simulator", "lock_projection", "cancel_projection", "rotate_projection")) {
            referenced.add("key.just_enough_oritech." + keyName);
        }
        referenced.add("key.categories.just_enough_oritech.main");
        referenced.add("gui.just_enough_oritech.control.layout_tools");

        var missingEn = referenced.stream().filter(k -> !en.containsKey(k)).toList();
        var missingRu = referenced.stream().filter(k -> !ru.containsKey(k)).toList();
        assertTrue(missingEn.isEmpty(), "Referenced keys missing in en_us.json: " + missingEn);
        assertTrue(missingRu.isEmpty(), "Referenced keys missing in ru_ru.json: " + missingRu);
    }

    @Test
    void mixinConfigReferencesExistingCompiledClasses() throws Exception {
        var json = JsonParser.parseString(
                Files.readString(RESOURCES.resolve("just_enough_oritech.mixins.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertTrue(json.get("required").getAsBoolean());
        String pkg = json.get("package").getAsString();
        assertEquals("com.justenoughoritech.mixin", pkg);
        var clientMixins = json.getAsJsonArray("client");
        assertFalse(clientMixins.isEmpty());
        for (var element : clientMixins) {
            String fqcn = pkg + "." + element.getAsString();
            assertNotNull(Class.forName(fqcn, false, getClass().getClassLoader()),
                    "Mixin class not found on classpath: " + fqcn);
        }
    }

    @Test
    void reactorPrinterRecipeUsesSteelIngotTagAndValidShapedPattern() throws IOException {
        var recipe = JsonParser.parseString(
                Files.readString(RESOURCES.resolve("data/just_enough_oritech/recipe/reactor_printer.json"),
                        StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
        assertEquals("just_enough_oritech:reactor_printer",
                recipe.getAsJsonObject("result").get("id").getAsString());

        var pattern = recipe.getAsJsonArray("pattern");
        assertEquals(3, pattern.size());
        var keys = recipe.getAsJsonObject("key");
        for (var row : pattern) {
            String line = row.getAsString();
            assertEquals(3, line.length());
            for (char c : line.toCharArray()) {
                if (c != ' ') assertTrue(keys.has(String.valueOf(c)), "Undefined pattern key: " + c);
            }
        }
        // FTB Evolution 2 unifies steel ingots via #c:ingots/steel; hardcoded oritech:steel_ingot is uncraftable there.
        assertEquals("#c:ingots/steel", keys.get("S").getAsString(),
                "Steel slot must use #c:ingots/steel tag for modpack unification compatibility");
    }

    @Test
    void blockstateItemModelLootTableAndTexturesAreConsistent() throws IOException {
        var blockstate = JsonParser.parseString(
                Files.readString(RESOURCES.resolve("assets/just_enough_oritech/blockstates/reactor_printer.json"),
                        StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("just_enough_oritech:block/reactor_printer",
                blockstate.getAsJsonObject("variants").getAsJsonObject("").get("model").getAsString());

        var itemDef = JsonParser.parseString(
                Files.readString(RESOURCES.resolve("assets/just_enough_oritech/items/reactor_printer.json"),
                        StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("just_enough_oritech:block/reactor_printer",
                itemDef.getAsJsonObject("model").get("model").getAsString());

        var model = JsonParser.parseString(
                Files.readString(RESOURCES.resolve("assets/just_enough_oritech/models/block/reactor_printer.json"),
                        StandardCharsets.UTF_8)).getAsJsonObject();
        var textures = model.getAsJsonObject("textures");
        assertFalse(textures.isEmpty());
        byte[] pngMagic = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        for (var entry : textures.entrySet()) {
            String ref = entry.getValue().getAsString();
            assertTrue(ref.startsWith("just_enough_oritech:"), "Unexpected texture namespace: " + ref);
            Path png = RESOURCES.resolve("assets/just_enough_oritech/textures/" + ref.substring("just_enough_oritech:".length()) + ".png");
            assertTrue(Files.isRegularFile(png), "Missing texture file: " + png);
            byte[] bytes = Files.readAllBytes(png);
            assertTrue(bytes.length > 24);
            byte[] header = new byte[8];
            System.arraycopy(bytes, 0, header, 0, 8);
            assertArrayEquals(pngMagic, header, "Invalid PNG signature in " + png);
        }

        var loot = JsonParser.parseString(
                Files.readString(RESOURCES.resolve("data/just_enough_oritech/loot_table/blocks/reactor_printer.json"),
                        StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("minecraft:block", loot.get("type").getAsString());
        var pool = loot.getAsJsonArray("pools").get(0).getAsJsonObject();
        assertEquals("just_enough_oritech:reactor_printer",
                pool.getAsJsonArray("entries").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void neoforgeModsTomlDeclaresModDependenciesAndMixinConfig() throws IOException {
        String toml = Files.readString(RESOURCES.resolve("META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        assertTrue(toml.contains("modId=\"just_enough_oritech\""));
        assertTrue(toml.contains("config=\"just_enough_oritech.mixins.json\""));
        assertTrue(toml.contains("modId=\"neoforge\""));
        assertTrue(toml.contains("modId=\"minecraft\""));
        assertTrue(toml.contains("modId=\"oritech\""));
        assertTrue(toml.contains("modId=\"jei\""));
    }

    private static int countPlaceholders(String value) {
        int count = 0;
        Matcher m = PLACEHOLDER.matcher(value);
        while (m.find()) count++;
        return count;
    }

    private static void collect(Pattern pattern, String src, String prefix, Set<String> out) {
        Matcher m = pattern.matcher(src);
        while (m.find()) {
            String token = m.groupCount() >= 1 ? m.group(1) : m.group(0);
            out.add(prefix + token);
        }
    }

    private static Set<String> matches(Pattern pattern, String src) {
        var out = new HashSet<String>();
        Matcher m = pattern.matcher(src);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
