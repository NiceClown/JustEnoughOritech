package com.justenoughoritech.client;

import com.justenoughoritech.sim.ReactorLayout;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;
import java.util.HashMap;
import java.util.List;

final class AssemblyPreview extends PictureInPictureRenderer<AssemblyPreview.State> {
    record State(int x0, int y0, int x1, int y1, float scale, ScreenRectangle scissorArea,
                 ScreenRectangle bounds, List<ReactorLayout.Placement> blocks, int width, int height,
                 int length, int layer) implements PictureInPictureRenderState {}

    AssemblyPreview(MultiBufferSource.BufferSource buffers) { super(buffers); }

    static void draw(GuiGraphicsExtractor graphics, List<ReactorLayout.Placement> blocks, ReactorLayout layout,
                     int layer, int x, int y, int width, int height) {
        var pose = graphics.pose();
        int left = Math.round(pose.m00()*x + pose.m20());
        int top = Math.round(pose.m11()*y + pose.m21());
        int right = Math.round(pose.m00()*(x+width) + pose.m20());
        int bottom = Math.round(pose.m11()*(y+height) + pose.m21());
        var scissor = graphics.peekScissorStack();
        var bounds = PictureInPictureRenderState.getBounds(left, top, right, bottom, scissor);
        if (bounds == null) return;
        float footprint = layout.width() + layout.length() + 4;
        float rise = layer >= 0 ? 1 : layout.height() + 2;
        float scale = Math.min((right-left-8)/footprint, (bottom-top-8)/(rise+footprint/2));
        if (scale <= 0) return;
        graphics.nextStratum();
        graphics.submitPictureInPictureRenderState(new State(left,top,right,bottom,scale,scissor,bounds,
                blocks,layout.width(),layout.height(),layout.length(),layer));
    }

    @Override public Class<State> getRenderStateClass() { return State.class; }
    @Override protected float getTranslateY(int height, int guiScale) { return height / 2f; }
    @Override protected String getTextureLabel() { return "Oritech reactor preview"; }

    @Override protected void renderToTexture(State state, PoseStack pose) {
        pose.mulPose(new Quaternionf().rotateX((float)Math.PI/6).rotateY((float)Math.PI/4));
        pose.translate(-(state.width()+2)/2f,
                state.layer() < 0 ? -(state.height()+2)/2f : -state.layer()-0.5f, -(state.length()+2)/2f);
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        var cache = new HashMap<String,List<BlockStateModelPart>>();
        var output = bufferSource.getBuffer(RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS));
        var quadInstance = new QuadInstance();
        var simulation = Workbench.INSTANCE.simulation;
        int maxHeat = ClientRuntimeConfig.config().maxHeat();
        quadInstance.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
        quadInstance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        for (var block : state.blocks()) {
            if (state.layer() >= 0 && block.y() != state.layer()) continue;
            if (state.layer() < 0 && block.x()>0 && block.x()<=state.width()
                    && block.y()>0 && block.y()<=state.height() && block.z()>0 && block.z()<=state.length()) continue;
            var parts = cache.computeIfAbsent(block.blockId(), id -> {
                var stateForBlock = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id)).defaultBlockState();
                var baked = new java.util.ArrayList<BlockStateModelPart>();
                models.get(stateForBlock).collectParts(net.minecraft.util.RandomSource.create(42),baked);
                return List.copyOf(baked);
            });
            pose.pushPose();
            pose.translate(block.x(), block.y(), block.z());
            int warmth = -1;
            if (state.layer()>=0 && simulation!=null && block.x()>0 && block.x()<=state.width()
                    && block.z()>0 && block.z()<=state.length()) {
                var cell = simulation.cell(block.x()-1,block.z()-1);
                if (cell!=null) warmth=Math.clamp((int)(cell.heat * 192d / Math.max(1,maxHeat)),0,192);
            }
            int thermalTint = warmth<0 ? 0xFFFFFFFF : 0xFFFF0000 | ((255-warmth)<<8) | (255-warmth);
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
    }
}
