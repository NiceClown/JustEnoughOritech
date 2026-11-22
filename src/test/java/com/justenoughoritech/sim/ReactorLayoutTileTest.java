package com.justenoughoritech.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorLayoutTileTest {
    private static ReactorParts.Part part(ReactorParts.Kind kind) {
        return new ReactorParts.Part("test:" + kind.name().toLowerCase(), "test", null, kind, 1, 1);
    }

    @Test
    void tilesTheWholeCoreAcrossBothAxes() {
        var layout = new ReactorLayout();
        layout.resize(2, 2, 66);
        layout.setHeight(2, 66);
        var rod = part(ReactorParts.Kind.ROD);
        var vent = part(ReactorParts.Kind.VENT);
        layout.set(0, 0, rod);
        layout.set(1, 1, vent);

        assertTrue(layout.tile(2, 3, 66));
        assertEquals(4, layout.width());
        assertEquals(6, layout.length());
        for (int z = 0; z < layout.length(); z++) {
            for (int x = 0; x < layout.width(); x++) {
                var expected = x % 2 == 0 && z % 2 == 0 ? rod
                        : x % 2 == 1 && z % 2 == 1 ? vent : ReactorParts.Part.EMPTY;
                assertEquals(expected.id(), layout.get(x, z).id(), "cell " + x + "," + z);
            }
        }
    }

    @Test
    void rejectsZeroOrOversizedCopies() {
        var layout = new ReactorLayout();
        layout.resize(2, 2, 66);
        layout.setHeight(2, 66);

        assertFalse(layout.tile(0, 1, 66));
        assertFalse(layout.tile(2, 1, 2));
        assertFalse(layout.tile(1, 2, 2));
        assertEquals(2, layout.width());
        assertEquals(2, layout.length());
    }
}
