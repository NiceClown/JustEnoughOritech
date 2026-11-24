package com.justenoughoritech.client;

import com.justenoughoritech.printer.BlueprintData;
import com.justenoughoritech.printer.PrinterContent;
import com.justenoughoritech.printer.PrinterControlRequest;
import com.justenoughoritech.printer.PrinterEntity;
import com.justenoughoritech.printer.PrinterMenu;
import com.justenoughoritech.printer.PrinterReport;
import com.justenoughoritech.sim.ReactorLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.justenoughoritech.client.SimulatorWidget.tr;

final class PrinterScreen extends AbstractContainerScreen<PrinterMenu> {
    private static final int MODEL_X = 180, MODEL_Y = 22, MODEL_W = 152, MODEL_H = 92;
    private static final int MATERIALS_X = MODEL_X, MATERIALS_Y = 160, MATERIALS_W = MODEL_W, ROWS = 6;
    private static final int HELP_X = 320, HELP_Y = 6, HELP_SIZE = 12;
    private static final int TEXT = 0xFFF3F6F9, RED = 0xFFFF8176, MUTED = 0xFFB8C4D0, OK = 0xFF89D39D;
    private static final Direction[] FACE_ORDER = {
            Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN
    };
    private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");

    private final TextViewport textViewport = new TextViewport();
    private final List<TipArea> tips = new ArrayList<>();
    private final Button[] moveButtons = new Button[6];
    private Button facePrev, faceNext, rotLeft, rotRight, flipX, flipZ, resetBtn, previewBtn, aimBtn;
    private int offset;
    private ReactorLayout previewLayout;
    private Rotation previewRot;
    private Mirror previewMirror;
    private List<ReactorLayout.Placement> shell = List.of();
    private float pitch = AssemblyPreview.DEFAULT_PITCH, yaw = AssemblyPreview.DEFAULT_YAW;
    private boolean rotating;

    private record TipArea(int x, int y, int w, int h, Component tip, boolean stepHint) {
        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    PrinterScreen(PrinterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 340, 244);
    }

    @Override
    protected void init() {
        super.init();
        rotating = false;
        tips.clear();

        facePrev = addBtn(8, 48, 16, 15, Component.literal("<"), tr("printer_tip.face"), false,
                () -> sendControl(PrinterControlRequest.setFace(menu.blockPos(), cycleFace(-1))));
        faceNext = addBtn(154, 48, 16, 15, Component.literal(">"), tr("printer_tip.face"), false,
                () -> sendControl(PrinterControlRequest.setFace(menu.blockPos(), cycleFace(1))));

        rotLeft = addBtn(8, 66, 38, 15, Component.literal("↺ 90"), tr("printer_tip.rotate_ccw"), false,
                () -> sendControl(PrinterControlRequest.rotate(menu.blockPos(), -1)));
        rotRight = addBtn(49, 66, 38, 15, Component.literal("↻ 90"), tr("printer_tip.rotate_cw"), false,
                () -> sendControl(PrinterControlRequest.rotate(menu.blockPos(), 1)));
        flipX = addBtn(90, 66, 39, 15, tr("printer_flip_x"), tr("printer_tip.flip_x"), false,
                () -> sendControl(PrinterControlRequest.simple(menu.blockPos(), PrinterControlRequest.Action.FLIP_X)));
        flipZ = addBtn(132, 66, 38, 15, tr("printer_flip_z"), tr("printer_tip.flip_z"), false,
                () -> sendControl(PrinterControlRequest.simple(menu.blockPos(), PrinterControlRequest.Action.FLIP_Z)));

        moveButtons[0] = addBtn(47, 84, 19, 15, Component.literal("-"), tr("printer_tip.move_x"), true,
                () -> sendMove(-1, 0, 0));
        moveButtons[1] = addBtn(68, 84, 19, 15, Component.literal("+"), tr("printer_tip.move_x"), true,
                () -> sendMove(1, 0, 0));
        moveButtons[2] = addBtn(130, 84, 19, 15, Component.literal("-"), tr("printer_tip.move_y"), true,
                () -> sendMove(0, -1, 0));
        moveButtons[3] = addBtn(151, 84, 19, 15, Component.literal("+"), tr("printer_tip.move_y"), true,
                () -> sendMove(0, 1, 0));
        moveButtons[4] = addBtn(47, 102, 19, 15, Component.literal("-"), tr("printer_tip.move_z"), true,
                () -> sendMove(0, 0, -1));
        moveButtons[5] = addBtn(68, 102, 19, 15, Component.literal("+"), tr("printer_tip.move_z"), true,
                () -> sendMove(0, 0, 1));

        resetBtn = addBtn(91, 102, 79, 15, tr("printer_reset_pos"), tr("printer_tip.reset"), false, () -> {
            var action = menu.started()
                    ? PrinterControlRequest.Action.RESET_PROGRESS
                    : PrinterControlRequest.Action.RESET_POSITION;
            sendControl(PrinterControlRequest.simple(menu.blockPos(), action));
        });

        previewBtn = addBtn(8, 121, 80, 17, tr("printer_preview_short"), tr("printer_tip.preview"), false, () -> {
            var printer = menu.printer();
            if (printer != null && printer.layout() != null) {
                ProjectionController.togglePrinterPreview(printer);
            }
        });
        aimBtn = addBtn(91, 121, 79, 17, tr("printer_aim"), tr("printer_tip.aim"), false, () -> {
            var printer = menu.printer();
            if (printer != null && printer.layout() != null) {
                onClose();
                ProjectionController.beginPrinterAim(printer);
            }
        });
        updateButtonStates();
    }

