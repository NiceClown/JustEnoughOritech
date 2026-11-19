package com.justenoughoritech.client;

import com.justenoughoritech.compat.jei.JEIPlugin;
import com.justenoughoritech.printer.PrintRequest;
import com.justenoughoritech.printer.BuildInspection;
import com.justenoughoritech.printer.PrinterEntity;
import com.justenoughoritech.printer.PrinterLayout;
import com.justenoughoritech.sim.ReactorLayout;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
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

import java.util.List;

import static com.justenoughoritech.client.SimulatorWidget.tr;

@EventBusSubscriber(modid="just_enough_oritech", value=Dist.CLIENT)
public final class ProjectionController {
    private static final RenderType PROJECTION_LINES = RenderType.create("just_enough_oritech_projection",
            RenderSetup.builder(RenderPipelines.LINES_TRANSLUCENT)
                    .setOutputTarget(OutputTarget.MAIN_TARGET).createRenderSetup());
    private static final int OUTLINE_LIMIT = 5_000;
    private static KeyMapping lockKey,cancelKey,rotateKey,openKey;
    private static boolean active,locked;
    private static BlockHitResult lockedHit;
    private static Rotation rotation=Rotation.NONE;
    private static Rotation previewRotation;
    private static ClientLevel world;
    private static PrinterEntity printer;
    private static ReactorLayout preview;
    private static List<ReactorLayout.Placement> placements = List.of();
    private static int revision = -1;
    private static boolean migratedKeys;
    private static BuildInspection manualInspection;
    private static boolean finishedInspection;
    private static long nextInspection;

