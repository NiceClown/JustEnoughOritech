package com.justenoughoritech.client;

import com.justenoughoritech.compat.jei.JEIPlugin;
import com.justenoughoritech.printer.BlueprintData;
import com.justenoughoritech.printer.BuildInspection;
import com.justenoughoritech.printer.PrinterControlRequest;
import com.justenoughoritech.printer.PrinterEntity;
import com.justenoughoritech.sim.ReactorLayout;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.justenoughoritech.client.SimulatorWidget.tr;

@EventBusSubscriber(modid = "just_enough_oritech", value = Dist.CLIENT)
public final class ProjectionController {
    private static final RenderType PROJECTION_LINES = RenderType.create("just_enough_oritech_projection",
            RenderSetup.builder(RenderPipelines.LINES_TRANSLUCENT)
                    .setOutputTarget(OutputTarget.MAIN_TARGET).createRenderSetup());
    private static final int GHOST_LIMIT = 4_096;
    private static final int OUTLINE_LIMIT = 12_000;

    private record GhostBlock(ReactorLayout.Placement placement, BlockState state, int alphaTint, double distSq) {}
    private static KeyMapping lockKey, cancelKey, rotateKey, openKey;
    private static boolean active, locked;
    private static BlockHitResult lockedHit;
    private static Rotation rotation = Rotation.NONE;
    private static Rotation previewRotation;
    private static Mirror previewMirror;
    private static ClientLevel world;
    private static PrinterEntity printer;
    private static PrinterEntity aimTarget;
    private static ReactorLayout preview;
    private static List<ReactorLayout.Placement> placements = List.of();
    private static int revision = -1;
    private static boolean migratedKeys;
    private static BuildInspection manualInspection;
    private static boolean finishedInspection;
    private static long nextInspection;

