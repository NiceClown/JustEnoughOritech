package com.justenoughoritech.client;

import com.justenoughoritech.sim.ReactorLayout;
import com.justenoughoritech.sim.ReactorParts;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import com.justenoughoritech.sim.ReactorSimulation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

final class AssemblyPreview extends PictureInPictureRenderer<AssemblyPreview.State> {
    static final float DEFAULT_PITCH=(float)Math.PI/6, DEFAULT_YAW=(float)Math.PI/4;
    static final float MIN_ZOOM = 0.4f, MAX_ZOOM = 4.0f;
    private static final RenderType EDIT_OUTLINE=RenderType.create("just_enough_oritech_edit_area",
            RenderSetup.builder(RenderPipelines.LINES.toBuilder()
                    .withLocation(Identifier.fromNamespaceAndPath("just_enough_oritech","pipeline/edit_area"))
                    .withDepthStencilState(Optional.empty()).build()).createRenderSetup());

    record EditArea(int x,int z,int width,int length,int y) {}

    record State(int x0, int y0, int x1, int y1, float scale, ScreenRectangle scissorArea,
                 ScreenRectangle bounds, List<ReactorLayout.Placement> blocks, int width, int height,
                 int length, int layer, Quaternionf orientation,
                 ReactorSimulation simulation, int maxHeat, EditArea editArea, boolean heatmap3d) implements PictureInPictureRenderState {}

    AssemblyPreview(MultiBufferSource.BufferSource buffers) { super(buffers); }

    static Quaternionf defaultOrientation() {
        return new Quaternionf().rotateX(DEFAULT_PITCH).rotateY(DEFAULT_YAW).normalize();
    }

    static void orbit(Quaternionf orientation, double dx, double dy) {
        if (orientation == null) return;
        orientation.premul(new Quaternionf().rotateY((float) dx * 0.028f).rotateX((float) dy * 0.028f)).normalize();
    }

    static float clampZoom(float zoom) {
        return Math.clamp(zoom, MIN_ZOOM, MAX_ZOOM);
    }

    static int thermalColor(int heat, int maxHeat) {
        float t = maxHeat <= 0 ? 0f : Math.clamp(heat / (float) maxHeat, 0f, 1.2f);
        if (t <= 0.35f) {
            return lerpRgb(0x2CE090, 0x4BF048, t / 0.35f);
        }
        if (t <= 0.65f) {
            return lerpRgb(0x4BF048, 0xFFE020, (t - 0.35f) / 0.30f);
        }
        if (t <= 0.85f) {
            return lerpRgb(0xFFE020, 0xFF7814, (t - 0.65f) / 0.20f);
        }
        return lerpRgb(0xFF7814, 0xFF1E2D, Math.min(1f, (t - 0.85f) / 0.15f));
    }

