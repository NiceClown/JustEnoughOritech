package com.justenoughoritech.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.justenoughoritech.JustEnoughOritech;
import com.justenoughoritech.printer.BlueprintData;
import com.justenoughoritech.printer.PrinterLayout;
import com.justenoughoritech.sim.ReactorLayout;
import com.justenoughoritech.sim.ReactorParts;
import com.mojang.serialization.JsonOps;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Built-in starter presets and local player-saved reactor blueprint library. */
public final class BlueprintLibrary {
    public static final int MAX_ENTRIES = 128;
    public static final BlueprintLibrary INSTANCE = new BlueprintLibrary(defaultPath());

    public record Entry(String name, PrinterLayout layout, boolean builtin) {}

    private final Path file;
    private List<Entry> saved;

    public BlueprintLibrary(Path file) {
        this.file = file;
    }

    private static Path defaultPath() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve("just_enough_oritech").resolve("blueprints.json");
        } catch (Throwable ignored) {
            return Path.of("config", "just_enough_oritech", "blueprints.json");
        }
    }

    public static List<Entry> presets(int maxSize) {
        return presets(maxSize, ReactorParts.discover());
    }

    public static List<Entry> presets(int maxSize, List<ReactorParts.Part> discovered) {
        ReactorParts.Part singleRod = firstKind(discovered, ReactorParts.Kind.ROD, 1);
        ReactorParts.Part doubleRod = firstKind(discovered, ReactorParts.Kind.ROD, 2);
        ReactorParts.Part quadRod = firstKind(discovered, ReactorParts.Kind.ROD, 4);
        ReactorParts.Part vent = firstKind(discovered, ReactorParts.Kind.VENT, 0);
        ReactorParts.Part pipe = firstKind(discovered, ReactorParts.Kind.PIPE, 0);
        ReactorParts.Part absorber = firstKind(discovered, ReactorParts.Kind.ABSORBER, 0);
        ReactorParts.Part reflector = firstKind(discovered, ReactorParts.Kind.REFLECTOR, 0);

        int limit = Math.min(maxSize, PrinterLayout.LIMIT);
        var out = new ArrayList<Entry>();
        if (singleRod != null && vent != null && limit >= 3) {
            var starter = new ReactorLayout();
            starter.resize(3, 3, limit);
            starter.setHeight(3, limit);
            starter.setEnergyPorts(1);
            starter.set(1, 1, singleRod);
            starter.set(1, 0, vent);
            starter.set(1, 2, vent);
            starter.set(0, 1, vent);
            starter.set(2, 1, vent);
            if (starter.validationErrors(limit).isEmpty()) {
                out.add(new Entry("Starter Passive", PrinterLayout.from(starter), true));
            }
        }
        ReactorParts.Part balRod = singleRod != null ? singleRod : doubleRod;
        if (balRod != null && vent != null && limit >= 5) {
            var balanced = new ReactorLayout();
            balanced.resize(5, 5, limit);
            balanced.setHeight(5, limit);
            balanced.setEnergyPorts(2);
            ReactorParts.Part mid = pipe != null ? pipe : vent;
            for (int[] r : new int[][]{{1, 1}, {1, 3}, {3, 1}, {3, 3}}) balanced.set(r[0], r[1], balRod);
            for (int[] c : new int[][]{{2, 1}, {2, 3}, {1, 2}, {3, 2}}) balanced.set(c[0], c[1], mid);
            for (int[] v : new int[][]{{1, 0}, {3, 0}, {0, 1}, {4, 1}, {0, 3}, {4, 3}, {1, 4}, {3, 4}, {2, 2}}) {
                balanced.set(v[0], v[1], vent);
            }
            if (balanced.validationErrors(limit).isEmpty()) {
                out.add(new Entry("Balanced Passive", PrinterLayout.from(balanced), true));
            }
        }
        ReactorParts.Part activeRod = doubleRod != null ? doubleRod : singleRod;
        if (activeRod != null && absorber != null && pipe != null && limit >= 5) {
            var active = new ReactorLayout();
            active.resize(5, 5, limit);
            active.setHeight(5, limit);
            active.setEnergyPorts(4);
            for (int[] r : new int[][]{{1, 1}, {1, 3}, {3, 1}, {3, 3}}) active.set(r[0], r[1], activeRod);
            for (int[] p : new int[][]{{2, 1}, {2, 3}, {1, 2}, {3, 2}}) active.set(p[0], p[1], pipe);
            active.set(2, 2, absorber);
            for (int[] a : new int[][]{{1, 0}, {3, 0}, {0, 1}, {4, 1}, {0, 3}, {4, 3}, {1, 4}, {3, 4}}) {
                active.set(a[0], a[1], vent != null ? vent : absorber);
            }
            if (active.validationErrors(limit).isEmpty()) {
                out.add(new Entry("Active Ice Core", PrinterLayout.from(active), true));
            }
        }
        ReactorParts.Part highRod = quadRod != null ? quadRod : doubleRod != null ? doubleRod : singleRod;
        if (highRod != null && pipe != null && absorber != null && vent != null && limit >= 7) {
            var quad = new ReactorLayout();
            quad.resize(7, 7, limit);
            quad.setHeight(limit >= 8 ? 8 : limit, limit);
            quad.setEnergyPorts(4);
            String[] pattern = {
                    ".PV.VP.",
                    "P4PAP4P",
                    "VPAVAPV",
                    ".A.V.A.",
                    "VPAVAPV",
                    "P4PAP4P",
                    ".PV.VP."
            };
            for (int z = 0; z < 7; z++) {
                String row = pattern[z];
                for (int x = 0; x < 7; x++) {
                    quad.set(x, z, switch (row.charAt(x)) {
                        case '4' -> highRod;
                        case 'P' -> pipe;
                        case 'A' -> absorber;
                        case 'V' -> vent;
                        default -> ReactorParts.Part.EMPTY;
                    });
                }
            }
            if (quad.validationErrors(limit).isEmpty()) {
                out.add(new Entry("Compact High Output", PrinterLayout.from(quad), true));
            }
        }
        return List.copyOf(out);
    }

    private static ReactorParts.Part firstKind(List<ReactorParts.Part> parts, ReactorParts.Kind kind, int rodCount) {
        return parts.stream()
                .filter(p -> p.kind() == kind && (rodCount == 0 || p.rodCount() == rodCount))
                .min(Comparator.comparingInt(ReactorParts.Part::rodCount))
                .orElse(null);
    }

    public synchronized List<Entry> saved() {
        if (saved == null) load();
        return List.copyOf(saved);
    }

    public synchronized void load() {
        saved = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(raw);
            if (!root.isJsonArray()) return;
            for (JsonElement elem : root.getAsJsonArray()) {
                if (!elem.isJsonObject() || saved.size() >= MAX_ENTRIES) continue;
                JsonObject obj = elem.getAsJsonObject();
                String name = obj.has("name") && obj.get("name").isJsonPrimitive()
                        ? BlueprintData.sanitizeName(obj.get("name").getAsString()) : "";
                if (name.isBlank() || !obj.has("layout")) continue;
                var parsed = PrinterLayout.CODEC.parse(JsonOps.INSTANCE, obj.get("layout")).result();
                if (parsed.isPresent()) {
                    saved.add(new Entry(name, parsed.get(), false));
                }
            }
        } catch (Exception exception) {
            JustEnoughOritech.LOGGER.warn("Could not read saved reactor blueprints from {}", file, exception);
            saved.clear();
        }
    }

    public synchronized boolean add(String rawName, PrinterLayout layout) {
        if (saved == null) load();
        if (layout == null || saved.size() >= MAX_ENTRIES) return false;
        String name = BlueprintData.sanitizeName(rawName);
        if (name.isBlank()) name = layout.width() + "x" + layout.length() + "x" + layout.height();
        for (int i = 0; i < saved.size(); i++) {
            if (saved.get(i).name().equalsIgnoreCase(name)) {
                saved.set(i, new Entry(name, layout, false));
                save();
                return true;
            }
        }
        saved.add(new Entry(name, layout, false));
        save();
        return true;
    }

    public synchronized boolean rename(int index, String rawName) {
        if (saved == null) load();
        if (index < 0 || index >= saved.size()) return false;
        String name = BlueprintData.sanitizeName(rawName);
        if (name.isBlank()) return false;
        var current = saved.get(index);
        saved.set(index, new Entry(name, current.layout(), false));
        save();
        return true;
    }

    public synchronized boolean remove(int index) {
        if (saved == null) load();
        if (index < 0 || index >= saved.size()) return false;
        saved.remove(index);
        save();
        return true;
    }

    public synchronized void save() {
        if (file == null || saved == null) return;
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            var arr = new JsonArray();
            for (var entry : saved) {
                var encoded = PrinterLayout.CODEC.encodeStart(JsonOps.INSTANCE, entry.layout()).result();
                if (encoded.isEmpty()) continue;
                var obj = new JsonObject();
                obj.addProperty("name", entry.name());
                obj.add("layout", encoded.get());
                arr.add(obj);
            }
            Files.writeString(file, arr.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            JustEnoughOritech.LOGGER.warn("Could not save reactor blueprints to {}", file, exception);
        }
    }
}
