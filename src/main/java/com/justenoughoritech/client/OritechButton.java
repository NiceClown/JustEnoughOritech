package com.justenoughoritech.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Crisp Oritech-styled button that clips/scrolls text safely within its inner bevel. */
final class OritechButton extends Button {
    private final TextViewport textViewport = new TextViewport();
    private OritechTheme.ButtonStyle style;

    OritechButton(int x, int y, int w, int h, Component message, OritechTheme.ButtonStyle style,
                  Component tooltip, OnPress onPress) {
        super(x, y, w, h, message, onPress, DEFAULT_NARRATION);
        this.style = style == null ? OritechTheme.ButtonStyle.DARK : style;
        if (tooltip != null) setTooltip(Tooltip.create(tooltip));
    }

    void setStyle(OritechTheme.ButtonStyle style) {
        this.style = style == null ? OritechTheme.ButtonStyle.DARK : style;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        OritechTheme.button(graphics, getX(), getY(), getWidth(), getHeight(), style, isHoveredOrFocused(), active);
        var font = Minecraft.getInstance().font;
        int color = OritechTheme.buttonTextColor(style, active);
        int textY = getY() + Math.max(1, (getHeight() - 8) / 2);
        textViewport.line(graphics, font, getMessage(), getX() + 3, textY, Math.max(4, getWidth() - 6), color, true);
    }
}
