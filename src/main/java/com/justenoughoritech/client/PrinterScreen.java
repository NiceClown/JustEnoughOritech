package com.justenoughoritech.client;

import com.justenoughoritech.compat.jei.JEIPlugin;
import com.justenoughoritech.printer.BlueprintData;
import com.justenoughoritech.printer.PrinterContent;
import com.justenoughoritech.printer.PrinterControlRequest;
import com.justenoughoritech.printer.PrinterEntity;
import com.justenoughoritech.printer.PrinterMenu;
import com.justenoughoritech.printer.PrinterReport;
import com.justenoughoritech.sim.ReactorLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.ArrayList;
import java.util.List;

import static com.justenoughoritech.client.SimulatorWidget.tr;

/** Oritech-styled 3D Reactor Printer screen with full placement controls, redstone/dismantle modes, and JEI lookup. */
final class PrinterScreen extends AbstractContainerScreen<PrinterMenu> {
    private static final int MODEL_X = 186, MODEL_Y = 24, MODEL_W = 154, MODEL_H = 102;
    private static final int MATERIALS_X = MODEL_X, MATERIALS_Y = 178, MATERIALS_W = MODEL_W, ROWS = 6;
    private static final int HELP_X = 328, HELP_Y = 4, HELP_SIZE = 12;
    private static final Direction[] FACE_ORDER = {
            Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN
    };

    private final TextViewport textViewport = new TextViewport();
    private final List<TipArea> tips = new ArrayList<>();
    private final OritechButton[] moveButtons = new OritechButton[6];
    private OritechButton facePrev, faceNext, rotLeft, rotRight, flipX, flipZ, resetBtn;
    private OritechButton redstoneBtn, modeBtn, previewBtn, aimBtn;
    private int offset;
    private ReactorLayout previewLayout;
    private Rotation previewRot;
    private Mirror previewMirror;
    private List<ReactorLayout.Placement> shell = List.of();
    private final org.joml.Quaternionf orientation = AssemblyPreview.defaultOrientation();
    private float zoom = 1.0f;
    private boolean rotating;

    private record TipArea(int x, int y, int w, int h, Component tip, boolean stepHint) {
        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    PrinterScreen(PrinterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 348, 264);
    }

