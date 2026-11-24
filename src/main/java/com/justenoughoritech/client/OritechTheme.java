package com.justenoughoritech.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Shared Bedrock-industrial palette and surface renderers matching Oritech's GUI design language
 * ({@code OritechSurface}: {@code PANEL}, {@code PANEL_DARK}, {@code PANEL_INSET}, {@code PANEL_ORANGE},
 * {@code PANEL_PRESSED}, and {@code itemslot}).
 */
public final class OritechTheme {
    public enum ButtonStyle {
        DARK, ORANGE, GREEN, RED, LIGHT
    }

    public static final int PANEL_BG = 0xFFD0D1D4;
    public static final int SUBPANEL_BG = 0xFFC2C4C9;
    public static final int BORDER_OUTER = 0xFF1E1E1F;
    public static final int HEADER_BG = 0xFF25282E;
    public static final int INSET_BG = 0xFF23262C;

    public static final int ORANGE_BRIGHT = 0xFFD18B33;
    public static final int ORANGE_DEEP = 0xFFB25020;
    public static final int ORANGE_HOVER = 0xFFDF9B42;
    public static final int CYAN_BRIGHT = 0xFF56C8DB;
    public static final int CYAN_DEEP = 0xFF3597AD;

    public static final int TEXT_DARK = 0xFF1E2024;
    public static final int TEXT_DARK_MUTED = 0xFF4B5058;
    public static final int TEXT_LIGHT = 0xFFF3F6F9;
    public static final int TEXT_MUTED = 0xFFB8C4D0;
    public static final int STATUS_OK = 0xFF57C972;
    public static final int STATUS_OK_DARK = 0xFF196B2C;
    public static final int STATUS_WARN = 0xFFE5A93B;
    public static final int STATUS_ALERT = 0xFFFF6B63;
    public static final int STATUS_ALERT_DARK = 0xFF9E1F1A;

    private OritechTheme() {}

    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (w < 4 || h < 4) return;
        g.fill(x, y, x + w, y + h, BORDER_OUTER);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL_BG);
        g.fill(x + 1, y + 1, x + w - 2, y + 3, 0xFFECEEF0);
        g.fill(x + 1, y + 1, x + 3, y + h - 2, 0xFFECEEF0);
        g.fill(x + 2, y + h - 3, x + w - 1, y + h - 1, 0xFF58585A);
        g.fill(x + w - 3, y + 2, x + w - 1, y + h - 1, 0xFF58585A);
    }

    public static void subPanel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (w < 4 || h < 4) return;
        g.fill(x, y, x + w, y + h, 0xFF7B7D82);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, SUBPANEL_BG);
        g.fill(x + 1, y + 1, x + w - 2, y + 2, 0xFFE5E7EA);
        g.fill(x + 1, y + 1, x + 2, y + h - 2, 0xFFE5E7EA);
    }

    public static void headerBar(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (w < 4 || h < 4) return;
        g.fill(x, y, x + w, y + h - 2, HEADER_BG);
        g.fill(x, y + h - 2, x + w, y + h - 1, ORANGE_BRIGHT);
        g.fill(x, y + h - 1, x + w, y + h, ORANGE_DEEP);
    }

    public static void inset(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (w < 4 || h < 4) return;
        g.fill(x, y, x + w, y + h, INSET_BG);
        g.fill(x, y, x + w, y + 1, 0x990D0E10);
        g.fill(x, y, x + 1, y + h, 0x990D0E10);
        g.fill(x, y + h - 1, x + w, y + h, 0x33FFFFFF);
        g.fill(x + w - 1, y, x + w, y + h, 0x33FFFFFF);
    }

    public static void slot(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, 0xFF8B8B8B);
        g.fill(x, y, x + 17, y + 1, 0xFF373737);
        g.fill(x, y, x + 1, y + 17, 0xFF373737);
        g.fill(x + 1, y + 17, x + 18, y + 18, 0xFFFFFFFF);
        g.fill(x + 17, y + 1, x + 18, y + 18, 0xFFFFFFFF);
    }

    public static void button(GuiGraphicsExtractor g, int x, int y, int w, int h,
                              ButtonStyle style, boolean hovered, boolean enabled) {
        if (w < 3 || h < 3) return;
        int fill;
        int hi;
        int lo;
        int border = enabled ? BORDER_OUTER : 0xFF2B2D30;
        if (!enabled) {
            fill = 0xFF36383B;
            hi = 0xFF424549;
            lo = 0xFF282A2D;
        } else {
            switch (style) {
                case ORANGE -> {
                    fill = hovered ? ORANGE_HOVER : ORANGE_BRIGHT;
                    hi = hovered ? 0xFFF3BF6D : 0xFFE5A94E;
                    lo = ORANGE_DEEP;
                }
                case GREEN -> {
                    fill = hovered ? 0xFF499D30 : 0xFF3C8527;
                    hi = hovered ? 0xFF72C05C : 0xFF639D52;
                    lo = 0xFF2C5A20;
                }
                case RED -> {
                    fill = hovered ? 0xFFB03C3C : 0xFF932F2F;
                    hi = hovered ? 0xFFD45B5B : 0xFFB84949;
                    lo = 0xFF611C1C;
                }
                case LIGHT -> {
                    fill = hovered ? 0xFFE5E7EB : PANEL_BG;
                    hi = 0xFFFEFEFE;
                    lo = 0xFF7B7D82;
                }
                case DARK -> {
                    fill = hovered ? 0xFF5A5C60 : 0xFF48494A;
                    hi = hovered ? 0xFF7A7C82 : 0xFF65676B;
                    lo = 0xFF313233;
                }
                default -> {
                    fill = 0xFF48494A;
                    hi = 0xFF65676B;
                    lo = 0xFF313233;
                }
            }
        }
        g.fill(x, y, x + w, y + h, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        g.fill(x + 1, y + 1, x + w - 2, y + 2, hi);
        g.fill(x + 1, y + 1, x + 2, y + h - 2, hi);
        g.fill(x + 2, y + h - 2, x + w - 1, y + h - 1, lo);
        g.fill(x + w - 2, y + 2, x + w - 1, y + h - 1, lo);
    }

    public static int buttonTextColor(ButtonStyle style, boolean enabled) {
        if (!enabled) return 0xFF889099;
        return switch (style) {
            case ORANGE, LIGHT -> 0xFF1A1816;
            default -> TEXT_LIGHT;
        };
    }

    public static void progressBar(GuiGraphicsExtractor g, int x, int y, int w, int h, float ratio, int color) {
        inset(g, x, y, w, h);
        float clamped = Math.clamp(ratio, 0f, 1f);
        int innerW = Math.max(0, w - 2);
        int fillW = Math.round(innerW * clamped);
        if (fillW > 0 && h > 2) {
            g.fill(x + 1, y + 1, x + 1 + fillW, y + h - 1, color);
            g.fill(x + 1, y + 1, x + 1 + fillW, y + 2, 0x33FFFFFF);
        }
    }
}
