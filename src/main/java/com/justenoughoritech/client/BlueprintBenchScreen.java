package com.justenoughoritech.client;

import com.justenoughoritech.compat.jei.JEIPlugin;
import com.justenoughoritech.printer.BlueprintBenchMenu;
import com.justenoughoritech.printer.BlueprintData;
import com.justenoughoritech.printer.OpenBlueprintBenchRequest;
import com.justenoughoritech.printer.PrinterContent;
import com.justenoughoritech.printer.PrinterLayout;
import com.justenoughoritech.printer.UpdateBlueprintBenchNameRequest;
import com.justenoughoritech.sim.ReactorLayout;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Optional;

import static com.justenoughoritech.client.SimulatorWidget.tr;

/** Interactive Blueprint Drafting Table screen with visible player inventory, real Paper slot, and 360° 3D preview. */
final class BlueprintBenchScreen extends AbstractContainerScreen<BlueprintBenchMenu> {
    private static final Workbench WB = Workbench.INSTANCE;
    private static final int MODEL_X = 186, MODEL_Y = 36, MODEL_W = 154, MODEL_H = 142;

    private final TextViewport textViewport = new TextViewport();
    private final Quaternionf orientation = AssemblyPreview.defaultOrientation();
    private float zoom = 1.0f;
    private boolean rotating;
    private EditBox nameBox;
    private OritechButton anchorBtn;
    private ReactorLayout previewLayout;
    private List<ReactorLayout.Placement> previewShell = List.of();