    @Override
    protected void init() {
        super.init();
        rotating = false;
        tips.clear();

        facePrev = addBtn(9, 61, 16, 15, Component.literal("<"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.face"), false,
                () -> sendControl(PrinterControlRequest.setFace(menu.blockPos(), cycleFace(-1))));
        faceNext = addBtn(163, 61, 16, 15, Component.literal(">"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.face"), false,
                () -> sendControl(PrinterControlRequest.setFace(menu.blockPos(), cycleFace(1))));

        rotLeft = addBtn(9, 79, 41, 15, Component.literal("↺ 90"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.rotate_ccw"), false,
                () -> sendControl(PrinterControlRequest.rotate(menu.blockPos(), -1)));
        rotRight = addBtn(52, 79, 41, 15, Component.literal("↻ 90"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.rotate_cw"), false,
                () -> sendControl(PrinterControlRequest.rotate(menu.blockPos(), 1)));
        flipX = addBtn(95, 79, 41, 15, tr("printer_flip_x"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.flip_x"), false,
                () -> sendControl(PrinterControlRequest.simple(menu.blockPos(), PrinterControlRequest.Action.FLIP_X)));
        flipZ = addBtn(138, 79, 41, 15, tr("printer_flip_z"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.flip_z"), false,
                () -> sendControl(PrinterControlRequest.simple(menu.blockPos(), PrinterControlRequest.Action.FLIP_Z)));

        moveButtons[0] = addBtn(47, 97, 19, 15, Component.literal("-"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.move_x"), true,
                () -> sendMove(-1, 0, 0));
        moveButtons[1] = addBtn(68, 97, 19, 15, Component.literal("+"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.move_x"), true,
                () -> sendMove(1, 0, 0));
        moveButtons[2] = addBtn(131, 97, 19, 15, Component.literal("-"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.move_y"), true,
                () -> sendMove(0, -1, 0));
        moveButtons[3] = addBtn(152, 97, 19, 15, Component.literal("+"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.move_y"), true,
                () -> sendMove(0, 1, 0));
        moveButtons[4] = addBtn(47, 115, 19, 15, Component.literal("-"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.move_z"), true,
                () -> sendMove(0, 0, -1));
        moveButtons[5] = addBtn(68, 115, 19, 15, Component.literal("+"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.move_z"), true,
                () -> sendMove(0, 0, 1));

        resetBtn = addBtn(94, 115, 85, 15, tr("printer_reset_pos"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.reset"), false, () -> {
            var action = menu.started()
                    ? PrinterControlRequest.Action.RESET_PROGRESS
                    : PrinterControlRequest.Action.RESET_POSITION;
            sendControl(PrinterControlRequest.simple(menu.blockPos(), action));
        });

        redstoneBtn = addBtn(9, 133, 83, 15, tr("printer_rs.high"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.redstone"), false,
                () -> sendControl(PrinterControlRequest.simple(menu.blockPos(), PrinterControlRequest.Action.CYCLE_REDSTONE)));
        modeBtn = addBtn(94, 133, 85, 15, tr("printer_mode.build"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.mode"), false,
                () -> sendControl(PrinterControlRequest.simple(menu.blockPos(), PrinterControlRequest.Action.TOGGLE_MODE)));

        previewBtn = addBtn(9, 151, 83, 16, tr("printer_preview_short"), OritechTheme.ButtonStyle.DARK, tr("printer_tip.preview"), false, () -> {
            var printer = menu.printer();
            if (printer != null && printer.layout() != null) {
                ProjectionController.togglePrinterPreview(printer);
            }
        });
        aimBtn = addBtn(94, 151, 85, 16, tr("printer_aim"), OritechTheme.ButtonStyle.ORANGE, tr("printer_tip.aim"), false, () -> {
            var printer = menu.printer();
            if (printer != null && printer.layout() != null) {
                onClose();
                ProjectionController.beginPrinterAim(printer);
            }
        });
        updateButtonStates();
    }

    private OritechButton addBtn(int x, int y, int w, int h, Component label, OritechTheme.ButtonStyle style,
                                 Component tip, boolean stepHint, Runnable action) {
        tips.add(new TipArea(x, y, w, h, tip, stepHint));
        return addRenderableWidget(new OritechButton(leftPos + x, topPos + y, w, h, label, style, null, b -> action.run()));
    }

    private Direction cycleFace(int delta) {
        var current = menu.attachFace();
        int idx = 0;
        for (int i = 0; i < FACE_ORDER.length; i++) {
            if (FACE_ORDER[i] == current) {
                idx = i;
                break;
            }
        }
        return FACE_ORDER[Math.floorMod(idx + delta, FACE_ORDER.length)];
    }

    private void sendMove(int sx, int sy, int sz) {
        int step = stepSize();
        sendControl(PrinterControlRequest.move(menu.blockPos(), sx * step, sy * step, sz * step));
    }

    private static int stepSize() {
        var mc = Minecraft.getInstance();
        return mc.hasControlDown() ? 50 : mc.hasShiftDown() ? 10 : 1;
    }

    private static void sendControl(PrinterControlRequest req) {
        ClientPacketDistributor.sendToServer(req);
    }

    @Override
    protected void containerTick() {
        updateButtonStates();
    }

    private void updateButtonStates() {
        boolean edit = menu.canEditProjection();
        facePrev.active = edit;
        faceNext.active = edit;
        rotLeft.active = edit;
        rotRight.active = edit;
        flipX.active = edit;
        flipZ.active = edit;
        for (var button : moveButtons) button.active = edit;
        aimBtn.active = edit;

        boolean canControl = menu.canCancel();
        resetBtn.active = canControl;
        resetBtn.setMessage(tr(menu.started() ? "printer_restart" : "printer_reset_pos"));
        resetBtn.setStyle(menu.started() ? OritechTheme.ButtonStyle.RED : OritechTheme.ButtonStyle.DARK);

        var rs = menu.redstoneMode();
        redstoneBtn.active = canControl;
        redstoneBtn.setMessage(tr("printer_rs." + switch (rs) {
            case LOW -> "low";
            case ALWAYS -> "always";
            case HIGH -> "high";
        }));
        redstoneBtn.setStyle(rs == PrinterControlRequest.RedstoneMode.ALWAYS
                ? OritechTheme.ButtonStyle.GREEN
                : rs == PrinterControlRequest.RedstoneMode.LOW ? OritechTheme.ButtonStyle.ORANGE : OritechTheme.ButtonStyle.DARK);

        var mode = menu.operationMode();
        modeBtn.active = canControl;
        boolean deconstruct = mode == PrinterControlRequest.OperationMode.DECONSTRUCT;
        modeBtn.setMessage(tr("printer_mode." + (deconstruct ? "deconstruct" : "build")));
        modeBtn.setStyle(deconstruct ? OritechTheme.ButtonStyle.RED : OritechTheme.ButtonStyle.DARK);

        var printer = menu.printer();
        boolean hasLayout = printer != null && printer.layout() != null;
        boolean previewing = ProjectionController.isPreviewingPrinter(printer);
        previewBtn.active = hasLayout;
        previewBtn.setMessage(tr(previewing ? "printer_preview_hide" : "printer_preview_short"));
        previewBtn.setStyle(previewing ? OritechTheme.ButtonStyle.GREEN : OritechTheme.ButtonStyle.DARK);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        OritechTheme.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        OritechTheme.headerBar(graphics, leftPos + 1, topPos + 1, imageWidth - 2, 19);
        graphics.fill(leftPos + 182, topPos + 21, leftPos + 183, topPos + imageHeight - 4, 0xFF8B8E94);

        for (var slot : menu.slots) {
            OritechTheme.slot(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
        }
        if (!menu.getSlot(0).hasItem()) {
            var slot = menu.getSlot(0);
            graphics.fakeItem(new ItemStack(PrinterContent.BLUEPRINT.get()), leftPos + slot.x, topPos + slot.y);
            graphics.fill(leftPos + slot.x, topPos + slot.y, leftPos + slot.x + 16, topPos + slot.y + 16, 0xA0252A30);
        }

        // Attach side inset well
        OritechTheme.inset(graphics, leftPos + 27, topPos + 61, 134, 15);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        var printer = menu.printer();
        String bpName = printer == null ? "" : printer.blueprintName();
        line(graphics, title, 8, 6, 170, OritechTheme.TEXT_LIGHT);
        Component modelHeader = bpName.isBlank() ? tr("printer_model") : Component.literal(bpName);
        line(graphics, modelHeader, MODEL_X, 6, HELP_X - MODEL_X - 4,
                bpName.isBlank() ? OritechTheme.TEXT_LIGHT : OritechTheme.ORANGE_BRIGHT);

        OritechTheme.button(graphics, HELP_X, HELP_Y, HELP_SIZE, HELP_SIZE, OritechTheme.ButtonStyle.DARK, false, true);
        line(graphics, Component.literal("?"), HELP_X + 3, HELP_Y + 2, 8, OritechTheme.TEXT_LIGHT);

        int state = menu.state();
        int stateColor = state == 5 ? OritechTheme.STATUS_OK_DARK
                : (state == 3 || state == 4 || state == 6 || state == 7) ? OritechTheme.STATUS_ALERT_DARK
                : OritechTheme.TEXT_DARK;
        line(graphics, tr("printer_state." + state), 31, 25, 148, stateColor);

        if (printer != null && printer.layout() != null) {
            var layout = printer.layout();
            line(graphics, tr("printer_summary", layout.width(), layout.length(), layout.height(), printer.placements().size()),
                    31, 35, 148, OritechTheme.TEXT_DARK_MUTED);
        } else {
            line(graphics, tr("printer_insert_bp"), 31, 35, 148, OritechTheme.TEXT_DARK_MUTED);
        }

        int total = menu.totalPlacements();
        int done = menu.progress();
        float ratio = menu.hasJob() && total > 0 ? Math.clamp(done / (float) total, 0f, 1f) : 0f;
        int barColor = state == 5 ? 0xFF3C8527
                : menu.operationMode() == PrinterControlRequest.OperationMode.DECONSTRUCT
                ? OritechTheme.ORANGE_BRIGHT : OritechTheme.CYAN_DEEP;
        OritechTheme.progressBar(graphics, 9, 47, 170, 11, ratio, barColor);
        Component progressText = menu.hasJob() && total > 0
                ? Component.literal(done + " / " + total + " (" + Math.round(ratio * 100f) + "%)")
                : Component.literal("0 / 0");
        textViewport.line(graphics, font, progressText, 12, 49, 164, OritechTheme.TEXT_LIGHT, true);

        Component faceLabel = !menu.hasJob()
                ? Component.literal("—")
                : menu.anchored()
                ? tr("printer_face_anchored")
                : tr("printer_face_value", tr("face." + menu.attachFace().getName()));
        line(graphics, faceLabel, 31, 65, 126, menu.anchored() ? OritechTheme.STATUS_OK : OritechTheme.TEXT_LIGHT);

        line(graphics, Component.literal("X: " + menu.offset(0)), 10, 101, 36, OritechTheme.TEXT_DARK);
        line(graphics, Component.literal("Y: " + menu.offset(1)), 94, 101, 36, OritechTheme.TEXT_DARK);
        line(graphics, Component.literal("Z: " + menu.offset(2)), 10, 119, 36, OritechTheme.TEXT_DARK);
        line(graphics, playerInventoryTitle, 12, 170, 162, OritechTheme.TEXT_DARK_MUTED);

        drawModel(graphics, printer);
        if (printer != null && printer.layout() != null) {
            drawPositionAndReport(graphics, printer, mouseX, mouseY);
        } else {
            line(graphics, tr("printer_materials_heading"), MATERIALS_X, 166, MATERIALS_W, OritechTheme.TEXT_DARK);
            OritechTheme.inset(graphics, MATERIALS_X, MATERIALS_Y, MATERIALS_W, ROWS * 12 + 4);
            textViewport.paragraph(graphics, font, tr("printer_no_job_hint"), MATERIALS_X + 6, MATERIALS_Y + 6,
                    MATERIALS_W - 12, 5, OritechTheme.TEXT_MUTED);
        }
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        int x = mouseX - leftPos, y = mouseY - topPos;
        if (x >= HELP_X && x < HELP_X + HELP_SIZE && y >= HELP_Y && y < HELP_Y + HELP_SIZE) {
            graphics.setComponentTooltipForNextFrame(font, PrinterContent.instructions(), mouseX, mouseY);
            return;
        }
        if (x >= 9 && x < 27 && y >= 25 && y < 43 && !menu.getSlot(0).hasItem()) {
            graphics.setTooltipForNextFrame(font, tr("printer_slot_tip"), mouseX, mouseY);
            return;
        }
        for (var tip : tips) {
            if (tip.contains(x, y)) {
                var list = new ArrayList<Component>();
                list.add(tip.tip());
                if (tip.stepHint()) list.add(tr("control.step"));
                graphics.setComponentTooltipForNextFrame(font, list, mouseX, mouseY);
                return;
            }
        }
        int hoveredRow = hoveredMissingRow(mouseX, mouseY);
        if (hoveredRow >= 0) {
            var entry = missing().get(offset + hoveredRow);
            var itemName = new ItemStack(BuiltInRegistries.ITEM.getValue(entry.item())).getHoverName();
            graphics.setComponentTooltipForNextFrame(font, List.of(
                    itemName,
                    tr("printer_jei_lookup").copy().withStyle(net.minecraft.ChatFormatting.GOLD)
            ), mouseX, mouseY);
            return;
        }
        if (!rotating && overModel(mouseX, mouseY) && previewLayout != null) {
            graphics.setTooltipForNextFrame(font, tr("printer_model_drag"), mouseX, mouseY);
        }
    }

    private void drawModel(GuiGraphicsExtractor graphics, PrinterEntity printer) {
        OritechTheme.inset(graphics, MODEL_X, MODEL_Y, MODEL_W, MODEL_H);
        var layout = printer == null ? null : printer.layout();
        if (layout == null) {
            previewLayout = null;
            previewRot = null;
            previewMirror = null;
            shell = List.of();
            textViewport.paragraph(graphics, font, tr("printer_model_empty"), MODEL_X + 6, MODEL_Y + 14,
                    MODEL_W - 12, 4, OritechTheme.TEXT_MUTED);
            return;
        }
        var rot = menu.rotation();
        var mir = menu.mirror();
        int width = layout.buildWidth(rot), height = layout.height(), length = layout.buildLength(rot);
        if (layout != previewLayout || rot != previewRot || mir != previewMirror) {
            previewLayout = layout;
            previewRot = rot;
            previewMirror = mir;
            shell = layout.shellPlacements(rot, mir);
        }
        AssemblyPreview.draw(graphics, shell, width, height, length, -1, null, null,
                orientation, zoom, MODEL_X + 1, MODEL_Y + 1, MODEL_W - 2, MODEL_H - 14, false);
        var facing = layout.controllerFacing(rot, mir);
        Component badge = tr(mir == Mirror.NONE ? "printer_orient_badge" : "printer_orient_mirrored",
                BlueprintData.rotationDegrees(rot), tr("face." + facing.getName()));
        line(graphics, badge, MODEL_X + 4, MODEL_Y + MODEL_H - 11, MODEL_W - 8, OritechTheme.CYAN_BRIGHT);
    }

    private void drawPositionAndReport(GuiGraphicsExtractor graphics, PrinterEntity printer, int mouseX, int mouseY) {
        var anchor = printer.anchor();
        if (anchor != null) {
            line(graphics, tr("printer_origin", anchor.getX(), anchor.getY(), anchor.getZ()),
                    MODEL_X, 130, MODEL_W, OritechTheme.TEXT_DARK);
        }
        var report = printer.report();
        if (menu.operationMode() == PrinterControlRequest.OperationMode.DECONSTRUCT) {
            textViewport.paragraph(graphics, font, tr("printer_dismantle_hint"), MODEL_X, 141, MODEL_W, 2, OritechTheme.ORANGE_DEEP);
            line(graphics, tr("printer_materials_heading"), MATERIALS_X, 166, MATERIALS_W, OritechTheme.TEXT_DARK);
            OritechTheme.inset(graphics, MATERIALS_X, MATERIALS_Y, MATERIALS_W, ROWS * 12 + 4);
            textViewport.paragraph(graphics, font, tr("printer_dismantle_storage"), MATERIALS_X + 6, MATERIALS_Y + 6,
                    MATERIALS_W - 12, 4, OritechTheme.TEXT_MUTED);
            return;
        }
        if (report == null) {
            line(graphics, tr("printer_checking"), MODEL_X, 141, MODEL_W, OritechTheme.TEXT_DARK_MUTED);
            line(graphics, tr(menu.creativeJob() ? "printer_materials_heading" : "printer_missing_title"),
                    MATERIALS_X, 166, MATERIALS_W, OritechTheme.TEXT_DARK);
            OritechTheme.inset(graphics, MATERIALS_X, MATERIALS_Y, MATERIALS_W, ROWS * 12 + 4);
            return;
        }
        var blocked = report.obstruction();
        if (blocked != null) {
            textViewport.paragraph(graphics, font, tr("printer_obstruction", blocked.getX(), blocked.getY(), blocked.getZ()),
                    MODEL_X, 141, MODEL_W, 2, OritechTheme.STATUS_ALERT_DARK);
        } else {
            line(graphics, tr(report.unloaded() ? "printer_partial" : "printer_clear"),
                    MODEL_X, 141, MODEL_W, OritechTheme.STATUS_OK_DARK);
        }
        line(graphics, tr(menu.creativeJob() ? "printer_materials_heading"
                        : report.unloaded() ? "printer_missing_estimate" : "printer_missing_title"),
                MATERIALS_X, 166, MATERIALS_W, OritechTheme.TEXT_DARK);

        OritechTheme.inset(graphics, MATERIALS_X, MATERIALS_Y, MATERIALS_W, ROWS * 12 + 4);
        var missing = report.missing();
        if (menu.creativeJob()) {
            textViewport.paragraph(graphics, font, tr("printer_free_creative"), MATERIALS_X + 6, MATERIALS_Y + 6,
                    MATERIALS_W - 12, 3, OritechTheme.TEXT_MUTED);
            return;
        }
        if (missing.isEmpty()) {
            textViewport.paragraph(graphics, font, tr(report.unloaded() ? "printer_check_loaded" : "printer_materials_ready"),
                    MATERIALS_X + 6, MATERIALS_Y + 6, MATERIALS_W - 12, 3, OritechTheme.STATUS_OK);
            return;
        }
        int max = Math.max(0, missing.size() - ROWS);
        offset = Math.clamp(offset, 0, max);
        int hoveredRow = hoveredMissingRow(mouseX, mouseY);
        for (int row = 0; row < ROWS && offset + row < missing.size(); row++) {
            var entry = missing.get(offset + row);
            int y = MATERIALS_Y + 2 + row * 12;
            if (row == hoveredRow) {
                graphics.fill(MATERIALS_X + 2, y, MATERIALS_X + MATERIALS_W - 7, y + 11, 0x38D18B33);
            }
            String count = Integer.toString(entry.count());
            int countX = MATERIALS_X + MATERIALS_W - 10 - font.width(count);
            line(graphics, new ItemStack(BuiltInRegistries.ITEM.getValue(entry.item())).getHoverName(),
                    MATERIALS_X + 4, y + 1, countX - (MATERIALS_X + 7),
                    row == hoveredRow ? OritechTheme.ORANGE_BRIGHT : OritechTheme.TEXT_LIGHT);
            line(graphics, Component.literal(count), countX, y + 1, font.width(count), OritechTheme.STATUS_ALERT);
        }
        if (max > 0) {
            int x = MATERIALS_X + MATERIALS_W - 6, height = ROWS * 12;
            int thumb = Math.max(10, height * ROWS / missing.size());
            int y = MATERIALS_Y + 2 + (height - thumb) * offset / max;
            graphics.fill(x, MATERIALS_Y + 2, x + 4, MATERIALS_Y + 2 + height, 0xFF14161A);
            graphics.fill(x, y, x + 4, y + thumb, OritechTheme.ORANGE_BRIGHT);
        }
    }

    private int hoveredMissingRow(double mouseX, double mouseY) {
        if (!overMaterials(mouseX, mouseY) || mouseX >= leftPos + MATERIALS_X + MATERIALS_W - 7) return -1;
        var list = missing();
        if (list.isEmpty()) return -1;
        int row = ((int) mouseY - (topPos + MATERIALS_Y + 2)) / 12;
        if (row < 0 || row >= ROWS || offset + row >= list.size()) return -1;
        return row;
    }

    private List<PrinterReport.Missing> missing() {
        var printer = menu.printer();
        var report = printer == null ? null : printer.report();
        return report == null ? List.of() : report.missing();
    }

    private boolean overMaterials(double x, double y) {
        return x >= leftPos + MATERIALS_X && x < leftPos + MATERIALS_X + MATERIALS_W
                && y >= topPos + MATERIALS_Y + 2 && y < topPos + MATERIALS_Y + 2 + ROWS * 12;
    }

    private boolean overModel(double x, double y) {
        return x >= leftPos + MODEL_X && x < leftPos + MODEL_X + MODEL_W
                && y >= topPos + MODEL_Y && y < topPos + MODEL_Y + MODEL_H;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (previewLayout != null && overModel(x, y) && dy != 0) {
            zoom = AssemblyPreview.clampZoom(zoom * (dy > 0 ? 1.15f : 1f / 1.15f));
            return true;
        }
        if (overMaterials(x, y) && dy != 0) {
            offset = Math.clamp(offset + (dy < 0 ? 1 : -1), 0, Math.max(0, missing().size() - ROWS));
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x(), y = event.y();
        if (previewLayout != null && overModel(x, y)) {
            if (event.button() == 0) {
                rotating = true;
                return true;
            }
            if (event.button() == 1) {
                orientation.set(AssemblyPreview.defaultOrientation());
                zoom = 1.0f;
                return true;
            }
        }
        int max = Math.max(0, missing().size() - ROWS);
        if (event.button() == 0 && max > 0 && overMaterials(x, y) && x >= leftPos + MATERIALS_X + MATERIALS_W - 7) {
            offset = Math.clamp((int) Math.round((y - topPos - MATERIALS_Y - 2) * max / (ROWS * 12 - 1)), 0, max);
            return true;
        }
        if (event.button() == 0 && menu.operationMode() == PrinterControlRequest.OperationMode.BUILD && !menu.creativeJob()) {
            int row = hoveredMissingRow(x, y);
            if (row >= 0) {
                var item = BuiltInRegistries.ITEM.getValue(missing().get(offset + row).item());
                JEIPlugin.showRecipe(new ItemStack(item));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (rotating && event.button() == 0) {
            AssemblyPreview.orbit(orientation, dx, dy);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (rotating && event.button() == 0) {
            rotating = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    private void line(GuiGraphicsExtractor graphics, Component label, int x, int y, int width, int color) {
        textViewport.line(graphics, font, label, x, y, width, color, false);
    }
}
