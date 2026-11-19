package com.justenoughoritech.client;

import com.justenoughoritech.sim.ReactorLayout;
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
import org.joml.Quaternionf;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

final class AssemblyPreview extends PictureInPictureRenderer<AssemblyPreview.State> {
    static final float DEFAULT_PITCH=(float)Math.PI/6, DEFAULT_YAW=(float)Math.PI/4;
    private static final RenderType EDIT_OUTLINE=RenderType.create("just_enough_oritech_edit_area",
            RenderSetup.builder(RenderPipelines.LINES.toBuilder()
                    .withLocation(Identifier.fromNamespaceAndPath("just_enough_oritech","pipeline/edit_area"))
                    .withDepthStencilState(Optional.empty()).build()).createRenderSetup());

    record EditArea(int x,int z,int width,int length,int y) {}

    record State(int x0, int y0, int x1, int y1, float scale, ScreenRectangle scissorArea,
                 ScreenRectangle bounds, List<ReactorLayout.Placement> blocks, int width, int height,
                 int length, int layer, float pitch, float yaw,
                 ReactorSimulation simulation, EditArea editArea) implements PictureInPictureRenderState {}

    AssemblyPreview(MultiBufferSource.BufferSource buffers) { super(buffers); }

    static List<ReactorLayout.Placement> shell(List<ReactorLayout.Placement> blocks,int width,int height,int length) {
        return blocks.stream().filter(block -> ReactorLayout.onShell(block,width,height,length)).toList();
    }

    static void draw(GuiGraphicsExtractor graphics,List<ReactorLayout.Placement> blocks,
                     int coreWidth,int coreHeight,int coreLength,int layer,ReactorSimulation simulation,
                     EditArea editArea,float pitch,float yaw,int x,int y,int width,int height) {
        var pose = graphics.pose();
        int left = Math.round(pose.m00()*x + pose.m20());
        int top = Math.round(pose.m11()*y + pose.m21());
        int right = Math.round(pose.m00()*(x+width) + pose.m20());
        int bottom = Math.round(pose.m11()*(y+height) + pose.m21());
        var scissor = graphics.peekScissorStack();
        var bounds = PictureInPictureRenderState.getBounds(left, top, right, bottom, scissor);
        if (bounds == null) return;
        double cosYaw=Math.abs(Math.cos(yaw)), sinYaw=Math.abs(Math.sin(yaw));
        double horizontal=cosYaw*(coreWidth+2)+sinYaw*(coreLength+2);
        double depth=sinYaw*(coreWidth+2)+cosYaw*(coreLength+2);
        double vertical=Math.abs(Math.cos(pitch))*(layer>=0 ? 1 : coreHeight+2)+Math.abs(Math.sin(pitch))*depth;
        float scale=(float)Math.min((right-left-8)/horizontal,(bottom-top-8)/vertical);
        if (scale <= 0) return;
        graphics.nextStratum();
        graphics.submitPictureInPictureRenderState(new State(left,top,right,bottom,scale,scissor,bounds,
                blocks,coreWidth,coreHeight,coreLength,layer,pitch,yaw,simulation,editArea));
    }

    @Override public Class<State> getRenderStateClass() { return State.class; }
    @Override protected float getTranslateY(int height, int guiScale) { return height / 2f; }
    @Override protected String getTextureLabel() { return "Oritech reactor preview"; }

    @Override protected void renderToTexture(State state, PoseStack pose) {
        pose.mulPose(new Quaternionf().rotateX(state.pitch()).rotateY(state.yaw()));
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
        for (var block : state.blocks()) {
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
            if (state.layer()>=0 && simulation!=null && block.x()>0 && block.x()<=state.width()
                    && block.z()>0 && block.z()<=state.length()) {
                var cell = simulation.cell(block.x()-1,block.z()-1);
                if (cell!=null) {
                    int heatColor=SimulatorWidget.heatColor(cell.heat);
                    thermalTint=0xFF000000 | (((heatColor>>16&255)+255)/2<<16)
                            | (((heatColor>>8&255)+255)/2<<8) | ((heatColor&255)+255)/2;
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
        if (state.editArea()!=null) {
            // Draw after the model so the active editor area is visible through the casing.
            bufferSource.endBatch(renderType);
            drawEditArea(pose.last(),state);
        }
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