    private static int lerpRgb(int c0, int c1, float t) {
        float k = Math.clamp(t, 0f, 1f);
        int r0 = (c0 >> 16) & 255, g0 = (c0 >> 8) & 255, b0 = c0 & 255;
        int r1 = (c1 >> 16) & 255, g1 = (c1 >> 8) & 255, b1 = c1 & 255;
        int r = Math.round(r0 + (r1 - r0) * k);
        int g = Math.round(g0 + (g1 - g0) * k);
        int b = Math.round(b0 + (b1 - b0) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static List<ReactorLayout.Placement> shell(List<ReactorLayout.Placement> blocks,int width,int height,int length) {
        return blocks.stream().filter(block -> ReactorLayout.onShell(block,width,height,length)).toList();
    }

    static List<ReactorLayout.Placement> heatmapCutaway(List<ReactorLayout.Placement> blocks, int width, int height, int length) {
        return blocks.stream().filter(block -> {
            if (!ReactorLayout.WALL.equals(block.blockId())) return true;
            return block.y() == 0;
        }).toList();
    }

    static void draw(GuiGraphicsExtractor graphics, List<ReactorLayout.Placement> blocks,
                     int coreWidth, int coreHeight, int coreLength, int layer, ReactorSimulation simulation,
                     EditArea editArea, Quaternionf orientation, float zoom,
                     int x, int y, int width, int height, boolean heatmap3d) {
        var pose = graphics.pose();
        int left = Math.round(pose.m00()*x + pose.m20());
        int top = Math.round(pose.m11()*y + pose.m21());
        int right = Math.round(pose.m00()*(x+width) + pose.m20());
        int bottom = Math.round(pose.m11()*(y+height) + pose.m21());
        var scissor = graphics.peekScissorStack();
        var bounds = PictureInPictureRenderState.getBounds(left, top, right, bottom, scissor);
        if (bounds == null) return;
        double diag = layer >= 0
                ? Math.hypot(coreWidth + 2.0, coreLength + 2.0) * 0.85
                : Math.sqrt((coreWidth + 2.0) * (coreWidth + 2.0)
                + (coreHeight + 2.0) * (coreHeight + 2.0)
                + (coreLength + 2.0) * (coreLength + 2.0)) * 0.78;
        float baseScale = (float) (Math.min(right - left - 8, bottom - top - 8) / Math.max(1.0, diag));
        float scale = baseScale * clampZoom(zoom);
        if (scale <= 0) return;
        int maxHeat = Math.max(1, ClientRuntimeConfig.config().maxHeat());
        var rotCopy = orientation == null ? defaultOrientation() : new Quaternionf(orientation);
        graphics.nextStratum();
        graphics.submitPictureInPictureRenderState(new State(left, top, right, bottom, scale, scissor, bounds,
                blocks, coreWidth, coreHeight, coreLength, layer, rotCopy, simulation, maxHeat, editArea, heatmap3d));
    }

    static void draw(GuiGraphicsExtractor graphics,List<ReactorLayout.Placement> blocks,
                     int coreWidth,int coreHeight,int coreLength,int layer,ReactorSimulation simulation,
                     EditArea editArea,float pitch,float yaw,int x,int y,int width,int height) {
        draw(graphics, blocks, coreWidth, coreHeight, coreLength, layer, simulation, editArea,
                new Quaternionf().rotateX(pitch).rotateY(yaw).normalize(), 1.0f, x, y, width, height, false);
    }

    @Override public Class<State> getRenderStateClass() { return State.class; }
    @Override protected float getTranslateY(int height, int guiScale) { return height / 2f; }
    @Override protected String getTextureLabel() { return "Oritech reactor preview"; }

    @Override protected void renderToTexture(State state, PoseStack pose) {
        pose.mulPose(state.orientation());
        pose.translate(-(state.width()+2)/2f,
                state.layer() < 0 ? -(state.height()+2)/2f : -state.layer()-0.5f, -(state.length()+2)/2f);
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        var cache = new HashMap<BlockState,List<BlockStateModelPart>>();
        var blockStates=new HashMap<String,BlockState>();
        var renderType=RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS);
        var output = bufferSource.getBuffer(renderType);
        var quadInstance = new QuadInstance();
        var simulation=state.simulation();
        quadInstance.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
        quadInstance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        int stride = state.blocks().size() > 6_000
                ? Math.max(2, (int) Math.ceil(Math.sqrt(state.blocks().size() / 4_000.0)))
                : 1;
        var heatBoxes = state.heatmap3d() && simulation != null ? new java.util.ArrayList<int[]>() : null;
        for (var block : state.blocks()) {
            if (stride > 1 && ReactorLayout.WALL.equals(block.blockId()) && !isEdge(block, state)) {
                if ((block.x() % stride != 0 && block.x() != 0 && block.x() != state.width() + 1)
                        || (block.y() % stride != 0 && block.y() != 0 && block.y() != state.height() + 1)
                        || (block.z() % stride != 0 && block.z() != 0 && block.z() != state.length() + 1)) {
                    continue;
                }
            }
            var modelState=blockStates.computeIfAbsent(block.blockId(),
                    id -> BuiltInRegistries.BLOCK.getValue(Identifier.parse(id)).defaultBlockState());
            if (block.blockId().equals(ReactorLayout.CONTROLLER) || block.blockId().equals(ReactorLayout.ENERGY_PORT)) {
                var facing=ReactorLayout.wallFacing(block,state.width());
                if (modelState.hasProperty(BlockStateProperties.FACING)) modelState=modelState.setValue(BlockStateProperties.FACING,facing);
                else if (modelState.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
                    modelState=modelState.setValue(BlockStateProperties.HORIZONTAL_FACING,facing);
            }
            var parts = cache.computeIfAbsent(modelState, blockState -> {
                var baked = new java.util.ArrayList<BlockStateModelPart>();
                models.get(blockState).collectParts(net.minecraft.util.RandomSource.create(42),baked);
                return List.copyOf(baked);
            });
            pose.pushPose();
            pose.translate(block.x(), block.y(), block.z());
            int thermalTint=0xFFFFFFFF;
            if (state.heatmap3d() && ReactorLayout.WALL.equals(block.blockId()) && block.y() == 0) {
                thermalTint = 0xFF68707A;
            }
            if ((state.layer()>=0 || state.heatmap3d()) && simulation!=null
                    && block.x()>0 && block.x()<=state.width()
                    && block.z()>0 && block.z()<=state.length()
                    && (state.layer()>=0 || (block.y()>0 && block.y()<=state.height()))) {
                var cell = simulation.cell(block.x()-1,block.z()-1);
                if (cell!=null) {
                    int heatColor = state.heatmap3d() ? thermalColor(cell.heat, state.maxHeat()) : SimulatorWidget.heatColor(cell.heat);
                    int r = ((heatColor>>16&255)*3 + 255)/4;
                    int g = ((heatColor>>8&255)*3 + 255)/4;
                    int b = ((heatColor&255)*3 + 255)/4;
                    thermalTint=0xFF000000 | (r<<16) | (g<<8) | b;
                    if (heatBoxes != null && cell.part.kind() != ReactorParts.Kind.EMPTY) {
                        heatBoxes.add(new int[]{block.x(), block.y(), block.z(), heatColor});
                    }
                }
            }
            for (var part : parts) {
                quadInstance.setColor(thermalTint);
                for (var quad : part.getQuads(null)) output.putBakedQuad(pose.last(),quad,quadInstance);
                for (var face : Direction.values()) {
                    quadInstance.setColor(switch (face) {
                        case UP -> 0xFFFFFFFF;
                        case DOWN -> 0xFF888888;
                        case EAST, WEST -> 0xFFBDBDBD;
                        case NORTH, SOUTH -> 0xFFDADADA;
                    });
                    quadInstance.multiplyColor(thermalTint);
                    for (var quad : part.getQuads(face)) output.putBakedQuad(pose.last(),quad,quadInstance);
                }
            }
            pose.popPose();
        }
        if (heatBoxes != null && !heatBoxes.isEmpty()) {
            bufferSource.endBatch(renderType);
            drawHeatGlowBoxes(pose.last().pose(), heatBoxes);
        }
        if (state.editArea()!=null) {
            // Draw after the model so the active editor area is visible through the casing.
            bufferSource.endBatch(renderType);
            drawEditArea(pose.last(),state);
        }
    }

    private static boolean isEdge(ReactorLayout.Placement b, State s) {
        int ex = (b.x() == 0 || b.x() == s.width() + 1) ? 1 : 0;
        int ey = (b.y() == 0 || b.y() == s.height() + 1) ? 1 : 0;
        int ez = (b.z() == 0 || b.z() == s.length() + 1) ? 1 : 0;
        return ex + ey + ez >= 2;
    }

    private void drawHeatGlowBoxes(Matrix4f mat, List<int[]> boxes) {
        var quads = bufferSource.getBuffer(RenderTypes.debugQuads());
        for (int[] box : boxes) {
            int rgb = box[3] & 0x00FFFFFF;
            int fill = 0x72000000 | rgb;
            float x0 = box[0] - 0.025f, y0 = box[1] - 0.025f, z0 = box[2] - 0.025f;
            float x1 = box[0] + 1.025f, y1 = box[1] + 1.025f, z1 = box[2] + 1.025f;
            quad(quads, mat, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, fill);
            quad(quads, mat, x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, fill);
            quad(quads, mat, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, fill);
            quad(quads, mat, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, fill);
            quad(quads, mat, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, fill);
            quad(quads, mat, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, fill);
        }
        bufferSource.endBatch(RenderTypes.debugQuads());
    }

    private static void quad(VertexConsumer out, Matrix4f mat,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3, int color) {
        out.addVertex(mat, x0, y0, z0).setColor(color);
        out.addVertex(mat, x1, y1, z1).setColor(color);
        out.addVertex(mat, x2, y2, z2).setColor(color);
        out.addVertex(mat, x3, y3, z3).setColor(color);
    }

    private void drawEditArea(PoseStack.Pose pose,State state) {
        var window=Minecraft.getInstance().getWindow();
        // The line shader gets the main framebuffer size, while this preview uses a smaller target.
        float widthRatio=(float)window.getGuiScaledWidth()/(state.x1()-state.x0());
        float heightRatio=(float)window.getGuiScaledHeight()/(state.y1()-state.y0());
        float thickness=window.getGuiScale()*Math.max(widthRatio,heightRatio);
        var out=bufferSource.getBuffer(EDIT_OUTLINE);
        outline(out,pose,state.editArea(),0xFF11271B,2.75f*thickness);
        outline(out,pose,state.editArea(),0xFF62FF72,1.75f*thickness);
        bufferSource.endBatch(EDIT_OUTLINE);
    }

    private static void outline(VertexConsumer out,PoseStack.Pose pose,EditArea area,int color,float thickness) {
        int x=area.x(), endX=x+area.width(), z=area.z(), endZ=z+area.length(), y=area.y();
        line(out,pose,x,y,z,endX,z,color,thickness);
        line(out,pose,x,y,endZ,endX,endZ,color,thickness);
        line(out,pose,x,y,z,x,endZ,color,thickness);
        line(out,pose,endX,y,z,endX,endZ,color,thickness);
    }

    private static void line(VertexConsumer out,PoseStack.Pose pose,int x,int y,int z,int endX,int endZ,
                             int color,float thickness) {
        int dx=Integer.signum(endX-x), dz=Integer.signum(endZ-z);
        out.addVertex(pose,x,y,z).setColor(color).setNormal(pose,dx,0,dz).setLineWidth(thickness);
        out.addVertex(pose,endX,y,endZ).setColor(color).setNormal(pose,dx,0,dz).setLineWidth(thickness);
    }
}