    private ProjectionController() {}

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("just_enough_oritech", "main"));
        event.registerCategory(category);
        lockKey = register(event, "lock_projection", GLFW.GLFW_KEY_Y, category);
        cancelKey = register(event, "cancel_projection", GLFW.GLFW_KEY_N, category);
        rotateKey = register(event, "rotate_projection", GLFW.GLFW_KEY_R, category);
        openKey = register(event, "open_simulator", GLFW.GLFW_KEY_K, category);
    }

    private static KeyMapping register(RegisterKeyMappingsEvent event, String name, int key, KeyMapping.Category category) {
        var mapping = new KeyMapping("key.just_enough_oritech." + name, InputConstants.Type.KEYSYM, key, category);
        event.register(mapping);
        return mapping;
    }

    public static void begin() {
        begin(true);
    }

    private static void begin(boolean closeScreen) {
        var client = Minecraft.getInstance();
        if (client.level == null) return;
        world = client.level;
        active = true;
        locked = false;
        lockedHit = null;
        rotation = Rotation.NONE;
        printer = null;
        aimTarget = null;
        preview = null;
        previewMirror = null;
        manualInspection = null;
        drainKey(lockKey);
        drainKey(cancelKey);
        drainKey(rotateKey);
        if (closeScreen) client.setScreen(null);
    }

    static void beginPrinterAim(PrinterEntity target) {
        if (target == null || target.layout() == null) return;
        begin(true);
        if (!active) return;
        aimTarget = target;
        rotation = target.rotation();
    }

    private static void drainKey(KeyMapping mapping) {
        if (mapping == null) return;
        while (mapping.consumeClick()) {}
    }

    static void previewPrinter(PrinterEntity source) {
        begin(false);
        if (!active) return;
        printer = source;
        locked = true;
    }

    static boolean isPreviewingPrinter(PrinterEntity source) {
        return active && source != null && printer == source && aimTarget == null;
    }

    static void togglePrinterPreview(PrinterEntity source) {
        if (isPreviewingPrinter(source)) clear();
        else previewPrinter(source);
    }

    public static void offerPoweredPrinter(PrinterEntity source) {
        var mc = Minecraft.getInstance();
        if (active || mc.screen != null || mc.player == null || !source.getBlockPos().closerToCenterThan(mc.player.position(), 32))
            return;
        previewPrinter(source);
    }

    public static Optional<BlueprintData.Anchor> lockedAnchor() {
        if (!active || !locked || lockedHit == null || printer != null || aimTarget != null || world == null)
            return Optional.empty();
        var origin = Workbench.INSTANCE.layout.buildOrigin(
                lockedHit.getBlockPos().relative(lockedHit.getDirection()),
                lockedHit.getDirection(),
                rotation);
        return Optional.of(new BlueprintData.Anchor(world.dimension().identifier(), origin, rotation));
    }

    public static void clearLockedAnchor() {
        if (printer == null && aimTarget == null) clear();
    }

    private static BlockHitResult aimedFace() {
        var target = Minecraft.getInstance().hitResult;
        return target instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private static BlockPos manualOrigin(ReactorLayout layout) {
        var hit = locked ? lockedHit : aimedFace();
        return hit == null ? null : layout.buildOrigin(hit.getBlockPos().relative(hit.getDirection()), hit.getDirection(), rotation);
    }

    public static boolean handleProjectionKey(KeyEvent key, int action) {
        var mc = Minecraft.getInstance();
        if (!active || mc.level != world || mc.screen != null || lockKey == null || cancelKey == null) return false;
        boolean confirm = printer == null && lockKey.matches(key);
        boolean cancel = cancelKey.matches(key);
        boolean rotate = printer == null && !locked && rotateKey != null && rotateKey.matches(key);
        if (!confirm && !cancel && !rotate) return false;
        if (action == GLFW.GLFW_PRESS) {
            if (cancel) clear();
            else if (confirm) lock();
            else rotate();
        }
        return action != GLFW.GLFW_RELEASE;
    }

    private static void lock() {
        var hit = aimedFace();
        if (hit == null) return;
        if (aimTarget != null) {
            var layout = aimTarget.layout();
            if (layout != null) {
                var newOrigin = layout.buildOrigin(hit.getBlockPos().relative(hit.getDirection()), hit.getDirection(), rotation);
                var target = aimTarget;
                ClientPacketDistributor.sendToServer(PrinterControlRequest.setAnchor(target.getBlockPos(), newOrigin, rotation));
                previewPrinter(target);
            }
            return;
        }
        lockedHit = hit;
        locked = true;
    }

    private static void rotate() {
        rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
    }

    private static void clear() {
        active = locked = false;
        lockedHit = null;
        printer = null;
        aimTarget = null;
        preview = null;
        placements = List.of();
        previewRotation = null;
        previewMirror = null;
        revision = -1;
        manualInspection = null;
        finishedInspection = false;
        nextInspection = 0;
    }

    /** Drop every preview derived from the previous server session, e.g. on disconnect. */
    static void onDisconnect() {
        clear();
        world = null;
        rotation = Rotation.NONE;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        if (!migratedKeys && lockKey != null && cancelKey != null && openKey != null) {
            migratedKeys = true;
            boolean lockChanged = migrateKey(lockKey, GLFW.GLFW_KEY_L);
            boolean cancelChanged = migrateKey(cancelKey, GLFW.GLFW_KEY_K);
            boolean openChanged = migrateKey(openKey, GLFW.GLFW_KEY_J);
            if (lockChanged || cancelChanged || openChanged) {
                KeyMapping.resetMapping();
                client.options.save();
            }
        }
        if (openKey != null && openKey.consumeClick() && client.level != null) JEIPlugin.openSimulator();
        boolean requestedRotation = rotateKey != null && rotateKey.consumeClick();
        if (!active) return;
        if (client.level != world
                || (printer != null && (printer.isRemoved() || printer.layout() == null))
                || (aimTarget != null && (aimTarget.isRemoved() || aimTarget.layout() == null))) {
            clear();
            return;
        }
        // Also accepts programmatic clicks from other integrations.
        if (cancelKey != null && cancelKey.consumeClick()) clear();
        if (active && printer == null && lockKey != null && lockKey.consumeClick()) lock();
        if (active && printer == null && !locked && client.screen == null && requestedRotation) rotate();
        if (active && printer == null) inspectManual(client.level);
        if (active && client.screen == null && client.gui != null && lockKey != null && cancelKey != null && rotateKey != null) {
            if (!locked) {
                client.gui.setOverlayMessage(tr("aim", rotateKey.getTranslatedKeyMessage(),
                        lockKey.getTranslatedKeyMessage(), cancelKey.getTranslatedKeyMessage()), false);
            } else if (printer == null && openKey != null) {
                client.gui.setOverlayMessage(tr("aim_locked",
                        openKey.getTranslatedKeyMessage(), cancelKey.getTranslatedKeyMessage()), false);
            }
        }
    }

    private static ReactorLayout manualLayout() {
        return aimTarget != null ? aimTarget.layout() : Workbench.INSTANCE.layout;
    }

    private static Mirror manualMirror() {
        return aimTarget != null ? aimTarget.mirror() : Mirror.NONE;
    }

    private static void inspectManual(ClientLevel level) {
        var layout = manualLayout();
        if (layout == null) {
            manualInspection = null;
            return;
        }
        var origin = manualOrigin(layout);
        if (origin == null) {
            manualInspection = null;
            return;
        }
        refreshManualPlacements();
        if (manualInspection == null || !origin.equals(manualInspection.origin())
                || (finishedInspection && level.getGameTime() >= nextInspection)) {
            manualInspection = new BuildInspection(placements, origin, 0);
            finishedInspection = false;
        }
        if (!finishedInspection && manualInspection.scan(level, 2048, true)) {
            finishedInspection = true;
            nextInspection = level.getGameTime() + 10;
        }
    }

    private static void refreshManualPlacements() {
        var layout = manualLayout();
        var mirror = manualMirror();
        int rev = aimTarget != null ? 0 : Workbench.INSTANCE.revision();
        if (preview == layout && revision == rev && previewRotation == rotation && previewMirror == mirror) return;
        preview = layout;
        revision = rev;
        previewRotation = rotation;
        previewMirror = mirror;
        if (layout == null) {
            placements = List.of();
        } else {
            long outerVol = (long) (layout.width() + 2) * (layout.height() + 2) * (layout.length() + 2);
            placements = outerVol > OUTLINE_LIMIT ? List.of() : layout.placements(rotation, mirror);
        }
        manualInspection = null;
    }

    private static boolean migrateKey(KeyMapping mapping, int oldDefault) {
        if (!mapping.getKey().equals(InputConstants.Type.KEYSYM.getOrCreate(oldDefault))) return false;
        mapping.setKey(mapping.getDefaultKey());
        return true;
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent.AfterLevel event) {
        if (!active || world == null || Minecraft.getInstance().level != world) return;
        var selected = printer != null ? printer.layout() : manualLayout();
        if (selected == null) return;
        var anchor = printer != null ? printer.anchor() : manualOrigin(selected);
        if (anchor == null) return;
        if (printer == null) refreshManualPlacements();
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        var pose = event.getPoseStack();
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        var selectedRotation = printer != null ? printer.rotation() : rotation;
        var selectedMirror = printer != null ? printer.mirror() : manualMirror();
        var blocks = printer != null ? printer.placements() : placements;
        int buildWidth = selected.buildWidth(selectedRotation);
        long outerVol = (long) (selected.width() + 2) * (selected.height() + 2) * (selected.length() + 2);
        boolean tooBigForBlocks = outerVol > OUTLINE_LIMIT || blocks.size() > OUTLINE_LIMIT;
        var obstructions = new ArrayList<BlockPos>();
        var ghosts = new ArrayList<GhostBlock>();

        if (!tooBigForBlocks && !blocks.isEmpty() && blocks.size() <= GHOST_LIMIT) {
            var cursor = new BlockPos.MutableBlockPos();
            var blockCache = new HashMap<String, Block>();
            for (var block : blocks) {
                cursor.set(anchor.getX() + block.x(), anchor.getY() + block.y(), anchor.getZ() + block.z());
                if (!world.hasChunkAt(cursor)) continue;
                var expected = blockCache.computeIfAbsent(block.blockId(), id -> {
                    var parsed = Identifier.tryParse(id);
                    return parsed == null ? null : BuiltInRegistries.BLOCK.getValue(parsed);
                });
                if (expected == null) continue;
                var current = world.getBlockState(cursor);
                if (current.is(expected)) continue;
                if (!current.isAir() && !current.canBeReplaced()) {
                    obstructions.add(cursor.immutable());
                }
                var modelState = expected.defaultBlockState();
                if (ReactorLayout.CONTROLLER.equals(block.blockId()) || ReactorLayout.ENERGY_PORT.equals(block.blockId())) {
                    var facing = ReactorLayout.wallFacing(block, buildWidth);
                    if (modelState.hasProperty(BlockStateProperties.FACING)) {
                        modelState = modelState.setValue(BlockStateProperties.FACING, facing);
                    } else if (modelState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                        modelState = modelState.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
                    }
                }
                int alphaTint = ReactorLayout.WALL.equals(block.blockId()) ? 0x4CFFFFFF
                        : (ReactorLayout.CONTROLLER.equals(block.blockId())
                        || ReactorLayout.ENERGY_PORT.equals(block.blockId())
                        || ReactorLayout.FUEL_PORT.equals(block.blockId())
                        || ReactorLayout.COOLANT_PORT.equals(block.blockId())) ? 0xB8FFFFFF : 0xB0FFFFFF;
                double dx = (cursor.getX() + 0.5) - camera.x;
                double dy = (cursor.getY() + 0.5) - camera.y;
                double dz = (cursor.getZ() + 0.5) - camera.z;
                ghosts.add(new GhostBlock(block, modelState, alphaTint, dx * dx + dy * dy + dz * dz));
            }
            ghosts.sort(Comparator.comparingDouble(GhostBlock::distSq).reversed());
        }

        pose.pushPose();
        try {
            pose.mulPose(event.getModelViewMatrix());
            pose.translate(anchor.getX() - camera.x, anchor.getY() - camera.y, anchor.getZ() - camera.z);
            if (!ghosts.isEmpty()) {
                drawGhostBlocks(buffers, pose, ghosts);
            }
            var out = buffers.getBuffer(PROJECTION_LINES);
            if (tooBigForBlocks) {
                box(out, pose.last(), 0, 0, 0, buildWidth + 2, selected.height() + 2,
                        selected.buildLength(selectedRotation) + 2, 0x9099DDF8);
                for (var b : selected.layerPlacements(1, selectedRotation, selectedMirror)) {
                    if (ReactorLayout.CONTROLLER.equals(b.blockId())) {
                        box(out, pose.last(), b.x(), b.y(), b.z(), b.x() + 1f, b.y() + 1f, b.z() + 1f, 0xFFFFD258, 2.5f);
                        break;
                    }
                }
            } else if (blocks.size() > GHOST_LIMIT) {
                drawBlocks(out, pose, blocks);
            } else if (ghosts.isEmpty()) {
                box(out, pose.last(), 0, 0, 0, buildWidth + 2, selected.height() + 2,
                        selected.buildLength(selectedRotation) + 2, 0x9057E09B, 1.5f);
            } else {
                box(out, pose.last(), -0.01f, -0.01f, -0.01f, buildWidth + 2.01f, selected.height() + 2.01f,
                        selected.buildLength(selectedRotation) + 2.01f, 0x4599DDF8, 1f);
                for (var g : ghosts) {
                    if (ReactorLayout.CONTROLLER.equals(g.placement().blockId())) {
                        var b = g.placement();
                        box(out, pose.last(), b.x() - 0.01f, b.y() - 0.01f, b.z() - 0.01f,
                                b.x() + 1.01f, b.y() + 1.01f, b.z() + 1.01f, 0xD0FFD258, 2f);
                        break;
                    }
                }
            }
            var manualObs = printer != null
                    ? (printer.report() == null ? null : printer.report().obstruction())
                    : (manualInspection == null || !anchor.equals(manualInspection.origin()) ? null : manualInspection.obstruction());
            if (!obstructions.isEmpty()) {
                for (var obs : obstructions) drawObstruction(out, pose, anchor, obs);
            } else if (manualObs != null) {
                drawObstruction(out, pose, anchor, manualObs);
            }
            buffers.endBatch(PROJECTION_LINES);
        } finally {
            pose.popPose();
        }
    }

    private static void drawGhostBlocks(net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers,
                                        PoseStack pose, List<GhostBlock> ghosts) {
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        var modelCache = new HashMap<BlockState, List<BlockStateModelPart>>();
        var ghostType = RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
        var output = buffers.getBuffer(ghostType);
        var quadInstance = new QuadInstance();
        quadInstance.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
        quadInstance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        for (var entry : ghosts) {
            var b = entry.placement();
            var parts = modelCache.computeIfAbsent(entry.state(), s -> {
                var baked = new ArrayList<BlockStateModelPart>();
                models.get(s).collectParts(RandomSource.create(42), baked);
                return List.copyOf(baked);
            });
            pose.pushPose();
            pose.translate(b.x() + 0.002f, b.y() + 0.002f, b.z() + 0.002f);
            pose.scale(0.996f, 0.996f, 0.996f);
            for (var part : parts) {
                quadInstance.setColor(entry.alphaTint());
                for (var quad : part.getQuads(null)) output.putBakedQuad(pose.last(), quad, quadInstance);
                for (var face : Direction.values()) {
                    quadInstance.setColor(switch (face) {
                        case UP -> 0xFFFFFFFF;
                        case DOWN -> 0xFF999999;
                        case EAST, WEST -> 0xFFC8C8C8;
                        case NORTH, SOUTH -> 0xFFE2E2E2;
                    });
                    quadInstance.multiplyColor(entry.alphaTint());
                    for (var quad : part.getQuads(face)) output.putBakedQuad(pose.last(), quad, quadInstance);
                }
            }
            pose.popPose();
        }
        buffers.endBatch(ghostType);
    }

    private static void drawBlocks(VertexConsumer out, PoseStack pose, List<ReactorLayout.Placement> blocks) {
        for (var block : blocks) {
            int color = switch (block.blockId()) {
                case ReactorLayout.WALL -> 0x558ABAD8;
                case ReactorLayout.CONTROLLER -> 0xD0FFD258;
                case ReactorLayout.ENERGY_PORT -> 0xD04FBAFF;
                case ReactorLayout.FUEL_PORT -> 0xD0FF9D58;
                case ReactorLayout.COOLANT_PORT -> 0xD057D488;
                default -> 0xA0DEE9F0;
            };
            // Block outlines leave the view clear, including when the camera is inside the preview.
            box(out, pose.last(), block.x() + .02f, block.y() + .02f, block.z() + .02f,
                    block.x() + .98f, block.y() + .98f, block.z() + .98f, color);
        }
    }

    private static void drawObstruction(VertexConsumer out, PoseStack pose, BlockPos origin, BlockPos obstruction) {
        int x = obstruction.getX() - origin.getX(), y = obstruction.getY() - origin.getY(), z = obstruction.getZ() - origin.getZ();
        box(out, pose.last(), x - .05f, y - .05f, z - .05f, x + 1.05f, y + 1.05f, z + 1.05f, 0xFFFF414A, 3f);
    }

    private static void box(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z, float xx, float yy, float zz, int color) {
        box(out, pose, x, y, z, xx, yy, zz, color, 1f);
    }

    private static void box(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z, float xx, float yy, float zz, int color, float width) {
        for (float a : new float[]{y, yy}) for (float b : new float[]{z, zz})
            line(out, pose, x, a, b, xx, a, b, 1, 0, 0, color, width);
        for (float a : new float[]{x, xx}) for (float b : new float[]{z, zz})
            line(out, pose, a, y, b, a, yy, b, 0, 1, 0, color, width);
        for (float a : new float[]{x, xx}) for (float b : new float[]{y, yy})
            line(out, pose, a, b, z, a, b, zz, 0, 0, 1, color, width);
    }

    private static void line(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z, float xx, float yy, float zz,
                             float nx, float ny, float nz, int color, float width) {
        out.addVertex(pose, x, y, z).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
        out.addVertex(pose, xx, yy, zz).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
    }
}