    BlueprintBenchScreen(BlueprintBenchMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 348, 230);
    }

    @Override
    protected void init() {
        super.init();
        rotating = false;
        orientation.set(WB.previewOrientation);
        zoom = WB.previewZoom;

        String initialName = !menu.data().name().isBlank() ? menu.data().name() : WB.blueprintName;
        nameBox = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 34, 170, 14, tr("library.name")));
        nameBox.setMaxLength(BlueprintData.MAX_NAME_LENGTH);
        nameBox.setValue(initialName);
        nameBox.setResponder(v -> {
            String clean = BlueprintData.sanitizeName(v);
            WB.blueprintName = clean;
            menu.setBlueprintName(clean);
            ClientPacketDistributor.sendToServer(new UpdateBlueprintBenchNameRequest(clean));
        });

        boolean hasAnchor = activeAnchor().isPresent();
        anchorBtn = addRenderableWidget(new OritechButton(
                leftPos + 12, topPos + 114, 162, 15,
                tr(hasAnchor ? "blueprint_clear_btn" : "blueprint_set_btn"),
                hasAnchor ? OritechTheme.ButtonStyle.GREEN : OritechTheme.ButtonStyle.DARK,
                tr(hasAnchor ? "control.blueprint_clear" : "control.blueprint_set"),
                b -> toggleAnchor()));

        addRenderableWidget(new OritechButton(
                leftPos + MODEL_X, topPos + 203, MODEL_W, 18,
                tr("blueprint_bench_back"),
                OritechTheme.ButtonStyle.DARK,
                null,
                b -> {
                    onClose();
                    JEIPlugin.openSimulator();
                }));

        int max = Math.min(PrinterLayout.LIMIT, ClientRuntimeConfig.config().maxSize());
        previewLayout = menu.data().layout().resolve(max);
        previewShell = previewLayout == null ? List.of() : previewLayout.shellPlacements(Rotation.NONE, Mirror.NONE);
    }

    private Optional<BlueprintData.Anchor> activeAnchor() {
        return menu.data().anchor();
    }

    private void toggleAnchor() {
        if (activeAnchor().isPresent()) {
            ProjectionController.clearLockedAnchor();
            var data = menu.data();
            ClientPacketDistributor.sendToServer(new OpenBlueprintBenchRequest(data.layout(), Optional.empty(), WB.blueprintName));
        } else {
            onClose();
            ProjectionController.begin();
        }
    }

    @Override
    protected void containerTick() {
        if (anchorBtn != null) {
            boolean hasAnchor = activeAnchor().isPresent();
            anchorBtn.setMessage(tr(hasAnchor ? "blueprint_clear_btn" : "blueprint_set_btn"));
            anchorBtn.setStyle(hasAnchor ? OritechTheme.ButtonStyle.GREEN : OritechTheme.ButtonStyle.DARK);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        OritechTheme.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        OritechTheme.headerBar(graphics, leftPos + 1, topPos + 1, imageWidth - 2, 19);
        graphics.fill(leftPos + 182, topPos + 21, leftPos + 183, topPos + imageHeight - 4, 0xFF8B8E94);

        for (var slot : menu.slots) {
            OritechTheme.slot(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
        }

        var inSlot = menu.getSlot(0);
        if (!inSlot.hasItem()) {
            graphics.fakeItem(new ItemStack(Items.PAPER), leftPos + inSlot.x, topPos + inSlot.y);
            graphics.fill(leftPos + inSlot.x, topPos + inSlot.y, leftPos + inSlot.x + 16, topPos + inSlot.y + 16, 0x90232930);
        }

        var outSlot = menu.getSlot(1);
        if (menu.canCraft()) {
            graphics.outline(leftPos + outSlot.x - 1, topPos + outSlot.y - 1, 18, 18, OritechTheme.CYAN_BRIGHT);
        } else if (!outSlot.hasItem()) {
            graphics.fakeItem(new ItemStack(PrinterContent.BLUEPRINT.get()), leftPos + outSlot.x, topPos + outSlot.y);
            graphics.fill(leftPos + outSlot.x, topPos + outSlot.y, leftPos + outSlot.x + 16, topPos + outSlot.y + 16, 0xA0252A30);
        }

        OritechTheme.inset(graphics, leftPos + 8, topPos + 89, 170, 44);
        OritechTheme.inset(graphics, leftPos + MODEL_X, topPos + MODEL_Y, MODEL_W, MODEL_H);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        line(graphics, title, 8, 6, 170, OritechTheme.TEXT_LIGHT);
        line(graphics, tr("library.name"), 8, 24, 170, OritechTheme.TEXT_DARK);

        boolean ready = menu.canCraft();
        textViewport.line(graphics, font, Component.literal("===>"), 52, 59, 54,
                ready ? OritechTheme.STATUS_OK_DARK : OritechTheme.TEXT_DARK_MUTED, true);

        boolean creative = minecraft != null && minecraft.player != null && minecraft.player.isCreative();
        Component hint = creative ? tr("blueprint_bench_hint_creative")
                : menu.hasPaper() ? tr("blueprint_bench_hint_ready")
                : tr("blueprint_bench_hint_survival");
        int hintColor = (creative || menu.hasPaper()) ? OritechTheme.STATUS_OK_DARK : OritechTheme.TEXT_DARK_MUTED;
        line(graphics, hint, 8, 75, 170, hintColor);

        var anchorOpt = activeAnchor();
        if (anchorOpt.isPresent()) {
            var a = anchorOpt.get();
            line(graphics, tr("blueprint_anchored", a.origin().getX(), a.origin().getY(), a.origin().getZ(),
                    BlueprintData.rotationDegrees(a.rotation())), 12, 93, 162, OritechTheme.STATUS_OK);
        } else {
            line(graphics, tr("blueprint_pos_auto"), 12, 93, 162, OritechTheme.TEXT_MUTED);
        }

        line(graphics, playerInventoryTitle, 12, 138, 162, OritechTheme.TEXT_DARK_MUTED);

        var pl = menu.data().layout();
        line(graphics, tr("blueprint_size", pl.width(), pl.length(), pl.height(), pl.ports()),
                MODEL_X, 24, MODEL_W, OritechTheme.TEXT_DARK);

        if (previewLayout != null) {
            AssemblyPreview.draw(graphics, previewShell,
                    previewLayout.width(), previewLayout.height(), previewLayout.length(),
                    -1, null, null, orientation, zoom,
                    MODEL_X + 1, MODEL_Y + 1, MODEL_W - 2, MODEL_H - 2, false);
        }
        textViewport.paragraph(graphics, font, tr("blueprint_bench_tip"), MODEL_X, 181, MODEL_W, 2, OritechTheme.TEXT_DARK_MUTED);
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        if (!rotating && overModel(mouseX, mouseY) && previewLayout != null) {
            graphics.setTooltipForNextFrame(font, tr("preview_orbit_tip"), mouseX, mouseY);
        }
    }

    private boolean overModel(double mx, double my) {
        return mx >= leftPos + MODEL_X && mx < leftPos + MODEL_X + MODEL_W
                && my >= topPos + MODEL_Y && my < topPos + MODEL_Y + MODEL_H;
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

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (previewLayout != null && overModel(mx, my) && dy != 0) {
            zoom = AssemblyPreview.clampZoom(zoom * (dy > 0 ? 1.15f : 1f / 1.15f));
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (nameBox != null && nameBox.isFocused()) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                nameBox.setFocused(false);
                return true;
            }
            return nameBox.keyPressed(event) || nameBox.canConsumeInput();
        }
        return super.keyPressed(event);
    }

    private void line(GuiGraphicsExtractor graphics, Component label, int x, int y, int width, int color) {
        textViewport.line(graphics, font, label, x, y, width, color, false);
    }
}