    private ProjectionController() {}

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        var category=KeyMapping.Category.register(Identifier.fromNamespaceAndPath("just_enough_oritech","main"));
        event.registerCategory(category);
        lockKey=register(event,"lock_projection",GLFW.GLFW_KEY_Y,category);
        cancelKey=register(event,"cancel_projection",GLFW.GLFW_KEY_N,category);
        rotateKey=register(event,"rotate_projection",GLFW.GLFW_KEY_R,category);
        openKey=register(event,"open_simulator",GLFW.GLFW_KEY_J,category);
    }

    private static KeyMapping register(RegisterKeyMappingsEvent event,String name,int key,KeyMapping.Category category) {
        var mapping=new KeyMapping("key.just_enough_oritech."+name,InputConstants.Type.KEYSYM,key,category);
        event.register(mapping);
        return mapping;
    }

    public static void begin() {
        var client=Minecraft.getInstance();
        world=client.level;
        active=true;
        locked=false;
        lockedHit=null;
        rotation=Rotation.NONE;
        printer=null;
        preview=null;
        manualInspection=null;
        client.setScreen(null);
    }

    static void previewPrinter(PrinterEntity source) {
        begin();
        printer=source;
        locked=true;
    }

    public static void offerPoweredPrinter(PrinterEntity source) {
        var mc=Minecraft.getInstance();
        if (active || mc.screen!=null || mc.player==null || !source.getBlockPos().closerToCenterThan(mc.player.position(),32))
            return;
        previewPrinter(source);
    }

    private static BlockHitResult aimedFace() {
        var target=Minecraft.getInstance().hitResult;
        return target instanceof BlockHitResult hit && hit.getType()==HitResult.Type.BLOCK ? hit : null;
    }

    private static BlockPos manualOrigin(ReactorLayout layout) {
        var hit=locked ? lockedHit : aimedFace();
        return hit==null ? null : layout.buildOrigin(hit.getBlockPos().relative(hit.getDirection()),hit.getDirection(),rotation);
    }

    public static boolean handleProjectionKey(KeyEvent key,int action) {
        var mc=Minecraft.getInstance();
        if (!active || mc.level!=world || mc.screen!=null || lockKey==null || cancelKey==null) return false;
        boolean confirm=printer==null && lockKey.matches(key), cancel=cancelKey.matches(key);
        boolean rotate=printer==null && !locked && rotateKey!=null && rotateKey.matches(key);
        if (!confirm && !cancel && !rotate) return false;
        if (action==GLFW.GLFW_PRESS) {
            if (cancel) clear();
            else if (confirm) lock();
            else rotate();
        }
        return action!=GLFW.GLFW_RELEASE;
    }

    private static void lock() {
        var hit=aimedFace();
        if (hit!=null) {
            lockedHit=hit;
            locked=true;
        }
    }

    private static void rotate() {
        rotation=rotation.getRotated(Rotation.CLOCKWISE_90);
    }

    private static void clear() {
        active=locked=false;
        lockedHit=null;
        printer=null;
        preview=null;
        placements=List.of();
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var client=Minecraft.getInstance();
        if (!migratedKeys && lockKey!=null && cancelKey!=null) {
            migratedKeys=true;
            boolean lockChanged=migrateKey(lockKey,GLFW.GLFW_KEY_L);
            boolean cancelChanged=migrateKey(cancelKey,GLFW.GLFW_KEY_K);
            if (lockChanged || cancelChanged) {
                KeyMapping.resetMapping();
                client.options.save();
            }
        }
        if (openKey!=null && openKey.consumeClick() && client.level!=null) JEIPlugin.openSimulator();
        boolean requestedRotation=rotateKey!=null && rotateKey.consumeClick();
        if (!active) return;
        if (client.level!=world || (printer!=null && printer.isRemoved())) {
            clear();
            return;
        }
        // Also accepts programmatic clicks from other integrations.
        if (cancelKey!=null && cancelKey.consumeClick()) clear();
        if (active && printer==null && lockKey!=null && lockKey.consumeClick()) lock();
        if (active && printer==null && !locked && client.screen==null && requestedRotation) rotate();
        if (active && printer==null) inspectManual(client.level);
        if (active && !locked && client.screen==null && client.gui!=null && lockKey!=null && cancelKey!=null && rotateKey!=null)
            client.gui.setOverlayMessage(tr("aim",rotateKey.getTranslatedKeyMessage(),
                    lockKey.getTranslatedKeyMessage(),cancelKey.getTranslatedKeyMessage()),false);
    }

    private static void inspectManual(ClientLevel level) {
        var origin=manualOrigin(Workbench.INSTANCE.layout);
        if (origin==null) {
            manualInspection=null;
            return;
        }
        refreshManualPlacements();
        if (manualInspection==null || !origin.equals(manualInspection.origin())
                || (finishedInspection && level.getGameTime()>=nextInspection)) {
            manualInspection=new BuildInspection(placements,origin,0);
            finishedInspection=false;
        }
        if (!finishedInspection && manualInspection.scan(level,2048,true)) {
            finishedInspection=true;
            nextInspection=level.getGameTime()+10;
        }
    }

    private static void refreshManualPlacements() {
        var current=Workbench.INSTANCE;
        if (preview==current.layout && revision==current.revision() && previewRotation==rotation) return;
        preview=current.layout;
        revision=current.revision();
        previewRotation=rotation;
        placements=preview.placements(rotation);
        manualInspection=null;
    }

    private static boolean migrateKey(KeyMapping mapping,int oldDefault) {
        if (!mapping.getKey().equals(InputConstants.Type.KEYSYM.getOrCreate(oldDefault))) return false;
        mapping.setKey(mapping.getDefaultKey());
        return true;
    }

    static void sendToPrinter() {
        var mc=Minecraft.getInstance();
        if (!active || !locked || lockedHit==null || printer!=null || mc.level!=world) {
            mc.gui.setOverlayMessage(tr("printer_lock"),false);
            return;
        }
        if (!(mc.level.getBlockEntity(lockedHit.getBlockPos()) instanceof PrinterEntity)) {
            mc.gui.setOverlayMessage(tr("printer_attach"),false);
            return;
        }
        var layout=Workbench.INSTANCE.layout;
        if (!layout.validationErrors(Math.min(PrinterLayout.LIMIT,ClientRuntimeConfig.config().maxSize())).isEmpty()) {
            mc.gui.setOverlayMessage(tr("printer_invalid"),false);
            return;
        }
        ClientPacketDistributor.sendToServer(new PrintRequest(lockedHit.getBlockPos(),lockedHit.getDirection(),
                rotation,PrinterLayout.from(layout)));
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent.AfterLevel event) {
        if (!active || Minecraft.getInstance().level!=world) return;
        var selected = printer!=null ? printer.layout() : Workbench.INSTANCE.layout;
        if (selected==null) return;
        var anchor=printer!=null ? printer.anchor() : manualOrigin(selected);
        if (anchor==null) return;
        if (printer==null) refreshManualPlacements();
        var selectedRotation=printer!=null ? printer.rotation() : rotation;
        var blocks=printer!=null ? printer.placements() : placements;
        var camera=Minecraft.getInstance().gameRenderer.getMainCamera().position();
        var pose=event.getPoseStack();
        var buffers=Minecraft.getInstance().renderBuffers().bufferSource();
        var out=buffers.getBuffer(PROJECTION_LINES);
        pose.pushPose();
        try {
            pose.mulPose(event.getModelViewMatrix());
            pose.translate(anchor.getX()-camera.x,anchor.getY()-camera.y,anchor.getZ()-camera.z);
            if (blocks.size()>OUTLINE_LIMIT) {
                box(out,pose.last(),0,0,0,selected.buildWidth(selectedRotation)+2,selected.height()+2,
                        selected.buildLength(selectedRotation)+2,0x9099DDF8);
            } else {
                drawBlocks(out,pose,blocks);
            }
            var obstruction=printer!=null
                    ? (printer.report()==null ? null : printer.report().obstruction())
                    : (manualInspection==null || !anchor.equals(manualInspection.origin()) ? null : manualInspection.obstruction());
            if (obstruction!=null) drawObstruction(out,pose,anchor,obstruction);
        } finally {
            buffers.endBatch(PROJECTION_LINES);
            pose.popPose();
        }
    }

    private static void drawBlocks(VertexConsumer out,PoseStack pose,List<ReactorLayout.Placement> blocks) {
        for (var block : blocks) {
                int color=switch (block.blockId()) {
                    case ReactorLayout.WALL -> 0x558ABAD8;
                    case ReactorLayout.CONTROLLER -> 0xD0FFD258;
                    case ReactorLayout.ENERGY_PORT -> 0xD04FBAFF;
                    case ReactorLayout.FUEL_PORT -> 0xD0FF9D58;
                    case ReactorLayout.COOLANT_PORT -> 0xD057D488;
                    default -> 0xA0DEE9F0;
                };
                // Block outlines leave the view clear, including when the camera is inside the preview.
                box(out,pose.last(),block.x()+.02f,block.y()+.02f,block.z()+.02f,
                        block.x()+.98f,block.y()+.98f,block.z()+.98f,color);
            }
    }

    private static void drawObstruction(VertexConsumer out,PoseStack pose,BlockPos origin,BlockPos obstruction) {
        int x=obstruction.getX()-origin.getX(), y=obstruction.getY()-origin.getY(), z=obstruction.getZ()-origin.getZ();
        box(out,pose.last(),x-.05f,y-.05f,z-.05f,x+1.05f,y+1.05f,z+1.05f,0xFFFF414A,3f);
    }

    private static void box(VertexConsumer out,PoseStack.Pose pose,float x,float y,float z,float xx,float yy,float zz,int color) {
        box(out,pose,x,y,z,xx,yy,zz,color,1f);
    }

    private static void box(VertexConsumer out,PoseStack.Pose pose,float x,float y,float z,float xx,float yy,float zz,int color,float width) {
        for (float a : new float[]{y,yy}) for (float b : new float[]{z,zz})
            line(out,pose,x,a,b,xx,a,b,1,0,0,color,width);
        for (float a : new float[]{x,xx}) for (float b : new float[]{z,zz})
            line(out,pose,a,y,b,a,yy,b,0,1,0,color,width);
        for (float a : new float[]{x,xx}) for (float b : new float[]{y,yy})
            line(out,pose,a,b,z,a,b,zz,0,0,1,color,width);
    }

    private static void line(VertexConsumer out,PoseStack.Pose pose,float x,float y,float z,float xx,float yy,float zz,
                             float nx,float ny,float nz,int color,float width) {
        out.addVertex(pose,x,y,z).setColor(color).setNormal(pose,nx,ny,nz).setLineWidth(width);
        out.addVertex(pose,xx,yy,zz).setColor(color).setNormal(pose,nx,ny,nz).setLineWidth(width);
    }
}
