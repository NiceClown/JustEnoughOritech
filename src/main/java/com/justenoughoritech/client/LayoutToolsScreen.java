package com.justenoughoritech.client;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.justenoughoritech.printer.PrinterLayout;
import com.justenoughoritech.sim.ReactorLayout;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import static com.justenoughoritech.client.SimulatorWidget.tr;

final class LayoutToolsScreen extends Screen {
    private static final Workbench WB = Workbench.INSTANCE;
    private static final int HEIGHT = 200, GAP = 4, PAD = 8;
    private static final int MAX_CLIPBOARD_LENGTH = 2 * 1024 * 1024;

    private final Screen parent;
    private final TextViewport textViewport = new TextViewport();
    private int left, top, panelWidth, column;
    private EditBox coreWidth, coreLength, stackHeight, ports, xCopies, zCopies, rowCount;
    private Component status = Component.empty();
    private boolean error;

    LayoutToolsScreen(Screen parent) {
        super(tr("layout_tools_title"));
        this.parent = parent;
    }

    @Override protected void init() {
        panelWidth = Math.min(344, width - 8);
        left = (width - panelWidth) / 2;
        top = Math.max(2, (height - HEIGHT) / 2);
        column = (panelWidth - 2 * PAD - 3 * GAP) / 4;
        coreWidth = field(0, 45, "layout_width", WB.layout.width());
        coreLength = field(1, 45, "layout_length", WB.layout.length());
        stackHeight = field(2, 45, "layout_height", WB.layout.height());
        ports = field(3, 45, "layout_ports", WB.layout.energyPorts());
        button(0, 67, 2, "layout_resize", OritechTheme.ButtonStyle.ORANGE, () -> resize(false));
        button(2, 67, 2, "layout_replace", OritechTheme.ButtonStyle.RED, () -> resize(true));

        xCopies = field(0, 97, "layout_x_copies", 2);
        zCopies = field(1, 97, "layout_z_copies", 2);
        button(2, 97, 2, "layout_tile", OritechTheme.ButtonStyle.DARK, this::tile);

        rowCount = field(3, 120, "layout_count", 1);
        button(0, 142, 1, "layout_add_rows", OritechTheme.ButtonStyle.DARK, () -> changeFootprint(0, 1));
        button(1, 142, 1, "layout_add_columns", OritechTheme.ButtonStyle.DARK, () -> changeFootprint(1, 0));
        button(2, 142, 1, "layout_remove_rows", OritechTheme.ButtonStyle.DARK, () -> changeFootprint(0, -1));
        button(3, 142, 1, "layout_remove_columns", OritechTheme.ButtonStyle.DARK, () -> changeFootprint(-1, 0));

        button(0, 165, 1, "layout_import", OritechTheme.ButtonStyle.DARK, this::importLayout);
        button(1, 165, 1, "layout_export", OritechTheme.ButtonStyle.DARK, this::exportLayout);
        addRenderableWidget(new OritechButton(x(2), top + 165, column * 2 + GAP, 18,
                Component.translatable("gui.done"), OritechTheme.ButtonStyle.ORANGE, null, button -> onClose()));
    }

    private int x(int index) {
        return left + PAD + index * (column + GAP);
    }

    private EditBox field(int index, int y, String key, int value) {
        var box = addRenderableWidget(new EditBox(font, x(index), top + y, column, 18, tr(key)));
        box.setMaxLength(5);
        box.setFilter(input -> input.chars().allMatch(c -> c >= '0' && c <= '9'));
        box.setValue(Integer.toString(value));
        return box;
    }

    private void button(int index, int y, int span, String key, OritechTheme.ButtonStyle style, Runnable action) {
        addRenderableWidget(new OritechButton(x(index), top + y, column * span + GAP * (span - 1), 18,
                tr(key), style, null, ignored -> action.run()));
    }

    private static int limit() {
        return Math.min(ClientRuntimeConfig.config().maxSize(), PrinterLayout.LIMIT);
    }

    private static int number(EditBox field) {
        String value = field.getValue();
        return value.isEmpty() ? 0 : Integer.parseInt(value);
    }

    private void resize(boolean replace) {
        int width = number(coreWidth), length = number(coreLength), height = number(stackHeight);
        int count = number(ports), max = limit();
        if (!validSize(width, length, height)) return;
        int maxPorts = ReactorLayout.maxEnergyPorts(width, length, height);
        if (count < 1 || count > maxPorts) {
            message(true, "layout_invalid_ports", maxPorts);
            return;
        }
        ReactorLayout draft = replace ? new ReactorLayout() : WB.layout.copy();
        draft.resize(width, length, max);
        draft.setHeight(height, max);
        draft.setEnergyPorts(count);
        if (replace) {
            minecraft.setScreen(new ConfirmScreen(confirmed -> {
                if (confirmed && validSize(draft.width(), draft.length(), draft.height())) apply(draft, "layout_applied");
                minecraft.setScreen(this);
            }, tr("layout_replace"), tr("layout_confirm_replace")));
        } else {
            apply(draft, "layout_applied");
        }
    }