    private Button addBtn(int x, int y, int w, int h, Component label, Component tip, boolean stepHint, Runnable action) {
        tips.add(new TipArea(x, y, w, h, tip, stepHint));
        return addRenderableWidget(Button.builder(label, b -> action.run())
                .bounds(leftPos + x, topPos + y, w, h).build());
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
        resetBtn.active = menu.canCancel();
        resetBtn.setMessage(tr(menu.started() ? "printer_restart" : "printer_reset_pos"));
        var printer = menu.printer();
        boolean hasLayout = printer != null && printer.layout() != null;
        previewBtn.active = hasLayout;
        previewBtn.setMessage(tr(ProjectionController.isPreviewingPrinter(printer)
                ? "printer_preview_hide" : "printer_preview_short"));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF293039);
        graphics.outline(leftPos, topPos, imageWidth, imageHeight, 0xFF768494);
        graphics.fill(leftPos + 175, topPos + 6, leftPos + 176, topPos + imageHeight - 6, 0xFF3E4A56);
        for (var slot : menu.slots) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE,
                    leftPos + slot.x - 1, topPos + slot.y - 1, 18, 18);
        }
        if (!menu.getSlot(0).hasItem()) {
            var slot = menu.getSlot(0);
            graphics.fakeItem(new ItemStack(PrinterContent.BLUEPRINT.get()), leftPos + slot.x, topPos + slot.y);
            graphics.fill(leftPos + slot.x, topPos + slot.y, leftPos + slot.x + 16, topPos + slot.y + 16, 0xB8232930);
        }
        graphics.fill(leftPos + 26, topPos + 48, leftPos + 152, topPos + 63, 0xFF1B2127);
        graphics.outline(leftPos + 26, topPos + 48, 126, 15, 0xFF44515D);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        line(graphics, title, 8, 7, 162, TEXT);
        line(graphics, tr("printer_model"), MODEL_X, 7, HELP_X - MODEL_X - 4, TEXT);
        graphics.outline(HELP_X, HELP_Y, HELP_SIZE, HELP_SIZE, 0xFF8594A4);
        line(graphics, Component.literal("?"), HELP_X + 3, HELP_Y + 2, 8, TEXT);

        int state = menu.state();
        int stateColor = state == 5 ? OK : (state == 3 || state == 4 || state == 6) ? RED : TEXT;
        line(graphics, tr("printer_state." + state), 30, 24, 140, stateColor);

        var printer = menu.printer();
        if (printer != null && printer.layout() != null) {
            var layout = printer.layout();
            line(graphics, tr("printer_summary", layout.width(), layout.length(), layout.height(), printer.placements().size()),
                    30, 35, 140, MUTED);
        } else {
            line(graphics, tr("printer_insert_bp"), 30, 35, 140, MUTED);
        }

        Component faceLabel = !menu.hasJob()
                ? Component.literal("—")
                : menu.anchored()
                ? tr("printer_face_anchored")
                : tr("printer_face_value", tr("face." + menu.attachFace().getName()));
        line(graphics, faceLabel, 30, 52, 118, menu.anchored() ? OK : TEXT);

        line(graphics, Component.literal("X:" + menu.offset(0)), 10, 88, 35, TEXT);
        line(graphics, Component.literal("Y:" + menu.offset(1)), 93, 88, 35, TEXT);
        line(graphics, Component.literal("Z:" + menu.offset(2)), 10, 106, 35, TEXT);
        line(graphics, playerInventoryTitle, 8, 148, 162, MUTED);

        drawModel(graphics, printer);
        if (printer != null && printer.layout() != null) {
            drawPositionAndReport(graphics, printer);
        } else {
            line(graphics, tr("printer_materials_heading"), MATERIALS_X, 148, MATERIALS_W, TEXT);
            textViewport.paragraph(graphics, font, tr("printer_no_job_hint"), MATERIALS_X, MATERIALS_Y, MATERIALS_W, 4, MUTED);
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
        if (x >= 8 && x < 26 && y >= 24 && y < 42 && !menu.getSlot(0).hasItem()) {
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
        if (!rotating && overModel(mouseX, mouseY) && previewLayout != null) {
            graphics.setTooltipForNextFrame(font, tr("printer_model_drag"), mouseX, mouseY);
        }
    }

    private void drawModel(GuiGraphicsExtractor graphics, PrinterEntity printer) {
        graphics.fill(MODEL_X, MODEL_Y, MODEL_X + MODEL_W, MODEL_Y + MODEL_H, 0xFF1B2127);
        graphics.outline(MODEL_X, MODEL_Y, MODEL_W, MODEL_H, 0xFF44515D);
        var layout = printer == null ? null : printer.layout();
        if (layout == null) {
            previewLayout = null;
            previewRot = null;
            previewMirror = null;
            shell = List.of();
            textViewport.paragraph(graphics, font, tr("printer_model_empty"), MODEL_X + 6, MODEL_Y + 14, MODEL_W - 12, 4, MUTED);
            return;
        }
        var rot = menu.rotation();
        var mir = menu.mirror();
        int width = layout.buildWidth(rot), height = layout.height(), length = layout.buildLength(rot);
        if (layout != previewLayout || rot != previewRot || mir != previewMirror) {
            previewLayout = layout;
            previewRot = rot;
            previewMirror = mir;
            shell = AssemblyPreview.shell(layout.placements(rot, mir), width, height, length);
        }
        AssemblyPreview.draw(graphics, shell, width, height, length, -1, null, null,
                pitch, yaw, MODEL_X, MODEL_Y, MODEL_W, MODEL_H - 12);
        var facing = layout.controllerFacing(rot, mir);
        Component badge = tr(mir == Mirror.NONE ? "printer_orient_badge" : "printer_orient_mirrored",
                BlueprintData.rotationDegrees(rot), tr("face." + facing.getName()));
        line(graphics, badge, MODEL_X + 4, MODEL_Y + MODEL_H - 11, MODEL_W - 8, MUTED);
    }

    private void drawPositionAndReport(GuiGraphicsExtractor graphics, PrinterEntity printer) {
        var anchor = printer.anchor();
        if (anchor != null) {
            line(graphics, tr("printer_origin", anchor.getX(), anchor.getY(), anchor.getZ()), 180, 118, MODEL_W, TEXT);
        }
        var report = printer.report();
        if (report == null) {
            line(graphics, tr("printer_checking"), 180, 129, MODEL_W, MUTED);
            line(graphics, tr(menu.creativeJob() ? "printer_materials_heading" : "printer_missing_title"),
                    MATERIALS_X, 148, MATERIALS_W, TEXT);
            return;
        }
        var blocked = report.obstruction();
        if (blocked != null) {
            textViewport.paragraph(graphics, font, tr("printer_obstruction", blocked.getX(), blocked.getY(), blocked.getZ()),
                    180, 129, MODEL_W, 2, RED);
        } else {
            line(graphics, tr(report.unloaded() ? "printer_partial" : "printer_clear"), 180, 129, MODEL_W, OK);
        }
        line(graphics, tr(menu.creativeJob() ? "printer_materials_heading"
                        : report.unloaded() ? "printer_missing_estimate" : "printer_missing_title"),
                MATERIALS_X, 148, MATERIALS_W, TEXT);

        var missing = report.missing();
        if (menu.creativeJob()) {
            textViewport.paragraph(graphics, font, tr("printer_free_creative"), MATERIALS_X, MATERIALS_Y, MATERIALS_W, 3, MUTED);
            return;
        }
        if (missing.isEmpty()) {
            textViewport.paragraph(graphics, font, tr(report.unloaded() ? "printer_check_loaded" : "printer_materials_ready"),
                    MATERIALS_X, MATERIALS_Y, MATERIALS_W, 3, OK);
            return;
        }
        int max = Math.max(0, missing.size() - ROWS);
        offset = Math.clamp(offset, 0, max);
        graphics.fill(MATERIALS_X, MATERIALS_Y, MATERIALS_X + MATERIALS_W, MATERIALS_Y + ROWS * 12, 0xFF1B2127);
        for (int row = 0; row < ROWS && offset + row < missing.size(); row++) {
            var entry = missing.get(offset + row);
            int y = MATERIALS_Y + 2 + row * 12;
            String count = Integer.toString(entry.count());
            int countX = MATERIALS_X + MATERIALS_W - 9 - font.width(count);
            line(graphics, new ItemStack(BuiltInRegistries.ITEM.getValue(entry.item())).getHoverName(),
                    MATERIALS_X + 3, y, countX - (MATERIALS_X + 6), TEXT);
            line(graphics, Component.literal(count), countX, y, font.width(count), RED);
        }
        if (max > 0) {
            int x = MATERIALS_X + MATERIALS_W - 5, height = ROWS * 12;
            int thumb = Math.max(10, height * ROWS / missing.size());
            int y = MATERIALS_Y + (height - thumb) * offset / max;
            graphics.fill(x, MATERIALS_Y, x + 4, MATERIALS_Y + height, 0xFF101417);
            graphics.fill(x, y, x + 4, y + thumb, 0xFF8594A4);
        }
    }

    private List<PrinterReport.Missing> missing() {
        var printer = menu.printer();
        var report = printer == null ? null : printer.report();
        return report == null ? List.of() : report.missing();
    }

    private boolean overMaterials(double x, double y) {
        return x >= leftPos + MATERIALS_X && x < leftPos + MATERIALS_X + MATERIALS_W
                && y >= topPos + MATERIALS_Y && y < topPos + MATERIALS_Y + ROWS * 12;
    }

    private boolean overModel(double x, double y) {
        return x >= leftPos + MODEL_X && x < leftPos + MODEL_X + MODEL_W
                && y >= topPos + MODEL_Y && y < topPos + MODEL_Y + MODEL_H;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (overMaterials(x, y) && dy != 0) {
            offset = Math.clamp(offset + (dy < 0 ? 1 : -1), 0, Math.max(0, missing().size() - ROWS));
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x(), y = event.y();
        if (event.button() == 0 && previewLayout != null && overModel(x, y)) {
            rotating = true;
            return true;
        }
        int max = Math.max(0, missing().size() - ROWS);
        if (event.button() == 0 && max > 0 && overMaterials(x, y) && x >= leftPos + MATERIALS_X + MATERIALS_W - 6) {
            offset = Math.clamp((int) Math.round((y - topPos - MATERIALS_Y) * max / (ROWS * 12 - 1)), 0, max);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (rotating && event.button() == 0) {
            yaw = (yaw + (float) dx * .035f) % (float) (2 * Math.PI);
            pitch = Math.clamp(pitch + (float) dy * .035f, -1.35f, 1.35f);
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
