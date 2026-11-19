package com.justenoughoritech.sim;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class ReactorParts {
    public enum Kind { EMPTY, ROD, PIPE, ABSORBER, VENT, REFLECTOR }
    public record Part(String id, String label, Block block, Kind kind, int rodCount, int pulses) {
        public static final Part EMPTY = new Part("", "Empty", null, Kind.EMPTY, 0, 0);
        @Override public String toString() { return label; }
    }

    private ReactorParts() {}

    /** Discover Oritech blocks at runtime so the add-on does not link to one Oritech binary. */
    public static List<Part> discover() {
        var out = new ArrayList<Part>();
        out.add(Part.EMPTY);
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block == null) continue;
            Class<?> type = block.getClass();
            String className = hierarchyName(type);
            Kind kind = kind(className);
            if (kind == Kind.EMPTY) continue;
            var id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null || !id.getNamespace().equals("oritech")) continue;
            int rods = readInt(block, "getRodCount", kind == Kind.ROD ? 1 : 0);
            int pulses = readInt(block, "getInternalPulseCount", kind == Kind.ROD ? rods : 0);
            out.add(new Part(id.toString(), humanize(id.getPath()), block, kind, rods, pulses));
        }
        return List.copyOf(out);
    }

    public static Part wall() { return find("reactor_wall"); }
    public static Part fuelPort() { return find("reactor_fuel_port"); }
    public static Part coolantPort() { return find("reactor_coolant_absorber_port"); }
    public static Part energyPort() { return find("reactor_energy_port"); }

    private static Part find(String path) {
        var id = Identifier.fromNamespaceAndPath("oritech", path);
        if (!BuiltInRegistries.BLOCK.containsKey(id)) return Part.EMPTY;
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        return block == null ? Part.EMPTY : new Part(id.toString(), humanize(path), block, Kind.EMPTY, 0, 0);
    }

    private static Kind kind(String name) {
        if (name.endsWith("ReactorRodBlock")) return Kind.ROD;
        if (name.endsWith("ReactorHeatPipeBlock")) return Kind.PIPE;
        if (name.endsWith("ReactorAbsorberBlock")) return Kind.ABSORBER;
        if (name.endsWith("ReactorHeatVentBlock")) return Kind.VENT;
        if (name.endsWith("ReactorNeutronReflectorBlock")) return Kind.REFLECTOR;
        return Kind.EMPTY;
    }

    private static String hierarchyName(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.startsWith("rearth.oritech.block.blocks.reactor.")) return c.getSimpleName();
        }
        return "";
    }

    private static int readInt(Object instance, String method, int fallback) {
        try {
            Method m = instance.getClass().getMethod(method);
            return ((Number) m.invoke(instance)).intValue();
        } catch (ReflectiveOperationException ignored) { return fallback; }
    }

    private static String humanize(String path) {
        var words = new StringBuilder();
        for (String part : path.split("_")) {
            if (!words.isEmpty()) words.append(' ');
            words.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return words.toString();
    }
}
