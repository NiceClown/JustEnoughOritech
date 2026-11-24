package com.justenoughoritech.client;

import com.justenoughoritech.printer.BlueprintData;
import com.justenoughoritech.printer.PrinterContent;
import com.justenoughoritech.printer.PrinterLayout;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static com.justenoughoritech.client.SimulatorWidget.tr;

/** Oritech-styled browser for built-in presets, saved local blueprints, and inventory blueprint items. */
final class BlueprintLibraryScreen extends Screen {
    enum Tab { PRESETS, SAVED, INVENTORY }

    private record ItemRow(String title, String subtitle, PrinterLayout layout, BlueprintData.Anchor anchor,
                           int sourceIndex) {}

    private static final Workbench WB = Workbench.INSTANCE;
    private static final int PANEL_W = 340, PANEL_H = 226, ROWS = 7, ROW_H = 14;

    private final Screen parent;
    private final TextViewport text = new TextViewport();
    private Tab tab = Tab.PRESETS;
    private int left, top, selected = -1, offset;
    private EditBox nameBox;
    private OritechButton presetsTab, savedTab, inventoryTab, saveBtn, loadBtn, deleteBtn;
    private Component status = Component.empty();
    private boolean statusError;

    BlueprintLibraryScreen(Screen parent) {
        super(tr("library_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        left = (width - PANEL_W) / 2;
        top = Math.max(2, (height - PANEL_H) / 2);

        presetsTab = addRenderableWidget(new OritechButton(left + 8, top + 24, 104, 16, tr("library.presets"),
                OritechTheme.ButtonStyle.DARK, null, b -> switchTab(Tab.PRESETS)));
        savedTab = addRenderableWidget(new OritechButton(left + 118, top + 24, 104, 16, tr("library.saved"),
                OritechTheme.ButtonStyle.DARK, null, b -> switchTab(Tab.SAVED)));
        inventoryTab = addRenderableWidget(new OritechButton(left + 228, top + 24, 104, 16, tr("library.inventory"),
                OritechTheme.ButtonStyle.DARK, null, b -> switchTab(Tab.INVENTORY)));

        nameBox = addRenderableWidget(new EditBox(font, left + 8, top + 168, 224, 16, tr("library.name")));
        nameBox.setMaxLength(BlueprintData.MAX_NAME_LENGTH);
        nameBox.setValue(WB.blueprintName);
        nameBox.setResponder(v -> WB.blueprintName = BlueprintData.sanitizeName(v));

        saveBtn = addRenderableWidget(new OritechButton(left + 236, top + 168, 96, 16, tr("library.save"),
                OritechTheme.ButtonStyle.ORANGE, tr("library.save_tip"), b -> saveCurrent()));

        loadBtn = addRenderableWidget(new OritechButton(left + 8, top + 190, 104, 16, tr("library.load"),
                OritechTheme.ButtonStyle.ORANGE, tr("library.load_tip"), b -> loadSelected()));
        deleteBtn = addRenderableWidget(new OritechButton(left + 118, top + 190, 104, 16, tr("library.delete"),
                OritechTheme.ButtonStyle.RED, tr("library.delete_tip"), b -> deleteSelected()));
        addRenderableWidget(new OritechButton(left + 228, top + 190, 104, 16, Component.translatable("gui.done"),
                OritechTheme.ButtonStyle.DARK, null, b -> onClose()));

        updateButtonStates();
    }

    private void switchTab(Tab next) {
        tab = next;
        selected = -1;
        offset = 0;
        status = Component.empty();
        statusError = false;
        updateButtonStates();
    }

    private List<ItemRow> rows() {
        var list = new ArrayList<ItemRow>();
        int maxSize = Math.min(PrinterLayout.LIMIT, ClientRuntimeConfig.config().maxSize());
        switch (tab) {
            case PRESETS -> {
                var presets = BlueprintLibrary.presets(maxSize);
                for (int i = 0; i < presets.size(); i++) {
                    var p = presets.get(i);
                    list.add(new ItemRow(p.name(),
                            p.layout().width() + "x" + p.layout().length() + "x" + p.layout().height()
                                    + " (" + p.layout().ports() + "P)",
                            p.layout(), null, i));
                }
            }
            case SAVED -> {
                var saved = BlueprintLibrary.INSTANCE.saved();
                for (int i = 0; i < saved.size(); i++) {
                    var s = saved.get(i);
                    list.add(new ItemRow(s.name(),
                            s.layout().width() + "x" + s.layout().length() + "x" + s.layout().height()
                                    + " (" + s.layout().ports() + "P)",
                            s.layout(), null, i));
                }
            }
            case INVENTORY -> {
                var mc = minecraft;
                if (mc != null && mc.player != null) {
                    var inv = mc.player.getInventory();
                    for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
                        ItemStack st = inv.getItem(i);
                        var data = st.is(PrinterContent.BLUEPRINT.get()) ? st.get(PrinterContent.BLUEPRINT_DATA.get()) : null;
                        if (data == null) continue;
                        String title = data.name().isBlank() ? tr("blueprint_unnamed").getString() : data.name();
                        String sub = data.layout().width() + "x" + data.layout().length() + "x" + data.layout().height();
                        list.add(new ItemRow(title, sub, data.layout(), data.anchor().orElse(null), i));
                    }
                    ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
                    var offData = off.is(PrinterContent.BLUEPRINT.get()) ? off.get(PrinterContent.BLUEPRINT_DATA.get()) : null;
                    if (offData != null) {
                        String title = offData.name().isBlank() ? tr("blueprint_unnamed").getString() : offData.name();
                        String sub = offData.layout().width() + "x" + offData.layout().length() + "x" + offData.layout().height();
                        list.add(new ItemRow(title, sub, offData.layout(), offData.anchor().orElse(null), Inventory.SLOT_OFFHAND));
                    }
                }
            }
        }
        return list;
    }

    private void saveCurrent() {
        int limit = Math.min(PrinterLayout.LIMIT, ClientRuntimeConfig.config().maxSize());
        if (!WB.layout.validationErrors(limit).isEmpty()) {
            status = tr(WB.layout.validationErrors(limit).getFirst());
            statusError = true;
            return;
        }
        String raw = nameBox == null ? WB.blueprintName : nameBox.getValue();
        if (raw == null || raw.isBlank()) {
            raw = WB.layout.width() + "x" + WB.layout.length() + "x" + WB.layout.height();
            if (nameBox != null) nameBox.setValue(raw);
        }
        if (BlueprintLibrary.INSTANCE.add(raw, PrinterLayout.from(WB.layout))) {
            WB.blueprintName = BlueprintData.sanitizeName(raw);
            status = tr("library.saved_msg", WB.blueprintName);
            statusError = false;
            tab = Tab.SAVED;
            offset = 0;
            selected = BlueprintLibrary.INSTANCE.saved().size() - 1;
        } else {
            status = tr("library.save_failed");
            statusError = true;
        }
        updateButtonStates();
    }

    private void loadSelected() {
        var list = rows();
        if (selected < 0 || selected >= list.size()) return;
        var row = list.get(selected);
        int limit = Math.min(PrinterLayout.LIMIT, ClientRuntimeConfig.config().maxSize());
        var resolved = row.layout().resolve(limit);
        if (resolved == null) {
            status = tr("printer_invalid");
            statusError = true;
            return;
        }
        WB.loadBlueprint(row.title(), resolved);
        if (nameBox != null) nameBox.setValue(WB.blueprintName);
        status = tr("library.loaded_msg", WB.blueprintName.isBlank() ? row.title() : WB.blueprintName);
        statusError = false;
        onClose();
    }

    private void deleteSelected() {
        if (tab != Tab.SAVED) return;
        var list = rows();
        if (selected < 0 || selected >= list.size()) return;
        if (BlueprintLibrary.INSTANCE.remove(list.get(selected).sourceIndex())) {
            selected = Math.min(selected, BlueprintLibrary.INSTANCE.saved().size() - 1);
            status = tr("library.deleted_msg");
            statusError = false;
        }
        updateButtonStates();
    }

    private void updateButtonStates() {
        if (presetsTab != null) presetsTab.setStyle(tab == Tab.PRESETS ? OritechTheme.ButtonStyle.ORANGE : OritechTheme.ButtonStyle.DARK);
        if (savedTab != null) savedTab.setStyle(tab == Tab.SAVED ? OritechTheme.ButtonStyle.ORANGE : OritechTheme.ButtonStyle.DARK);
        if (inventoryTab != null) inventoryTab.setStyle(tab == Tab.INVENTORY ? OritechTheme.ButtonStyle.ORANGE : OritechTheme.ButtonStyle.DARK);
        var list = rows();
        if (loadBtn != null) loadBtn.active = selected >= 0 && selected < list.size();
        if (deleteBtn != null) deleteBtn.active = tab == Tab.SAVED && selected >= 0 && selected < list.size();
    }

    private int listTop() { return top + 45; }
    private int listBottom() { return top + 45 + ROWS * ROW_H + 4; }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && event.x() >= left + 8 && event.x() < left + PANEL_W - 8
                && event.y() >= listTop() + 2 && event.y() < listBottom() - 2) {
            int rowIdx = offset + ((int) event.y() - (listTop() + 2)) / ROW_H;
            var list = rows();
            if (rowIdx >= 0 && rowIdx < list.size()) {
                selected = rowIdx;
                updateButtonStates();
                if (doubleClick) loadSelected();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int max = Math.max(0, rows().size() - ROWS);
        if (dy != 0 && mx >= left && mx < left + PANEL_W && my >= listTop() && my < listBottom()) {
            offset = Math.clamp(offset + (dy < 0 ? 1 : -1), 0, max);
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xA8101216);
        OritechTheme.panel(g, left, top, PANEL_W, PANEL_H);
        OritechTheme.headerBar(g, left + 1, top + 1, PANEL_W - 2, 19);
        text.line(g, font, title, left + 8, top + 5, PANEL_W - 16, OritechTheme.TEXT_LIGHT, false);

        int lx = left + 8, ly = listTop(), lw = PANEL_W - 16, lh = ROWS * ROW_H + 4;
        OritechTheme.inset(g, lx, ly, lw, lh);
        var list = rows();
        int max = Math.max(0, list.size() - ROWS);
        offset = Math.clamp(offset, 0, max);
        if (list.isEmpty()) {
            text.paragraph(g, font, tr("library.empty"), lx + 8, ly + 10, lw - 16, 3, OritechTheme.TEXT_MUTED);
        } else {
            for (int r = 0; r < ROWS && offset + r < list.size(); r++) {
                int idx = offset + r;
                var row = list.get(idx);
                int ry = ly + 2 + r * ROW_H;
                if (idx == selected) {
                    g.fill(lx + 2, ry, lx + lw - 8, ry + ROW_H - 1, 0x55D18B33);
                    g.outline(lx + 2, ry, lw - 10, ROW_H - 1, OritechTheme.ORANGE_BRIGHT);
                }
                text.line(g, font, Component.literal(row.title()), lx + 6, ry + 3, lw - 96,
                        idx == selected ? OritechTheme.TEXT_LIGHT : OritechTheme.TEXT_MUTED, false);
                text.line(g, font, Component.literal(row.subtitle()), left + PANEL_W - 88, ry + 3, 72,
                        OritechTheme.CYAN_BRIGHT, false);
            }
        }
        if (max > 0) {
            int sx = lx + lw - 6;
            int sh = lh - 4;
            int thumb = Math.max(12, sh * ROWS / list.size());
            int sy = ly + 2 + (sh - thumb) * offset / max;
            g.fill(sx, ly + 2, sx + 4, ly + lh - 2, 0xFF14161A);
            g.fill(sx, sy, sx + 4, sy + thumb, OritechTheme.ORANGE_BRIGHT);
        }

        text.line(g, font, tr("library.name"), left + 8, top + 157, 224, OritechTheme.TEXT_DARK, false);
        text.line(g, font, status, left + 8, top + 211, PANEL_W - 16,
                statusError ? OritechTheme.STATUS_ALERT_DARK : OritechTheme.STATUS_OK_DARK, false);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
