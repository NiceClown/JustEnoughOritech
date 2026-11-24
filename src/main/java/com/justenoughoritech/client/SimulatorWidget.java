package com.justenoughoritech.client;

import com.justenoughoritech.printer.BlueprintData;
import com.justenoughoritech.printer.OpenBlueprintBenchRequest;
import com.justenoughoritech.printer.PrinterContent;
import com.justenoughoritech.printer.PrinterLayout;
import com.justenoughoritech.printer.ReactorWorldScanner;
import com.justenoughoritech.sim.ReactorLayout;
import com.justenoughoritech.sim.ReactorParts;
import com.justenoughoritech.sim.ReactorSimulation;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.widgets.IRecipeWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

public final class SimulatorWidget implements IRecipeWidget {
    private static final int MAIN_W = 420, CARD_X = 424, CARD_Y = 18, CARD_W = 92, CARD_H = 204;
    private static final int W = CARD_X + CARD_W, H = 232, CELL = 17, GX = 7, GY = 42, GRID = 5;
    private static final int PART_SLOT_X = 21, PART_SLOT_Y = 172;
    private static final int LIST_TOP = 152, LIST_HEIGHT = 70, LIST_ROWS = 6;
    private static final int PREVIEW_X = 110, PREVIEW_Y = 54, PREVIEW_W = 149, PREVIEW_H = 100;
    private static final int HEAT_BTN_X = 224, EYE_X = 244, TOOLS_Y = 30;
    private static final int COOL = 0xFF418C50, WARM = 0xFFBE9D3D, HOT = 0xFFFF414A, SELECTED = 0xFFFFDA66;
    private static final int WARM_THRESHOLD = 65, HOT_THRESHOLD = 100;
    private static final Workbench WB = Workbench.INSTANCE;
    private static Viewport viewport;
    private static Viewport scrollbarDrag;
    private static Viewport previewDrag;
    private static Viewport gridDrag;
    private static int gridDragButton = -1, gridDragStartX, gridDragStartZ, gridDragEndX, gridDragEndZ;
    private final float scale;
    private final TextViewport textViewport = new TextViewport();
    private int previewRevision = -1;
    private int previewLayer = -2;
    private boolean previewHeat;
    private List<ReactorLayout.Placement> preview = List.of();

    private record Viewport(Screen screen, SimulatorWidget widget, ScreenRectangle clip,
                            float left, float top, float scaleX, float scaleY) {
        boolean contains(Screen screen, double x, double y) {
            return this.screen == screen && clip != null && scaleX > 0 && scaleY > 0
                    && clip.containsPoint((int) Math.floor(x), (int) Math.floor(y));
        }

        double x(double mouseX) { return (mouseX - left) / scaleX; }
        double y(double mouseY) { return (mouseY - top) / scaleY; }
    }

    public SimulatorWidget() { this(displayScale()); }
    SimulatorWidget(float scale) { this.scale = scale; }

    private static float displayScale() {
        var window = Minecraft.getInstance().getWindow();
        return Math.min(1f, Math.min(Math.max(1, window.getGuiScaledWidth() - 100) / (float) W,
                Math.max(1, window.getGuiScaledHeight() - 112) / (float) H));
    }

    public static int displayWidth() { return (int) Math.ceil(W * displayScale()); }
    public static int displayHeight() { return (int) Math.ceil(H * displayScale()); }
    @Override public ScreenPosition getPosition() { return new ScreenPosition(0, 0); }

    static Component tr(String key, Object... args) {
        return Component.translatable("gui.just_enough_oritech." + key, args);
    }

    @Override
    public void drawWidget(GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
        WB.refreshParts();
        WB.refreshInputs();
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.scale(scale);
        try {
            graphics.enableScissor(0, 0, W, H);
            try {
                viewport = new Viewport(Minecraft.getInstance().screen, this, graphics.peekScissorStack(),
                        pose.m20(), pose.m21(), pose.m00(), pose.m11());
                drawEditor(graphics);
                for (var button : buttons()) button.draw(graphics, textViewport, mouseX / scale, mouseY / scale);
                drawEye(graphics);
            } finally {
                graphics.disableScissor();
            }
        } finally {
            pose.popMatrix();
        }
    }

    static void clearViewport(ScreenEvent.Render.Pre event) {
        viewport = null;
        if (scrollbarDrag != null && scrollbarDrag.screen() != event.getScreen()) scrollbarDrag = null;
        if (previewDrag != null && previewDrag.screen() != event.getScreen()) previewDrag = null;
        if (gridDrag != null && gridDrag.screen() != event.getScreen()) gridDrag = null;
    }

    static void handlePress(ScreenEvent.MouseButtonPressed.Pre event) {
        var view = viewport;
        if (view == null || !view.contains(event.getScreen(), event.getMouseX(), event.getMouseY())) return;
        double x = view.x(event.getMouseX()), y = view.y(event.getMouseY());
        if ((event.getButton() == 0 || event.getButton() == 1) && grid(x, y)) {
            int cx = WB.gridOffsetX + (int) (x - GX) / CELL;
            int cz = WB.gridOffsetZ + (int) (y - GY) / CELL;
            if (cx >= 0 && cx < WB.layout.width() && cz >= 0 && cz < WB.layout.length()) {
                gridDrag = view;
                gridDragButton = event.getButton();
                gridDragStartX = gridDragEndX = cx;
                gridDragStartZ = gridDragEndZ = cz;
                event.setCanceled(true);
                return;
            }
        }
        if (event.getButton() == 0 && list(x, y) && x >= 406) scrollbarDrag = view;
        if (event.getButton() == 0 && inPreview(x, y)) previewDrag = view;
        if (view.widget().click(x, y, event.getButton())) event.setCanceled(true);
    }

