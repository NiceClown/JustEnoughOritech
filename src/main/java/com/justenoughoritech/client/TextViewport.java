package com.justenoughoritech.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

/** Text stays inside a measured slot even when the GUI is scaled down. */
final class TextViewport {
    private static final double HOLD_MS = 1_200;
    private static final double PIXELS_PER_MS = 0.024;
    private static final int LINE_HEIGHT = 9;
    private final Map<Long, Timing> timing = new HashMap<>();

    private record Timing(String text, long sinceNanos) {}

    void line(GuiGraphicsExtractor graphics, Font font, Component label, int x, int y,
              int width, int color, boolean centered) {
        if (width <= 0) return;
        int measured = font.width(label);
        if (measured <= width) {
            timing.remove(key(x,y));
            graphics.text(font,label,centered ? x+(width-measured)/2 : x,y,color);
            return;
        }
        int offset = scrollOffset(label,x,y,measured-width,PIXELS_PER_MS);
        graphics.enableScissor(x,y,x+width,y+LINE_HEIGHT);
        try {
            graphics.text(font,label,x-offset,y,color);
        } finally {
            graphics.disableScissor();
        }
    }

    void paragraph(GuiGraphicsExtractor graphics, Font font, Component label, int x, int y,
                   int width, int rows, int color) {
        if (width <= 0 || rows <= 0) return;
        var lines = font.split(label,width);
        int hidden = Math.max(0,lines.size()-rows);
        int first = 0;
        if (hidden>0) first=scrollOffset(label,x,y,hidden,1d/1_800);
        else timing.remove(key(x,y));
        graphics.enableScissor(x,y,x+width,y+rows*LINE_HEIGHT);
        try {
            for (int i=first;i<Math.min(lines.size(),first+rows);i++)
                graphics.text(font,lines.get(i),x,y+(i-first)*LINE_HEIGHT,color);
        } finally {
            graphics.disableScissor();
        }
    }

    private int scrollOffset(Component label,int x,int y,int max,double speed) {
        long key = key(x,y);
        String text = label.getString();
        long nowNanos = System.nanoTime();
        var from = timing.get(key);
        if (from==null || !from.text().equals(text)) {
            timing.put(key,new Timing(text,nowNanos));
            return 0;
        }
        long elapsedMs = Math.max(0L, (nowNanos - from.sinceNanos()) / 1_000_000L);
        return scrollOffsetAt(elapsedMs, max, speed);
    }

    static int scrollOffsetAt(long elapsedMs, int max, double speed) {
        if (max <= 0 || speed <= 0 || elapsedMs <= 0) return 0;
        double travel = max/speed;
        double phase = elapsedMs % (2*(travel+HOLD_MS));
        if (phase < HOLD_MS) return 0;
        if (phase < HOLD_MS+travel) return Math.clamp((int)((phase-HOLD_MS)*speed),0,max);
        if (phase < 2*HOLD_MS+travel) return max;
        return Math.clamp((int)(max-(phase-2*HOLD_MS-travel)*speed),0,max);
    }

    static long key(int x,int y) { return ((long)x<<32) | (y&0xffffffffL); }
}
