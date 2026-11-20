package com.justenoughoritech.sim;

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorDesignerTest {
    private static ReactorParts.Part part(String id, ReactorParts.Kind kind, int rods, int pulses) {
        return new ReactorParts.Part(id, id, null, kind, rods, pulses);
    }

    private static List<ReactorParts.Part> parts(boolean cooling) {
        var parts = new ArrayList<ReactorParts.Part>();
        parts.add(ReactorParts.Part.EMPTY);
        parts.add(part("test:single", ReactorParts.Kind.ROD, 1, 1));
        parts.add(part("test:double", ReactorParts.Kind.ROD, 2, 4));
        parts.add(part("test:quad", ReactorParts.Kind.ROD, 4, 12));
        parts.add(part("test:pipe", ReactorParts.Kind.PIPE, 0, 0));
        parts.add(part("test:reflector", ReactorParts.Kind.REFLECTOR, 0, 0));
        if (cooling) {
            parts.add(part("test:absorber", ReactorParts.Kind.ABSORBER, 0, 0));
            parts.add(part("test:vent", ReactorParts.Kind.VENT, 0, 0));
        }
        return parts;
    }

    private static ReactorDesigner.Request request(long target, boolean resize) {
        return new ReactorDesigner.Request(target, ReactorDesigner.Risk.NORMAL,
                Set.of("test:single", "test:double", "test:quad"), resize,
                ReactorDesigner.CoolingMode.AUTO);
    }

    private static ReactorLayout layout() {
        var layout = new ReactorLayout();
        layout.resize(5, 5, 66);
        layout.setHeight(3, 66);
        return layout;
    }

    @Test
    void findsStableDesignForSmallReactor() {
        var result = ReactorDesigner.find(layout(), ReactorConfig.DEFAULT, parts(true),
                new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000), ItemStack.EMPTY,
                request(0, false));
        assertNotNull(result.safe(), "expected a stable design");
        assertTrue(result.safe().rodBlocks() > 0);
        assertTrue(result.safe().layout().width() <= 66);
        assertTrue(result.safe().layout().length() <= 66);
        assertTrue(result.safe().layout().height() <= 66);
        assertTrue(result.safe().peakHeat() <= ReactorConfig.DEFAULT.maxHeat());
    }

    @Test
    void findsTargetOnLargerLayout() {
        var layout = new ReactorLayout();
        layout.resize(9, 9, 66);
        layout.setHeight(3, 66);
        var result = ReactorDesigner.find(layout, ReactorConfig.DEFAULT, parts(true),
                new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000), ItemStack.EMPTY,
                request(2000, true));
        assertNotNull(result.safe(), "expected a stable design");
        assertTrue(result.safe().exportRate() >= 2000,
                "expected the planner to meet the 2000 RF/t target, got " + result.safe().exportRate());
    }

    @Test
    void handlesWideLayouts() {
        var layout = new ReactorLayout();
        layout.resize(6, 4, 66);
        layout.setHeight(3, 66);
        var result = ReactorDesigner.find(layout, ReactorConfig.DEFAULT, parts(true),
                new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000), ItemStack.EMPTY,
                request(0, false));
        assertNotNull(result.safe(), "expected a stable design on a wide layout");
    }

    @Test
    void findsLargerTarget() {
        var layout = new ReactorLayout();
        layout.resize(9, 9, 66);
        layout.setHeight(3, 66);
        var result = ReactorDesigner.find(layout, ReactorConfig.DEFAULT, parts(true),
                new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000), ItemStack.EMPTY,
                request(10_000, true));
        assertNotNull(result.safe(), "expected a stable design");
        assertTrue(result.safe().exportRate() >= 10_000,
                "expected at least 10000 RF/t, got " + result.safe().exportRate());
    }

    @Test
    void cautiousDesignRespectsHeatBudget() {
        var cautious = new ReactorDesigner.Request(0, ReactorDesigner.Risk.CAUTIOUS,
                Set.of("test:single", "test:double", "test:quad"), false,
                ReactorDesigner.CoolingMode.AUTO);
        var result = ReactorDesigner.find(layout(), ReactorConfig.DEFAULT, parts(true),
                new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000), ItemStack.EMPTY, cautious);
        if (result.safe() != null) {
            assertTrue((long) result.safe().peakHeat() * 100
                    <= (long) ReactorConfig.DEFAULT.maxHeat() * 65);
        }
    }

    @Test
    void returnsNothingWhenCancelled() {
        var cancelled = new AtomicBoolean(true);
        var result = ReactorDesigner.find(layout(), ReactorConfig.DEFAULT, parts(true),
                new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000), ItemStack.EMPTY,
                request(1000, true), cancelled::get);
        assertNull(result.safe());
        assertNull(result.dangerous());
    }

    @Test
    void needsCoolingParts() {
        var result = ReactorDesigner.find(layout(), ReactorConfig.DEFAULT, parts(false),
                new ReactorInputs.Fuel(ItemStack.EMPTY, 10_000), ItemStack.EMPTY,
                request(1000, false));
        assertNull(result.safe());
        assertNull(result.dangerous());
    }
}