    static void handleRelease(ScreenEvent.MouseButtonReleased.Pre event) {
        boolean handled = false;
        if (gridDrag != null && event.getButton() == gridDragButton) {
            if (gridDrag.screen() == event.getScreen()) {
                int minX = Math.max(0, Math.min(gridDragStartX, gridDragEndX));
                int maxX = Math.min(WB.layout.width() - 1, Math.max(gridDragStartX, gridDragEndX));
                int minZ = Math.max(0, Math.min(gridDragStartZ, gridDragEndZ));
                int maxZ = Math.min(WB.layout.length() - 1, Math.max(gridDragStartZ, gridDragEndZ));
                var part = gridDragButton == 1 ? ReactorParts.Part.EMPTY : WB.selected();
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        WB.layout.set(x, z, part);
                    }
                }
                WB.cursorX = Math.clamp(gridDragEndX, 0, WB.layout.width() - 1);
                WB.cursorZ = Math.clamp(gridDragEndZ, 0, WB.layout.length() - 1);
                WB.edited();
                handled = true;
            }
            gridDrag = null;
            gridDragButton = -1;
        }
        if (scrollbarDrag != null && event.getButton() == 0) {
            if (scrollbarDrag.screen() == event.getScreen()) handled = true;
            scrollbarDrag = null;
        }
        if (previewDrag != null && event.getButton() == 0) {
            if (previewDrag.screen() == event.getScreen()) handled = true;
            previewDrag = null;
        }
        if (handled) event.setCanceled(true);
    }

    static void handleDrag(ScreenEvent.MouseDragged.Pre event) {
        if (gridDrag != null && gridDrag.screen() == event.getScreen() && event.getMouseButton() == gridDragButton) {
            double gx = gridDrag.x(event.getMouseX()), gy = gridDrag.y(event.getMouseY());
            int maxVisX = Math.min(GRID - 1, Math.max(0, WB.layout.width() - 1 - WB.gridOffsetX));
            int maxVisZ = Math.min(GRID - 1, Math.max(0, WB.layout.length() - 1 - WB.gridOffsetZ));
            int cellX = Math.clamp((int) Math.floor((gx - GX) / CELL), 0, maxVisX);
            int cellZ = Math.clamp((int) Math.floor((gy - GY) / CELL), 0, maxVisZ);
            gridDragEndX = Math.clamp(WB.gridOffsetX + cellX, 0, WB.layout.width() - 1);
            gridDragEndZ = Math.clamp(WB.gridOffsetZ + cellZ, 0, WB.layout.length() - 1);
            event.setCanceled(true);
            return;
        }
        if (event.getMouseButton() != 0) return;
        if (scrollbarDrag != null && scrollbarDrag.screen() == event.getScreen()) {
            scrollbarDrag.widget().scrollBar(scrollbarDrag.y(event.getMouseY()));
            event.setCanceled(true);
            return;
        }
        if (previewDrag != null && previewDrag.screen() == event.getScreen()) {
            AssemblyPreview.orbit(WB.previewOrientation, event.getDragX(), event.getDragY());
            event.setCanceled(true);
        }
    }

    static void handleScroll(ScreenEvent.MouseScrolled.Pre event) {
        var view = viewport;
        if (view != null && view.contains(event.getScreen(), event.getMouseX(), event.getMouseY())
                && view.widget().scroll(view.x(event.getMouseX()), view.y(event.getMouseY()),
                event.getScrollDeltaX(), event.getScrollDeltaY())) event.setCanceled(true);
    }

    private void drawEditor(GuiGraphicsExtractor g) {
        OritechTheme.panel(g, 0, 0, MAIN_W, H);
        OritechTheme.headerBar(g, 1, 1, MAIN_W - 2, 25);

        OritechTheme.subPanel(g, 4, 28, 100, 200);
        OritechTheme.subPanel(g, 107, 28, 155, 200);
        OritechTheme.subPanel(g, 265, 28, 151, 200);

        text(g, 6, 4, 240, Component.translatable("jei.just_enough_oritech.reactor.title"), OritechTheme.TEXT_LIGHT);
        var snapshot = ClientRuntimeConfig.snapshot();
        var compatibility = ClientRuntimeConfig.compatibility();
        Component status = switch (compatibility) {
            case WAITING -> tr("waiting");
            case VERIFIED -> Component.literal(snapshot.oritechVersion());
            case UNVERIFIED -> Component.empty().append(tr("unverified")).append(" ").append(snapshot.oritechVersion());
            case INCOMPATIBLE -> tr("incompatible", issueText(ClientRuntimeConfig.runtimeIssue()));
        };
        text(g, 252, 4, 162, status,
                compatibility == ClientRuntimeConfig.Compatibility.VERIFIED ? OritechTheme.STATUS_OK : OritechTheme.STATUS_ALERT);
        drawHeatLegend(g);

        text(g, GX, 31, 94, tr("interior", WB.layout.width(), WB.layout.length()), OritechTheme.TEXT_DARK);
        drawGrid(g);
        var selected = WB.selected().block();
        text(g, GX, 129, 94, tr("selected", selected == null ? tr("empty") : selected.getName()), OritechTheme.TEXT_DARK);
        OritechTheme.slot(g, PART_SLOT_X, PART_SLOT_Y);
        if (selected != null) {
            g.fakeItem(new ItemStack(selected), PART_SLOT_X + 1, PART_SLOT_Y + 1);
        } else {
            g.fill(PART_SLOT_X + 1, PART_SLOT_Y + 1, PART_SLOT_X + 17, PART_SLOT_Y + 17, 0x70232930);
        }
        text(g, GX, 191, 94, tr("layer_number", WB.selectedLayer + 1, WB.layout.height()), OritechTheme.TEXT_DARK_MUTED);
        text(g, GX, 201, 94, tr("ports", WB.layout.energyPorts()), OritechTheme.TEXT_DARK_MUTED);

        text(g, 111, 32, 53, tr(WB.layersMode ? "preview_layer" : "preview_3d"), OritechTheme.TEXT_DARK);
        int editWidth = Math.min(GRID, WB.layout.width() - WB.gridOffsetX);
        int editLength = Math.min(GRID, WB.layout.length() - WB.gridOffsetZ);
        text(g, 111, 45, 147, tr("edit_area_coords", WB.gridOffsetX + 1, WB.gridOffsetX + editWidth,
                WB.gridOffsetZ + 1, WB.gridOffsetZ + editLength), OritechTheme.TEXT_DARK_MUTED);

        OritechTheme.inset(g, PREVIEW_X, PREVIEW_Y, PREVIEW_W, PREVIEW_H);
        int layer = WB.layersMode ? WB.selectedLayer + 1 : -1;
        boolean heatmap = !WB.layersMode && WB.heatmap3d;
        if (previewRevision != WB.revision() || previewLayer != layer || previewHeat != heatmap) {
            previewRevision = WB.revision();
            previewLayer = layer;
            previewHeat = heatmap;
            int yStep = Math.max(1, (int) Math.ceil(((long) WB.layout.width() * WB.layout.length() * WB.layout.height()) / 12_000.0));
            preview = layer >= 0
                    ? WB.layout.layerPlacements(layer, Rotation.NONE, Mirror.NONE)
                    : heatmap
                    ? WB.layout.heatmapCutawayPlacements(Rotation.NONE, Mirror.NONE, yStep)
                    : WB.layout.shellPlacements(Rotation.NONE, Mirror.NONE);
        }
        var editArea = WB.showEditArea ? new AssemblyPreview.EditArea(WB.gridOffsetX + 1, WB.gridOffsetZ + 1,
                editWidth, editLength, WB.selectedLayer + 2) : null;
        AssemblyPreview.draw(g, preview, WB.layout.width(), WB.layout.height(), WB.layout.length(), layer, WB.simulation,
                editArea, WB.previewOrientation, WB.previewZoom, PREVIEW_X + 1, PREVIEW_Y + 1, PREVIEW_W - 2, PREVIEW_H - 2, heatmap);
        if (heatmap) {
            Component badge = WB.simulation != null
                    ? tr("heatmap_badge", WB.simulation.maxHeatSeen(), ClientRuntimeConfig.config().maxHeat())
                    : tr("heatmap_ready");
            int badgeColor = WB.simulation != null
                    ? AssemblyPreview.thermalColor(WB.simulation.maxHeatSeen(), Math.max(1, ClientRuntimeConfig.config().maxHeat()))
                    : OritechTheme.CYAN_BRIGHT;
            text(g, PREVIEW_X + 4, PREVIEW_Y + 3, PREVIEW_W - 8, badge, badgeColor);
        }

        text(g, 111, 157, 147, tr("heat_history"), OritechTheme.TEXT_DARK_MUTED);
        drawHeatGraph(g);
        if (WB.designStatus != null) paragraph(g, 111, 190, 147, 2, WB.designStatus, OritechTheme.TEXT_DARK);

        var fuel = WB.inputs.fuels().isEmpty() ? null
                : WB.inputs.fuels().get(Math.floorMod(WB.selectedFuel, WB.inputs.fuels().size()));
        var coolant = WB.inputs.coolants().isEmpty() ? ItemStack.EMPTY
                : WB.inputs.coolants().get(Math.floorMod(WB.selectedCoolant, WB.inputs.coolants().size()));
        resource(g, 31, "fuel", fuel == null ? ItemStack.EMPTY : fuel.item());
        text(g, 270, 66, 141, fuel == null ? tr("none") : tr("fuel_ticks", fuel.duration()), OritechTheme.TEXT_DARK_MUTED);
        resource(g, 79, "coolant", coolant);

        if (WB.blocksMode) drawBlockList(g);
        else drawReport(g, compatibility);
        drawBlueprintCard(g, compatibility);
    }

    private void drawBlueprintCard(GuiGraphicsExtractor g, ClientRuntimeConfig.Compatibility compatibility) {
        boolean creative = isCreative();
        int paperCount = countPlayerPaper();

        OritechTheme.panel(g, CARD_X, CARD_Y, CARD_W, CARD_H);
        OritechTheme.headerBar(g, CARD_X + 1, CARD_Y + 1, CARD_W - 2, 16);
        text(g, CARD_X + 5, CARD_Y + 4, CARD_W - 10, tr("blueprint_card"), OritechTheme.TEXT_LIGHT);

        Component activeName = WB.blueprintName.isBlank()
                ? tr("blueprint_unnamed")
                : Component.literal(WB.blueprintName);
        text(g, CARD_X + 5, CARD_Y + 21, CARD_W - 10, activeName,
                WB.blueprintName.isBlank() ? OritechTheme.TEXT_DARK_MUTED : OritechTheme.TEXT_DARK);

        var errors = layoutErrors();
        Component supplyLabel = creative ? tr("blueprint_creative")
                : paperCount > 0 ? tr("blueprint_paper_count", paperCount) : tr("blueprint_no_paper");
        int supplyColor = creative ? OritechTheme.STATUS_OK_DARK
                : paperCount > 0 ? OritechTheme.TEXT_DARK_MUTED : OritechTheme.STATUS_ALERT_DARK;
        text(g, CARD_X + 5, CARD_Y + 56, CARD_W - 10, supplyLabel, supplyColor);

        int boxY = CARD_Y + 72;
        OritechTheme.inset(g, CARD_X + 4, boxY, CARD_W - 8, 56);
        var anchorOpt = ProjectionController.lockedAnchor();
        if (anchorOpt.isPresent()) {
            var anchor = anchorOpt.get();
            text(g, CARD_X + 7, boxY + 4, CARD_W - 14, tr("blueprint_pos_locked"), OritechTheme.STATUS_OK);
            text(g, CARD_X + 7, boxY + 14, CARD_W - 14,
                    Component.literal(anchor.origin().getX() + "," + anchor.origin().getY() + "," + anchor.origin().getZ()),
                    OritechTheme.TEXT_LIGHT);
            text(g, CARD_X + 7, boxY + 24, CARD_W - 14,
                    tr("blueprint_rot_deg", BlueprintData.rotationDegrees(anchor.rotation())), OritechTheme.TEXT_MUTED);
        } else {
            paragraph(g, CARD_X + 7, boxY + 5, CARD_W - 14, 3, tr("blueprint_pos_auto"), OritechTheme.TEXT_MUTED);
        }

        int msgY = CARD_Y + 132;
        if (WB.blueprintStatus != null) {
            paragraph(g, CARD_X + 5, msgY, CARD_W - 10, 3, WB.blueprintStatus,
                    WB.blueprintStatusError ? OritechTheme.STATUS_ALERT_DARK : OritechTheme.STATUS_OK_DARK);
        } else if (!errors.isEmpty()) {
            paragraph(g, CARD_X + 5, msgY, CARD_W - 10, 3,
                    Component.translatable(errors.getFirst()), OritechTheme.STATUS_ALERT_DARK);
        } else {
            paragraph(g, CARD_X + 5, msgY, CARD_W - 10, 3,
                    tr("blueprint_card_bench_hint"), OritechTheme.TEXT_DARK_MUTED);
        }
    }

    private void resource(GuiGraphicsExtractor g, int y, String key, ItemStack stack) {
        text(g, 270, y + 3, 98, tr(key), OritechTheme.TEXT_DARK);
        if (!stack.isEmpty()) g.fakeItem(stack, 392, y - 1);
        OritechTheme.inset(g, 288, y + 17, 106, 14);
        text(g, 291, y + 20, 100, stack.isEmpty() ? tr("none") : stack.getHoverName(), OritechTheme.TEXT_LIGHT);
    }

    private void drawGrid(GuiGraphicsExtractor g) {
        OritechTheme.inset(g, GX - 1, GY - 1, GRID * CELL + 2, GRID * CELL + 2);
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                int cx = WB.gridOffsetX + x, cz = WB.gridOffsetZ + z;
                int px = GX + x * CELL, py = GY + z * CELL;
                boolean valid = cx < WB.layout.width() && cz < WB.layout.length();
                g.fill(px, py, px + CELL - 1, py + CELL - 1, valid ? 0xFF3A444E : 0xFF1F242B);
                if (!valid) continue;
                if (WB.simulation != null && WB.simulation.cell(cx, cz) != null)
                    g.fill(px, py, px + CELL - 1, py + CELL - 1, heatColor(WB.simulation.cell(cx, cz).heat));
                var part = WB.layout.get(cx, cz);
                if (part.block() != null) g.fakeItem(new ItemStack(part.block()), px, py);
                if (cx == WB.cursorX && cz == WB.cursorZ) g.outline(px, py, CELL - 1, CELL - 1, SELECTED);
            }
        }
        if (gridDrag != null && gridDrag.screen() == Minecraft.getInstance().screen) {
            int selMinX = Math.max(WB.gridOffsetX, Math.min(gridDragStartX, gridDragEndX));
            int selMaxX = Math.min(WB.gridOffsetX + GRID - 1, Math.min(WB.layout.width() - 1, Math.max(gridDragStartX, gridDragEndX)));
            int selMinZ = Math.max(WB.gridOffsetZ, Math.min(gridDragStartZ, gridDragEndZ));
            int selMaxZ = Math.min(WB.gridOffsetZ + GRID - 1, Math.min(WB.layout.length() - 1, Math.max(gridDragStartZ, gridDragEndZ)));
            if (selMinX <= selMaxX && selMinZ <= selMaxZ) {
                boolean erase = gridDragButton == 1;
                int fill = erase ? 0x55FF4A4A : 0x554CF570;
                int border = erase ? 0xFFFF5A5A : 0xFF57FF6A;
                var dragPart = WB.selected().block();
                for (int gz = selMinZ; gz <= selMaxZ; gz++) {
                    for (int gx = selMinX; gx <= selMaxX; gx++) {
                        int px = GX + (gx - WB.gridOffsetX) * CELL;
                        int py = GY + (gz - WB.gridOffsetZ) * CELL;
                        g.fill(px, py, px + CELL - 1, py + CELL - 1, fill);
                        if (!erase && dragPart != null) {
                            g.fakeItem(new ItemStack(dragPart), px, py);
                        }
                    }
                }
                int bx = GX + (selMinX - WB.gridOffsetX) * CELL;
                int bz = GY + (selMinZ - WB.gridOffsetZ) * CELL;
                int bw = (selMaxX - selMinX + 1) * CELL - 1;
                int bh = (selMaxZ - selMinZ + 1) * CELL - 1;
                g.outline(bx, bz, bw, bh, border);
            }
        }
    }

    private void drawHeatLegend(GuiGraphicsExtractor g) {
        var font = Minecraft.getInstance().font;
        var label = tr("heat_legend");
        int x = 6;
        g.text(font, label, x, 15, OritechTheme.TEXT_MUTED, false);
        x += font.width(label) + 7;
        x = legendEntry(g, x, COOL, tr("heat_legend_cool", WARM_THRESHOLD), false);
        x = legendEntry(g, x, WARM, tr("heat_legend_warm", WARM_THRESHOLD, HOT_THRESHOLD), false);
        x = legendEntry(g, x, HOT, tr("heat_legend_hot", HOT_THRESHOLD), false);
        legendEntry(g, x, SELECTED, tr("heat_legend_selected"), true);
    }

    private int legendEntry(GuiGraphicsExtractor g, int x, int color, Component label, boolean outline) {
        if (outline) g.outline(x, 15, 7, 7, color);
        else g.fill(x, 15, x + 7, 22, color);
        int width = Minecraft.getInstance().font.width(label);
        text(g, x + 10, 15, Math.min(width, 414 - x - 10), label, OritechTheme.TEXT_MUTED);
        return x + width + 16;
    }

    static int heatColor(int heat) {
        long maximum = Math.max(1, ClientRuntimeConfig.config().maxHeat());
        long scaled = (long) heat * 100;
        return scaled >= maximum * HOT_THRESHOLD ? HOT : scaled >= maximum * WARM_THRESHOLD ? WARM : COOL;
    }

    private void drawHeatGraph(GuiGraphicsExtractor g) {
        OritechTheme.inset(g, 110, 167, 149, 20);
        if (WB.simulation == null) return;
        int[] history = WB.simulation.heatHistory();
        for (int i = 0; i < history.length; i++) {
            int bar = Math.clamp((int) (17d * history[i] / Math.max(1, ClientRuntimeConfig.config().maxHeat())), 1, 17);
            int x = 111 + i * 147 / Math.max(1, history.length);
            g.fill(x, 186 - bar, 111 + (i + 1) * 147 / history.length, 186, heatColor(history[i]));
        }
    }

    private void drawReport(GuiGraphicsExtractor g, ClientRuntimeConfig.Compatibility compatibility) {
        OritechTheme.inset(g, 269, 134, 144, 91);
        if (compatibility == ClientRuntimeConfig.Compatibility.WAITING) {
            paragraph(g, 273, 140, 136, 3, tr("waiting"), OritechTheme.TEXT_MUTED);
            return;
        }
        if (compatibility == ClientRuntimeConfig.Compatibility.INCOMPATIBLE) {
            paragraph(g, 273, 140, 136, 4, issueText(ClientRuntimeConfig.runtimeIssue()), OritechTheme.STATUS_ALERT);
            paragraph(g, 273, 184, 136, 3, tr("install", ClientRuntimeConfig.latestCompatible()), OritechTheme.STATUS_ALERT);
            return;
        }
        var sim = WB.simulation;
        String state = sim == null ? "ready" : sim.status().name().toLowerCase(java.util.Locale.ROOT);
        text(g, 273, 138, 136, tr("state", tr("state." + state)), OritechTheme.TEXT_LIGHT);
        if (sim == null) {
            paragraph(g, 273, 152, 136, 6, WB.issue == null ? tr("empty_run") : issueText(WB.issue), OritechTheme.TEXT_MUTED);
            return;
        }
        text(g, 273, 150, 136, tr("power", sim.generation()), OritechTheme.TEXT_LIGHT);
        text(g, 273, 162, 136, tr("export", sim.exportRate()), OritechTheme.CYAN_BRIGHT);
        text(g, 273, 174, 136, tr("peak", sim.maxHeatSeen(), ClientRuntimeConfig.config().maxHeat()), heatColor(sim.maxHeatSeen()));
        Component risk = switch (sim.status()) {
            case MELTDOWN -> tr("meltdown");
            case SAFE_COOLDOWN -> tr("cooldown", ClientRuntimeConfig.config().safeModeCooldown());
            case STEADY -> tr("steady", sim.ticks());
            default -> sim.unstableTicks() > 0 ? tr("failure", sim.ticksToMeltdown()) : tr("no_risk");
        };
        text(g, 273, 186, 136, risk, sim.unstableTicks() > 0 ? OritechTheme.STATUS_ALERT : OritechTheme.STATUS_OK);

        g.fill(272, 198, 410, 199, 0xFF363B42);
        String fuelRate = ReactorSimulation.formatRate(sim.fuelPerMinute());
        drawRateRow(g, 202, tr("economy.fuel_short"), Component.literal(fuelRate + "/m"));
        Component coolValue = sim.coolantPerSecond() > 0.0
                ? Component.literal(ReactorSimulation.formatRate(sim.coolantPerMinute()) + "/m")
                : tr("economy.passive");
        drawRateRow(g, 213, tr("economy.coolant_short"), coolValue);
    }

    private void drawRateRow(GuiGraphicsExtractor g, int y, Component label, Component value) {
        var font = Minecraft.getInstance().font;
        text(g, 273, y, 68, label, OritechTheme.TEXT_MUTED);
        int vw = font.width(value);
        text(g, 409 - vw, y, vw, value, OritechTheme.ORANGE_BRIGHT);
    }

    private static Component issueText(String issue) {
        if (issue == null || issue.isBlank()) return tr("missing_data");
        if (issue.startsWith("gui.just_enough_oritech.")) return Component.translatable(issue);
        return Component.literal(issue);
    }

    private List<Map.Entry<String, Integer>> blockList() {
        return WB.layout.blockCounts().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    }

    private void drawBlockList(GuiGraphicsExtractor g) {
        var entries = blockList();
        int max = Math.max(0, entries.size() - LIST_ROWS);
        WB.blockOffset = Math.clamp(WB.blockOffset, 0, max);
        text(g, 270, 137, 143, tr("total", entries.stream().mapToInt(Map.Entry::getValue).sum()), OritechTheme.TEXT_DARK);
        OritechTheme.inset(g, 270, LIST_TOP, 144, LIST_HEIGHT);
        for (int i = 0; i < LIST_ROWS && WB.blockOffset + i < entries.size(); i++) {
            var entry = entries.get(WB.blockOffset + i);
            var name = BuiltInRegistries.BLOCK.getValue(Identifier.parse(entry.getKey())).getName();
            text(g, 272, LIST_TOP + 2 + i * 11, 99, name, OritechTheme.TEXT_LIGHT);
            String count = Integer.toString(entry.getValue());
            var font = Minecraft.getInstance().font;
            text(g, 402 - font.width(count), LIST_TOP + 2 + i * 11, 30, Component.literal(count), OritechTheme.ORANGE_BRIGHT);
        }
        int slider = sliderHeight(entries.size());
        int top = LIST_TOP + (max == 0 ? 0 : (LIST_HEIGHT - slider) * WB.blockOffset / max);
        g.fill(406, LIST_TOP, 414, LIST_TOP + LIST_HEIGHT, 0xFF14161A);
        OritechTheme.button(g, 406, top, 8, slider,
                max == 0 ? OritechTheme.ButtonStyle.DARK : OritechTheme.ButtonStyle.ORANGE, false, max > 0);
    }

    private int sliderHeight(int count) {
        return Math.max(10, LIST_HEIGHT * LIST_ROWS / Math.max(LIST_ROWS, count));
    }

    private void scrollBar(double y) {
        int size = blockList().size(), max = Math.max(0, size - LIST_ROWS);
        int height = sliderHeight(size);
        WB.blockOffset = max == 0 ? 0 : Math.clamp((int) Math.round((y - LIST_TOP - height / 2d) * max / (LIST_HEIGHT - height)), 0, max);
    }

    private void drawEye(GuiGraphicsExtractor g) {
        int x = EYE_X + 3, y = TOOLS_Y + 3;
        int ink = OritechTheme.TEXT_LIGHT;
        g.fill(x + 2, y, x + 6, y + 1, ink);
        g.fill(x + 1, y + 1, x + 7, y + 2, ink);
        g.fill(x, y + 2, x + 2, y + 4, ink);
        g.fill(x + 3, y + 2, x + 5, y + 4, ink);
        g.fill(x + 6, y + 2, x + 8, y + 4, ink);
        g.fill(x + 1, y + 4, x + 7, y + 5, ink);
        g.fill(x + 2, y + 5, x + 6, y + 6, ink);
        if (!WB.showEditArea) {
            for (int i = 0; i < 8; i++) {
                g.fill(x + i, y + 6 - i, x + i + 1, y + 8 - i, 0xFF36383B);
                g.fill(x + i, y + 6 - i, x + i + 1, y + 7 - i, OritechTheme.STATUS_ALERT);
            }
        }
    }

    private record Control(int x, int y, int w, int h, Component label, Component tooltip, boolean enabled,
                           OritechTheme.ButtonStyle style, Runnable action, boolean variableStep) {
        Control(int x, int y, int w, int h, Component label, Component tooltip, boolean enabled,
                OritechTheme.ButtonStyle style, Runnable action) {
            this(x, y, w, h, label, tooltip, enabled, style, action, false);
        }

        boolean at(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }

        void draw(GuiGraphicsExtractor g, TextViewport viewport, double mx, double my) {
            OritechTheme.button(g, x, y, w, h, style, at(mx, my), enabled);
            if (!label.getString().isEmpty()) {
                int color = OritechTheme.buttonTextColor(style, enabled);
                viewport.line(g, Minecraft.getInstance().font, label, x + 3, y + (h - 8) / 2, w - 6, color, true);
            }
        }
    }

    private Control control(int x, int y, int width, String key, OritechTheme.ButtonStyle style, Runnable action) {
        return new Control(x, y, width, 14, tr(key), tr("control." + key), true, style, action);
    }

    private Control arrow(int x, int y, String label, String actionKey, Runnable action) {
        return new Control(x, y, 16, 14, Component.literal(label), tr("control." + actionKey),
                true, OritechTheme.ButtonStyle.DARK, action);
    }

    private Control stepControl(int x, int y, String key, IntConsumer action) {
        return new Control(x, y, 22, 14, tr(key), tr("control." + key), true, OritechTheme.ButtonStyle.DARK, () -> {
            var mc = Minecraft.getInstance();
            action.accept(mc.hasControlDown() ? 50 : mc.hasShiftDown() ? 10 : 1);
        }, true);
    }

    private List<Control> buttons() {
        var controls = new ArrayList<Control>();
        controls.add(stepControl(7, 140, "width_minus", step -> resize(-step, 0)));
        controls.add(stepControl(30, 140, "width_plus", step -> resize(step, 0)));
        controls.add(stepControl(55, 140, "length_minus", step -> resize(0, -step)));
        controls.add(stepControl(78, 140, "length_plus", step -> resize(0, step)));
        controls.add(stepControl(7, 156, "height_minus", step -> height(-step)));
        controls.add(stepControl(30, 156, "height_plus", this::height));
        controls.add(control(55, 156, 45, WB.layersMode ? "assembly" : "layer", OritechTheme.ButtonStyle.DARK,
                () -> WB.layersMode = !WB.layersMode));
        controls.add(new Control(7, 173, 13, 16, Component.literal("<"), tr("control.part_previous"),
                true, OritechTheme.ButtonStyle.DARK, () -> WB.cyclePart(-1)));
        controls.add(new Control(40, 173, 13, 16, Component.literal(">"), tr("control.part_next"),
                true, OritechTheme.ButtonStyle.DARK, () -> WB.cyclePart(1)));
        controls.add(new Control(55, 173, 45, 16, tr("preview"), tr("control.preview"),
                true, OritechTheme.ButtonStyle.DARK, ProjectionController::begin));
        controls.add(arrow(7, 212, "<", "layer_previous", () -> layer(-1)));
        controls.add(arrow(24, 212, ">", "layer_next", () -> layer(1)));
        controls.add(stepControl(55, 212, "port_minus", step -> ports(-step)));
        controls.add(stepControl(78, 212, "port_plus", this::ports));

        controls.add(new Control(166, TOOLS_Y, 56, 13, tr("layout_tools"), tr("control.layout_tools"), true,
                OritechTheme.ButtonStyle.DARK, () -> {
            var mc = Minecraft.getInstance();
            mc.setScreen(new LayoutToolsScreen(mc.screen));
        }));
        boolean ready = WB.inputs.available() && ClientRuntimeConfig.compatibility() != ClientRuntimeConfig.Compatibility.INCOMPATIBLE;
        controls.add(new Control(HEAT_BTN_X, TOOLS_Y, 18, 13, Component.literal("T°"),
                tr(WB.heatmap3d ? "heatmap_hide" : "heatmap_show"), !WB.layersMode,
                !WB.layersMode && WB.heatmap3d ? OritechTheme.ButtonStyle.GREEN : OritechTheme.ButtonStyle.DARK,
                () -> {
                    WB.heatmap3d = !WB.heatmap3d;
                    if (WB.heatmap3d && WB.simulation == null && ready && layoutErrors().isEmpty()) {
                        WB.start();
                    }
                }));
        controls.add(new Control(EYE_X, TOOLS_Y, 14, 13, Component.empty(),
                tr(WB.showEditArea ? "edit_area_hide" : "edit_area_show"), true,
                WB.showEditArea ? OritechTheme.ButtonStyle.GREEN : OritechTheme.ButtonStyle.DARK,
                () -> WB.showEditArea = !WB.showEditArea));

        controls.add(arrow(270, 48, "<", "fuel_previous", () -> fuel(-1)));
        controls.add(arrow(396, 48, ">", "fuel_next", () -> fuel(1)));
        controls.add(arrow(270, 95, "<", "coolant_previous", () -> coolant(-1)));
        controls.add(arrow(396, 95, ">", "coolant_next", () -> coolant(1)));
        controls.add(new Control(270, 117, 44, 14, tr(WB.running ? "pause" : "run"),
                tr(WB.running ? "control.pause" : "control.run"), ready,
                WB.running ? OritechTheme.ButtonStyle.GREEN : OritechTheme.ButtonStyle.ORANGE, () -> {
            if (WB.running) WB.pause(); else WB.start();
        }));
        controls.add(control(316, 117, 46, "reset", OritechTheme.ButtonStyle.DARK, WB::reset));
        controls.add(control(364, 117, 50, WB.blocksMode ? "results" : "blocks", OritechTheme.ButtonStyle.DARK,
                () -> WB.blocksMode = !WB.blocksMode));
        controls.add(new Control(110, 209, 149, 16, tr("auto"), tr("control.auto"), ready && !WB.designing(),
                OritechTheme.ButtonStyle.ORANGE, () -> {
            var mc = Minecraft.getInstance();
            mc.setScreen(new AutoDesignScreen(mc.screen));
        }));

        boolean validWrite = ready && layoutErrors().isEmpty();
        controls.add(new Control(CARD_X + 6, CARD_Y + 34, CARD_W - 12, 18, tr("blueprint_write_open"),
                tr("control.blueprint_bench"), validWrite, OritechTheme.ButtonStyle.ORANGE, this::openBlueprintBench));
        boolean hasAnchor = ProjectionController.lockedAnchor().isPresent();
        controls.add(new Control(CARD_X + 7, CARD_Y + 109, CARD_W - 14, 14,
                tr(hasAnchor ? "blueprint_clear_btn" : "blueprint_set_btn"),
                tr(hasAnchor ? "control.blueprint_clear" : "control.blueprint_set"), true,
                hasAnchor ? OritechTheme.ButtonStyle.GREEN : OritechTheme.ButtonStyle.DARK,
                hasAnchor ? ProjectionController::clearLockedAnchor : ProjectionController::begin));

        controls.add(new Control(CARD_X + 4, CARD_Y + 165, 41, 15, tr("blueprint_load_btn"),
                tr("control.blueprint_load"), true, OritechTheme.ButtonStyle.DARK, this::loadFromInventory));
        controls.add(new Control(CARD_X + 47, CARD_Y + 165, 41, 15, tr("blueprint_scan_btn"),
                tr("control.blueprint_scan"), true, OritechTheme.ButtonStyle.DARK, this::scanNearbyReactor));
        controls.add(new Control(CARD_X + 4, CARD_Y + 183, CARD_W - 8, 15, tr("blueprint_library_btn"),
                tr("control.blueprint_library"), true, OritechTheme.ButtonStyle.DARK, () -> {
            var mc = Minecraft.getInstance();
            mc.setScreen(new BlueprintLibraryScreen(mc.screen));
        }));
        return controls;
    }

    private void loadFromInventory() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        BlueprintData found = findBlueprintInHandOrInventory(mc.player.getInventory());
        if (found == null) {
            WB.blueprintStatus = tr("blueprint_none_inv");
            WB.blueprintStatusError = true;
            return;
        }
        int limit = Math.min(PrinterLayout.LIMIT, ClientRuntimeConfig.config().maxSize());
        var resolved = found.layout().resolve(limit);
        if (resolved == null) {
            WB.blueprintStatus = tr("printer_invalid");
            WB.blueprintStatusError = true;
            return;
        }
        WB.loadBlueprint(found.name(), resolved);
        WB.blueprintStatus = tr("blueprint_loaded_inv");
        WB.blueprintStatusError = false;
    }

    private static BlueprintData findBlueprintInHandOrInventory(Inventory inv) {
        ItemStack main = inv.getSelectedItem();
        if (main.is(PrinterContent.BLUEPRINT.get()) && main.get(PrinterContent.BLUEPRINT_DATA.get()) != null) {
            return main.get(PrinterContent.BLUEPRINT_DATA.get());
        }
        ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
        if (off.is(PrinterContent.BLUEPRINT.get()) && off.get(PrinterContent.BLUEPRINT_DATA.get()) != null) {
            return off.get(PrinterContent.BLUEPRINT_DATA.get());
        }
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(PrinterContent.BLUEPRINT.get()) && st.get(PrinterContent.BLUEPRINT_DATA.get()) != null) {
                return st.get(PrinterContent.BLUEPRINT_DATA.get());
            }
        }
        return null;
    }

    private void scanNearbyReactor() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        int limit = Math.min(PrinterLayout.LIMIT, ClientRuntimeConfig.config().maxSize());
        var res = ReactorWorldScanner.scanNearPlayer(mc.level, mc.player, limit);
        if (res.isEmpty()) {
            WB.blueprintStatus = tr("blueprint_scan_failed");
            WB.blueprintStatusError = true;
            return;
        }
        var scanned = res.get();
        String name = "Reactor " + scanned.layout().width() + "x" + scanned.layout().length() + "x" + scanned.layout().height();
        WB.loadBlueprint(name, scanned.layout());
        WB.blueprintStatus = tr("blueprint_scanned_short",
                scanned.layout().width(), scanned.layout().length(), scanned.layout().height());
        WB.blueprintStatusError = false;
    }

    private static List<String> layoutErrors() {
        return WB.layout.validationErrors(Math.min(PrinterLayout.LIMIT, ClientRuntimeConfig.config().maxSize()));
    }

    private static boolean isCreative() {
        var player = Minecraft.getInstance().player;
        return player != null && player.isCreative();
    }

    private static int countPlayerPaper() {
        var player = Minecraft.getInstance().player;
        if (player == null) return 0;
        int total = 0;
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            var stack = inv.getItem(i);
            if (stack.is(Items.PAPER)) total += stack.getCount();
        }
        var offhand = inv.getItem(Inventory.SLOT_OFFHAND);
        if (offhand.is(Items.PAPER)) total += offhand.getCount();
        return total;
    }

    private void openBlueprintBench() {
        var errors = layoutErrors();
        if (!errors.isEmpty()) {
            WB.blueprintStatus = Component.translatable(errors.getFirst());
            WB.blueprintStatusError = true;
            return;
        }
        ClientPacketDistributor.sendToServer(new OpenBlueprintBenchRequest(
                PrinterLayout.from(WB.layout), ProjectionController.lockedAnchor(), WB.blueprintName));
    }

    private void resize(int dx, int dz) {
        WB.layout.resize(WB.layout.width() + dx, WB.layout.length() + dz, ClientRuntimeConfig.config().maxSize());
        WB.edited();
    }

    private void height(int dy) {
        WB.layout.setHeight(WB.layout.height() + dy, ClientRuntimeConfig.config().maxSize());
        WB.edited();
    }

    private void layer(int delta) { WB.selectedLayer = Math.floorMod(WB.selectedLayer + delta, WB.layout.height()); }
    private void ports(int delta) { WB.layout.setEnergyPorts(WB.layout.energyPorts() + delta); WB.edited(); }
    private void fuel(int delta) { WB.selectedFuel += delta; WB.edited(); }
    private void coolant(int delta) { WB.selectedCoolant += delta; WB.edited(); }

    private void text(GuiGraphicsExtractor g, int x, int y, int width, Component label, int color) {
        textViewport.line(g, Minecraft.getInstance().font, label, x, y, width, color, false);
    }

    private void paragraph(GuiGraphicsExtractor g, int x, int y, int width, int rows, Component label, int color) {
        textViewport.paragraph(g, Minecraft.getInstance().font, label, x, y, width, rows, color);
    }

    private static boolean grid(double x, double y) { return x >= GX && x < GX + GRID * CELL && y >= GY && y < GY + GRID * CELL; }
    private static boolean inPartSlot(double x, double y) { return x >= PART_SLOT_X && x < PART_SLOT_X + 18 && y >= PART_SLOT_Y && y < PART_SLOT_Y + 18; }
    private static boolean inPreview(double x, double y) { return x >= PREVIEW_X && x < PREVIEW_X + PREVIEW_W && y >= PREVIEW_Y && y < PREVIEW_Y + PREVIEW_H; }
    private static boolean list(double x, double y) { return WB.blocksMode && x >= 270 && x < 414 && y >= LIST_TOP && y < LIST_TOP + LIST_HEIGHT; }

    @Override
    public void getTooltip(ITooltipBuilder tooltip, double mx, double my) {
        double x = mx / scale, y = my / scale;
        for (var button : buttons()) {
            if (!button.at(x, y)) continue;
            tooltip.add(button.tooltip());
            if (button.variableStep()) tooltip.add(tr("control.step"));
            return;
        }
        if (inPartSlot(x, y)) {
            var selected = WB.selected().block();
            tooltip.add(selected == null ? tr("empty") : selected.getName());
            tooltip.add(tr("part_slot_tip").copy().withStyle(ChatFormatting.GRAY));
            return;
        }
        if (x >= 6 && x < 414 && y >= 14 && y < 24) {
            tooltip.add(tr("heat_legend_tip", ClientRuntimeConfig.config().maxHeat()));
            return;
        }
        if (grid(x, y)) {
            int cx = WB.gridOffsetX + (int) (x - GX) / CELL;
            int cz = WB.gridOffsetZ + (int) (y - GY) / CELL;
            if (cx < WB.layout.width() && cz < WB.layout.length()) {
                var part = WB.layout.get(cx, cz);
                Component name = part.block() == null ? tr("empty") : part.block().getName();
                tooltip.add(Component.empty().append(name)
                        .append(Component.literal(" (" + (cx + 1) + ", " + (cz + 1) + ")").withStyle(ChatFormatting.GRAY)));
                var sim = WB.simulation;
                if (sim != null) {
                    var cell = sim.cell(cx, cz);
                    if (cell != null) {
                        int maxHeat = Math.max(1, ClientRuntimeConfig.config().maxHeat());
                        int pct = Math.round(cell.heat * 100f / maxHeat);
                        ChatFormatting heatStyle = pct >= HOT_THRESHOLD ? ChatFormatting.RED
                                : pct >= WARM_THRESHOLD ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
                        String deltaStr = cell.lastHeatDelta >= 0 ? "+" + cell.lastHeatDelta : Integer.toString(cell.lastHeatDelta);
                        tooltip.add(tr("cell.heat", cell.heat, maxHeat, pct, deltaStr).copy().withStyle(heatStyle));
                        if (cell.part.kind() == ReactorParts.Kind.ROD) {
                            tooltip.add(tr("cell.rod", cell.pulses, sim.cellGeneration(cell), sim.cellHeatGeneration(cell))
                                    .copy().withStyle(ChatFormatting.AQUA));
                        } else if (cell.part.kind() == ReactorParts.Kind.VENT || cell.part.kind() == ReactorParts.Kind.ABSORBER || cell.part.kind() == ReactorParts.Kind.PIPE) {
                            tooltip.add(tr("cell.cooling", cell.lastCoolingWork).copy().withStyle(ChatFormatting.AQUA));
                            if (cell.part.kind() == ReactorParts.Kind.ABSORBER) {
                                tooltip.add(tr("cell.coolant_buf", cell.coolantRemaining()).copy().withStyle(ChatFormatting.GRAY));
                            }
                        }
                    }
                }
                tooltip.add(tr("grid_tip").copy().withStyle(ChatFormatting.DARK_GRAY));
            }
            return;
        }
        if (inPreview(x, y)) {
            tooltip.add(tr("preview_orbit_tip"));
            return;
        }
        if (!WB.blocksMode && WB.simulation != null && x >= 270 && x < 414 && y >= 198 && y < 226) {
            var sim = WB.simulation;
            tooltip.add(tr("economy.title").copy().withStyle(ChatFormatting.GOLD));
            tooltip.add(tr("economy.fuel_detail",
                    ReactorSimulation.formatRate(sim.fuelPerMinute()),
                    ReactorSimulation.formatRate(sim.fuelPerHour()),
                    ReactorSimulation.formatDuration(sim.fuelStackRuntimeSeconds()))
                    .copy().withStyle(ChatFormatting.WHITE));
            tooltip.add(tr("economy.fuel_yield", ReactorSimulation.formatEnergy(sim.rfPerFuelItem()))
                    .copy().withStyle(ChatFormatting.AQUA));
            if (sim.coolantPerSecond() > 0.0) {
                tooltip.add(tr("economy.coolant_detail",
                        ReactorSimulation.formatRate(sim.coolantPerMinute()),
                        ReactorSimulation.formatRate(sim.coolantPerHour()),
                        ReactorSimulation.formatDuration(sim.coolantStackRuntimeSeconds()))
                        .copy().withStyle(ChatFormatting.AQUA));
            } else {
                tooltip.add(tr("economy.coolant_passive").copy().withStyle(ChatFormatting.GREEN));
            }
            return;
        }
        if (x >= 288 && x < 394 && y >= 48 && y < 63 && !WB.inputs.fuels().isEmpty()) {
            tooltip.add(WB.inputs.fuels().get(Math.floorMod(WB.selectedFuel, WB.inputs.fuels().size())).item().getHoverName());
            return;
        }
        if (x >= 288 && x < 394 && y >= 95 && y < 110 && !WB.inputs.coolants().isEmpty()) {
            tooltip.add(WB.inputs.coolants().get(Math.floorMod(WB.selectedCoolant, WB.inputs.coolants().size())).getHoverName());
            return;
        }
        if (x >= GX && x < 102 && y >= 128 && y < 138 && WB.selected().block() != null) {
            tooltip.add(WB.selected().block().getName());
            return;
        }
        if (x >= 110 && x < 258 && y >= 189 && y < 208 && WB.designStatus != null) {
            tooltip.add(WB.designStatus);
            return;
        }
        if (list(x, y) && x < 406) {
            var entries = blockList();
            int localRow = ((int) y - LIST_TOP) / 11;
            int row = WB.blockOffset + localRow;
            if (localRow >= 0 && localRow < LIST_ROWS && row < entries.size())
                tooltip.add(BuiltInRegistries.BLOCK.getValue(Identifier.parse(entries.get(row).getKey())).getName());
        }
    }

    private boolean click(double x, double y, int button) {
        if (inPartSlot(x, y) && (button == 0 || button == 1)) {
            WB.cyclePart(button == 1 ? -1 : 1);
            return true;
        }
        if (inPreview(x, y)) {
            if (button == 1) WB.resetPreviewView();
            return true;
        }
        if (button != 0) return false;
        if (list(x, y) && x >= 406) {
            scrollBar(y);
            return true;
        }
        for (var control : buttons()) {
            if (control.at(x, y)) {
                if (control.enabled()) control.action().run();
                return true;
            }
        }
        return false;
    }

    private boolean scroll(double x, double y, double dx, double dy) {
        if (inPartSlot(x, y) && dy != 0) {
            WB.cyclePart(dy < 0 ? 1 : -1);
            return true;
        }
        if (inPreview(x, y) && dy != 0) {
            WB.previewZoom = AssemblyPreview.clampZoom(WB.previewZoom * (dy > 0 ? 1.15f : 1f / 1.15f));
            return true;
        }
        if (grid(x, y)) return panGrid(dx, dy);
        if (list(x, y) && dy != 0) {
            int delta = dy < 0 ? 1 : -1;
            WB.blockOffset = Math.clamp(WB.blockOffset + delta, 0, Math.max(0, blockList().size() - LIST_ROWS));
            return true;
        }
        return false;
    }

    private static boolean panGrid(double dx, double dy) {
        if (dx == 0 && dy == 0) return false;
        boolean horizontalWheel = Math.abs(dx) > Math.abs(dy);
        int delta = (horizontalWheel ? dx : dy) < 0 ? 1 : -1;
        if (horizontalWheel || Minecraft.getInstance().hasShiftDown())
            WB.gridOffsetX = Math.clamp(WB.gridOffsetX + delta, 0, Math.max(0, WB.layout.width() - GRID));
        else WB.gridOffsetZ = Math.clamp(WB.gridOffsetZ + delta, 0, Math.max(0, WB.layout.length() - GRID));
        return true;
    }

    @Override public void tick() { WB.tick(); }
}