    private boolean validSize(int width, int length, int height) {
        int max = limit();
        if (width >= 1 && width <= max && length >= 1 && length <= max && height >= 1 && height <= max) return true;
        message(true, "layout_invalid_size", max);
        return false;
    }

    private void tile() {
        var draft = WB.layout.copy();
        if (!validSize(draft.width(), draft.length(), draft.height())) return;
        if (!draft.tile(number(xCopies), number(zCopies), limit())) {
            message(true, "layout_invalid_tile", limit());
            return;
        }
        apply(draft, "layout_applied");
    }

    private void changeFootprint(int dx, int dz) {
        int count = number(rowCount);
        if (count < 1) {
            message(true, "layout_invalid_count");
            return;
        }
        var draft = WB.layout.copy();
        int width = draft.width() + dx * count, length = draft.length() + dz * count;
        if (!validSize(width, length, draft.height())) return;
        draft.resize(width, length, limit());
        apply(draft, "layout_applied");
    }

    private void exportLayout() {
        PrinterLayout.CODEC.encodeStart(JsonOps.INSTANCE, PrinterLayout.from(WB.layout)).result().ifPresentOrElse(json -> {
            minecraft.keyboardHandler.setClipboard(json.toString());
            message(false, "layout_exported");
        }, () -> message(true, "layout_export_error"));
    }

    private void importLayout() {
        String text = minecraft.keyboardHandler.getClipboard();
        if (text != null && !text.isBlank() && text.length() <= MAX_CLIPBOARD_LENGTH) {
            try {
                var draft = PrinterLayout.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                        .result().map(blueprint -> blueprint.resolveDraft(limit())).orElse(null);
                if (draft != null) {
                    apply(draft, "layout_imported");
                    return;
                }
            } catch (JsonParseException ignored) {
                // Keep the previous draft when the clipboard is not a blueprint.
            }
        }
        message(true, "layout_import_error");
    }

    private void apply(ReactorLayout draft, String key) {
        WB.layout.replaceWith(draft);
        WB.edited();
        coreWidth.setValue(Integer.toString(draft.width()));
        coreLength.setValue(Integer.toString(draft.length()));
        stackHeight.setValue(Integer.toString(draft.height()));
        ports.setValue(Integer.toString(draft.energyPorts()));
        message(false, key);
    }

    private void message(boolean error, String key, Object... args) {
        this.error = error;
        status = tr(key, args);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xA8101216);
        OritechTheme.panel(graphics, left, top, panelWidth, HEIGHT);
        OritechTheme.headerBar(graphics, left + 1, top + 1, panelWidth - 2, 18);
        int inner = panelWidth - 2 * PAD;
        line(graphics, title, x(0), top + 5, inner, OritechTheme.TEXT_LIGHT);
        line(graphics, tr("layout_current", WB.layout.width(), WB.layout.length(), WB.layout.height(), limit()),
                x(0), top + 22, inner, OritechTheme.TEXT_DARK_MUTED);
        line(graphics, tr("layout_width"), x(0), top + 34, column, OritechTheme.TEXT_DARK);
        line(graphics, tr("layout_length"), x(1), top + 34, column, OritechTheme.TEXT_DARK);
        line(graphics, tr("layout_height"), x(2), top + 34, column, OritechTheme.TEXT_DARK);
        line(graphics, tr("layout_ports"), x(3), top + 34, column, OritechTheme.TEXT_DARK);
        line(graphics, tr("layout_x_copies"), x(0), top + 87, column, OritechTheme.TEXT_DARK);
        line(graphics, tr("layout_z_copies"), x(1), top + 87, column, OritechTheme.TEXT_DARK);
        line(graphics, tr("layout_count"), x(0), top + 125, column * 3 + 2 * GAP, OritechTheme.TEXT_DARK);
        line(graphics, status, x(0), top + 188, inner,
                error ? OritechTheme.STATUS_ALERT_DARK : OritechTheme.STATUS_OK_DARK);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void line(GuiGraphicsExtractor graphics, Component label, int x, int y, int width, int color) {
        textViewport.line(graphics, font, label, x, y, width, color, false);
    }
}
