package com.justenoughoritech.client;

import com.justenoughoritech.sim.ReactorLayout;
import com.justenoughoritech.sim.ReactorParts;
import com.justenoughoritech.sim.ReactorSimulation;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.inputs.IJeiGuiEventListener;
import mezz.jei.api.gui.widgets.IRecipeWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SimulatorWidget implements IRecipeWidget, IJeiGuiEventListener {
    private static final int W = 420, H = 230, CELL = 17, GX = 6, GY = 40, GRID = 5;
    private static final int LIST_TOP = 165, LIST_HEIGHT = 59, LIST_ROWS = 5;
    private static final int LIGHT = 0xFFF1F3F5, MUTED = 0xFFB8C4D0, ALERT = 0xFFFF8176;
    private static final Workbench WB = Workbench.INSTANCE;
    private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
    private static final Identifier HOVER = Identifier.withDefaultNamespace("widget/button_highlighted");
    private static final Identifier DISABLED = Identifier.withDefaultNamespace("widget/button_disabled");
    private final float scale;
    private final TextViewport textViewport = new TextViewport();
    private int previewRevision = -1;
    private int previewLayer = -2;
    private List<ReactorLayout.Placement> blueprint = List.of();
    private List<ReactorLayout.Placement> preview = List.of();
    private boolean draggingBar;

    public SimulatorWidget() { this(displayScale()); }
    SimulatorWidget(float scale) { this.scale = scale; }

    private static float displayScale() {
        var window = Minecraft.getInstance().getWindow();
        return Math.min(1f, Math.min(Math.max(1, window.getGuiScaledWidth() - 100)/(float)W,
                Math.max(1, window.getGuiScaledHeight() - 112)/(float)H));
    }

    public static int displayWidth() { return (int)Math.ceil(W * displayScale()); }
    public static int displayHeight() { return (int)Math.ceil(H * displayScale()); }
    @Override public ScreenPosition getPosition() { return new ScreenPosition(0,0); }
    @Override public ScreenRectangle getArea() { return new ScreenRectangle(0,0,(int)Math.ceil(W*scale),(int)Math.ceil(H*scale)); }

    static Component tr(String key, Object... args) {
        return Component.translatable("gui.just_enough_oritech." + key, args);
    }

    @Override public void drawWidget(GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
        WB.refreshParts();
        WB.refreshInputs();
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.scale(scale);
        try {
            graphics.enableScissor(0,0,W,H);
            try {
                drawEditor(graphics);
                for (var button : buttons()) button.draw(graphics,textViewport,mouseX/scale,mouseY/scale);
            } finally {
                graphics.disableScissor();
            }
        } finally {
            pose.popMatrix();
        }
    }

    private void drawEditor(GuiGraphicsExtractor g) {
        g.fill(0,0,W,H,0xFF20252B);
        g.fill(2,28,104,228,0xFF293039);
        g.fill(106,28,262,228,0xFF252B32);
        g.fill(264,28,418,228,0xFF293039);
        text(g,4,4,410,Component.translatable("jei.just_enough_oritech.reactor.title"),LIGHT);
        var snapshot = ClientRuntimeConfig.snapshot();
        var compatibility = ClientRuntimeConfig.compatibility();
        Component status = switch (compatibility) {
            case WAITING -> tr("waiting");
            case VERIFIED -> Component.literal(snapshot.oritechVersion());
            case UNVERIFIED -> Component.empty().append(tr("unverified")).append(" ").append(snapshot.oritechVersion());
            case INCOMPATIBLE -> tr("incompatible", issueText(ClientRuntimeConfig.runtimeIssue()));
        };
        text(g,4,16,410,status, compatibility == ClientRuntimeConfig.Compatibility.VERIFIED ? 0xFF89D39D : ALERT);

        text(g,GX,30,96,tr("interior",WB.layout.width(),WB.layout.length()),LIGHT);
        drawGrid(g);
        var selected = WB.selected().block();
        text(g,GX,127,96,tr("selected",selected == null ? tr("empty") : selected.getName()),LIGHT);
        text(g,GX,192,96,tr("layer_number",WB.selectedLayer+1,WB.layout.height()),MUTED);
        text(g,GX,203,96,tr("ports",WB.layout.energyPorts()),MUTED);

        text(g,110,31,148,tr(WB.layersMode ? "preview_layer" : "preview_3d"),LIGHT);
        int layer = WB.layersMode ? WB.selectedLayer+1 : -1;
        if (previewRevision != WB.revision()) {
            blueprint = WB.layout.placements();
            previewRevision = WB.revision();
            previewLayer = -2;
        }
        if (previewLayer != layer) {
            previewLayer = layer;
            preview = blueprint.stream().filter(block -> layer >= 0 ? block.y()==layer
                    : block.x()==0 || block.x()==WB.layout.width()+1 || block.z()==0
                    || block.z()==WB.layout.length()+1 || block.y()==0 || block.y()==WB.layout.height()+1).toList();
        }
        AssemblyPreview.draw(g,preview,WB.layout,layer,109,43,150,112);
        text(g,110,157,148,tr("heat_history"),MUTED);
        drawHeatGraph(g);
        if (WB.designStatus != null) paragraph(g,110,190,148,2,WB.designStatus,MUTED);

        var fuel = WB.inputs.fuels().isEmpty() ? null
                : WB.inputs.fuels().get(Math.floorMod(WB.selectedFuel, WB.inputs.fuels().size()));
        var coolant = WB.inputs.coolants().isEmpty() ? ItemStack.EMPTY
                : WB.inputs.coolants().get(Math.floorMod(WB.selectedCoolant, WB.inputs.coolants().size()));
        resource(g,30,"fuel",fuel == null ? ItemStack.EMPTY : fuel.item());
        text(g,270,67,141,fuel == null ? tr("none") : tr("fuel_ticks",fuel.duration()),MUTED);
        resource(g,79,"coolant",coolant);
        if (WB.blocksMode) drawBlockList(g);
        else drawReport(g,compatibility);
    }

    private void resource(GuiGraphicsExtractor g, int y, String key, ItemStack stack) {
        text(g,270,y+4,98,tr(key),LIGHT);
        if (!stack.isEmpty()) g.fakeItem(stack,392,y);
        text(g,289,y+23,100,stack.isEmpty() ? tr("none") : stack.getHoverName(),LIGHT);
    }

    private void drawGrid(GuiGraphicsExtractor g) {
        for (int z=0;z<GRID;z++) for (int x=0;x<GRID;x++) {
            int cx = WB.gridOffsetX+x, cz = WB.gridOffsetZ+z;
            int px = GX+x*CELL, py = GY+z*CELL;
            boolean valid = cx<WB.layout.width() && cz<WB.layout.length();
            g.fill(px,py,px+CELL-1,py+CELL-1,valid ? 0xFF3A444E : 0xFF20272E);
            if (!valid) continue;
            if (WB.simulation != null && WB.simulation.cell(cx,cz) != null)
                g.fill(px,py,px+CELL-1,py+CELL-1,heatColor(WB.simulation.cell(cx,cz).heat));
            var part = WB.layout.get(cx,cz);
            if (part.block() != null) g.fakeItem(new ItemStack(part.block()),px,py);
            if (cx == WB.cursorX && cz == WB.cursorZ) g.outline(px,py,CELL-1,CELL-1,0xFFFFDA66);
        }
    }

    private int heatColor(int heat) {
        float f = Math.clamp(heat/(float)Math.max(1,ClientRuntimeConfig.config().maxHeat()),0f,1f);
        return 0xFF000000 | ((int)(65+190*f)<<16) | ((int)(140-95*f)<<8) | (int)(80-40*f);
    }

    private void drawHeatGraph(GuiGraphicsExtractor g) {
        g.fill(110,169,258,187,0xFF161B20);
        if (WB.simulation == null) return;
        int[] history = WB.simulation.heatHistory();
        for (int i=0;i<history.length;i++) {
            int bar = Math.clamp((int)(17d*history[i]/Math.max(1,ClientRuntimeConfig.config().maxHeat())),1,17);
            int x = 110+i*148/Math.max(1,history.length);
            g.fill(x,186-bar,110+(i+1)*148/history.length,186,heatColor(history[i]));
        }
    }

    private void drawReport(GuiGraphicsExtractor g, ClientRuntimeConfig.Compatibility compatibility) {
        if (compatibility == ClientRuntimeConfig.Compatibility.WAITING) {
            paragraph(g,270,145,143,3,tr("waiting"),MUTED);
            return;
        }
        if (compatibility == ClientRuntimeConfig.Compatibility.INCOMPATIBLE) {
            paragraph(g,270,145,143,4,issueText(ClientRuntimeConfig.runtimeIssue()),ALERT);
            paragraph(g,270,185,143,3,tr("install",ClientRuntimeConfig.latestCompatible()),ALERT);
            return;
        }
        var sim = WB.simulation;
        String state = sim == null ? "ready" : sim.status().name().toLowerCase(java.util.Locale.ROOT);
        text(g,270,145,143,tr("state",tr("state."+state)),LIGHT);
        if (sim == null) {
            paragraph(g,270,158,143,6,WB.issue == null ? tr("empty_run") : issueText(WB.issue),MUTED);
            return;
        }
        text(g,270,157,143,tr("power",sim.generation()),LIGHT);
        text(g,270,169,143,tr("export",sim.exportRate()),MUTED);
        text(g,270,181,143,tr("peak",sim.maxHeatSeen(),ClientRuntimeConfig.config().maxHeat()),heatColor(sim.maxHeatSeen()));
        Component risk = switch (sim.status()) {
            case MELTDOWN -> tr("meltdown");
            case SAFE_COOLDOWN -> tr("cooldown",ClientRuntimeConfig.config().safeModeCooldown());
            case STEADY -> tr("steady",sim.ticks());
            default -> sim.unstableTicks() > 0 ? tr("failure",sim.ticksToMeltdown()) : tr("no_risk");
        };
        text(g,270,193,143,risk,sim.unstableTicks()>0 ? ALERT : MUTED);
        text(g,270,207,143,tr("consumption",sim.fuelItems(),sim.coolantItems()),MUTED);
    }

    private static Component issueText(String issue) {
        if (issue == null || issue.isBlank()) return tr("missing_data");
        if (issue.startsWith("gui.just_enough_oritech.")) return Component.translatable(issue);
        return Component.literal(issue);
    }

    private List<Map.Entry<String,Integer>> blockList() {
        return WB.layout.blockCounts().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    }

    private void drawBlockList(GuiGraphicsExtractor g) {
        var entries = blockList();
        int max = Math.max(0,entries.size()-LIST_ROWS);
        WB.blockOffset = Math.clamp(WB.blockOffset,0,max);
        text(g,270,149,143,tr("total",entries.stream().mapToInt(Map.Entry::getValue).sum()),LIGHT);
        g.fill(270,LIST_TOP,414,LIST_TOP+LIST_HEIGHT,0xFF1B2127);
        for (int i=0;i<LIST_ROWS && WB.blockOffset+i<entries.size();i++) {
            var entry = entries.get(WB.blockOffset+i);
            var name = BuiltInRegistries.BLOCK.getValue(Identifier.parse(entry.getKey())).getName();
            text(g,272,LIST_TOP+2+i*11,99,name,LIGHT);
            String count = Integer.toString(entry.getValue());
            var font = Minecraft.getInstance().font;
            text(g,402-font.width(count),LIST_TOP+2+i*11,30,Component.literal(count),MUTED);
        }
        int slider = sliderHeight(entries.size());
        int top = LIST_TOP+(max==0 ? 0 : (LIST_HEIGHT-slider)*WB.blockOffset/max);
        g.fill(406,LIST_TOP,414,LIST_TOP+LIST_HEIGHT,0xFF11161A);
        g.blitSprite(RenderPipelines.GUI_TEXTURED,max == 0 ? DISABLED : BUTTON,406,top,8,slider);
    }

    private int sliderHeight(int count) {
        return Math.max(10,LIST_HEIGHT*LIST_ROWS/Math.max(LIST_ROWS,count));
    }

    private void scrollBar(double y) {
        int size = blockList().size(), max = Math.max(0,size-LIST_ROWS);
        int height = sliderHeight(size);
        WB.blockOffset = max == 0 ? 0 : Math.clamp((int)Math.round((y-LIST_TOP-height/2d)*max/(LIST_HEIGHT-height)),0,max);
    }

    private record Control(int x,int y,int w,int h,Component label,boolean enabled,Runnable action) {
        boolean at(double mx,double my) { return mx>=x && mx<x+w && my>=y && my<y+h; }
        void draw(GuiGraphicsExtractor g,TextViewport viewport,double mx,double my) {
            g.blitSprite(RenderPipelines.GUI_TEXTURED,!enabled ? DISABLED : at(mx,my) ? HOVER : BUTTON,x,y,w,h);
            viewport.line(g,Minecraft.getInstance().font,label,x+3,y+(h-8)/2,w-6,enabled ? LIGHT : MUTED,true);
        }
    }

    private Control control(int x,int y,int width,String key,Runnable action) {
        return new Control(x,y,width,14,tr(key),true,action);
    }

    private Control arrow(int x,int y,String label,Runnable action) {
        return new Control(x,y,16,14,Component.literal(label),true,action);
    }

    private List<Control> buttons() {
        var controls = new ArrayList<Control>();
        controls.add(control(6,139,22,"width_minus",() -> resize(-1,0)));
        controls.add(control(29,139,22,"width_plus",() -> resize(1,0)));
        controls.add(control(52,139,22,"length_minus",() -> resize(0,-1)));
        controls.add(control(75,139,22,"length_plus",() -> resize(0,1)));
        controls.add(control(6,155,22,"height_minus",() -> height(-1)));
        controls.add(control(29,155,22,"height_plus",() -> height(1)));
        controls.add(control(52,155,45,WB.layersMode ? "assembly" : "layer",() -> WB.layersMode = !WB.layersMode));
        controls.add(arrow(6,173,"<",() -> WB.cyclePart(-1)));
        controls.add(arrow(24,173,">",() -> WB.cyclePart(1)));
        controls.add(control(43,173,54,"ghost",ProjectionController::begin));
        controls.add(arrow(6,213,"<",() -> layer(-1)));
        controls.add(arrow(24,213,">",() -> layer(1)));
        controls.add(control(51,213,22,"port_minus",() -> ports(-1)));
        controls.add(control(75,213,22,"port_plus",() -> ports(1)));
        controls.add(arrow(270,50,"<",() -> fuel(-1)));
        controls.add(arrow(396,50,">",() -> fuel(1)));
        controls.add(arrow(270,99,"<",() -> coolant(-1)));
        controls.add(arrow(396,99,">",() -> coolant(1)));
        boolean ready = WB.inputs.available() && ClientRuntimeConfig.compatibility() != ClientRuntimeConfig.Compatibility.INCOMPATIBLE;
        controls.add(new Control(270,124,42,14,tr(WB.running?"pause":"run"),ready,() -> {
            if (WB.running) WB.pause(); else WB.start();
        }));
        controls.add(control(314,124,46,"reset",WB::reset));
        controls.add(control(362,124,51,WB.blocksMode?"results":"blocks",() -> WB.blocksMode = !WB.blocksMode));
        controls.add(new Control(110,209,65,16,tr("auto"),ready&&!WB.designing(),() -> {
            var mc=Minecraft.getInstance();
            mc.setScreen(new AutoDesignScreen(mc.screen));
        }));
        controls.add(new Control(177,209,81,16,tr("print_short"),ready,ProjectionController::sendToPrinter));
        return controls;
    }

    private void resize(int dx,int dz) {
        WB.layout.resize(WB.layout.width()+dx,WB.layout.length()+dz,ClientRuntimeConfig.config().maxSize());
        WB.edited();
    }
    private void height(int dy) {
        WB.layout.setHeight(WB.layout.height()+dy,ClientRuntimeConfig.config().maxSize());
        WB.edited();
    }
    private void layer(int delta) { WB.selectedLayer = Math.floorMod(WB.selectedLayer+delta,WB.layout.height()); }
    private void ports(int delta) { WB.layout.setEnergyPorts(WB.layout.energyPorts()+delta); WB.edited(); }
    private void fuel(int delta) { WB.selectedFuel += delta; WB.edited(); }
    private void coolant(int delta) { WB.selectedCoolant += delta; WB.edited(); }

    private void text(GuiGraphicsExtractor g,int x,int y,int width,Component label,int color) {
        textViewport.line(g,Minecraft.getInstance().font,label,x,y,width,color,false);
    }

    private void paragraph(GuiGraphicsExtractor g,int x,int y,int width,int rows,Component label,int color) {
        textViewport.paragraph(g,Minecraft.getInstance().font,label,x,y,width,rows,color);
    }

    private static boolean grid(double x,double y) { return x>=GX && x<GX+GRID*CELL && y>=GY && y<GY+GRID*CELL; }
    private static boolean list(double x,double y) { return WB.blocksMode && x>=270 && x<414 && y>=LIST_TOP && y<LIST_TOP+LIST_HEIGHT; }

    @Override public void getTooltip(ITooltipBuilder tooltip,double mx,double my) {
        double x = mx/scale, y = my/scale;
        for (var button : buttons()) {
            if (!button.at(x,y)) continue;
            tooltip.add(button.x()==177 && button.y()==209 ? tr("print") : button.label());
            return;
        }
        if (grid(x,y)) {
            tooltip.add(tr("grid_tip"));
            return;
        }
        if (x>=289 && x<390 && y>=50 && y<65 && !WB.inputs.fuels().isEmpty()) {
            tooltip.add(WB.inputs.fuels().get(Math.floorMod(WB.selectedFuel,WB.inputs.fuels().size())).item().getHoverName());
            return;
        }
        if (x>=289 && x<390 && y>=99 && y<114 && !WB.inputs.coolants().isEmpty()) {
            tooltip.add(WB.inputs.coolants().get(Math.floorMod(WB.selectedCoolant,WB.inputs.coolants().size())).getHoverName());
            return;
        }
        if (x>=GX && x<103 && y>=127 && y<137 && WB.selected().block()!=null) {
            tooltip.add(WB.selected().block().getName());
            return;
        }
        if (x>=110 && x<258 && y>=190 && y<208 && WB.designStatus != null) {
            tooltip.add(WB.designStatus);
            return;
        }
        if (list(x,y) && x<406) {
            var entries = blockList();
            int row = WB.blockOffset+((int)y-LIST_TOP)/11;
            if (row<entries.size())
                tooltip.add(BuiltInRegistries.BLOCK.getValue(Identifier.parse(entries.get(row).getKey())).getName());
        }
    }

    @Override public boolean mouseClicked(double mx,double my,int button) {
        double x = mx/scale, y = my/scale;
        if ((button==0 || button==1) && grid(x,y)) {
            int cx = WB.gridOffsetX+(int)(x-GX)/CELL, cz = WB.gridOffsetZ+(int)(y-GY)/CELL;
            if (cx<WB.layout.width() && cz<WB.layout.length()) {
                WB.cursorX=cx;
                WB.cursorZ=cz;
                WB.layout.set(cx,cz,button==1 ? ReactorParts.Part.EMPTY : WB.selected());
                WB.edited();
            }
            return true;
        }
        if (button != 0) return false;
        if (list(x,y) && x>=406) {
            draggingBar = true;
            scrollBar(y);
            return true;
        }
        for (var control : buttons()) if (control.at(x,y)) {
            if (control.enabled()) control.action().run();
            return true;
        }
        return false;
    }

    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        if (!draggingBar || button!=0) return false;
        scrollBar(y/scale);
        return true;
    }

    @Override public boolean mouseReleased(double x,double y,int button) {
        if (!draggingBar || button!=0) return false;
        draggingBar = false;
        return true;
    }

    @Override public boolean mouseScrolled(double mx,double my,double dx,double dy) {
        double x=mx/scale,y=my/scale;
        int delta=dy<0 ? 1 : -1;
        if (grid(x,y)) {
            if (Minecraft.getInstance().hasShiftDown()) WB.gridOffsetX=Math.clamp(WB.gridOffsetX+delta,0,Math.max(0,WB.layout.width()-GRID));
            else WB.gridOffsetZ=Math.clamp(WB.gridOffsetZ+delta,0,Math.max(0,WB.layout.length()-GRID));
            return true;
        }
        if (list(x,y)) {
            WB.blockOffset = Math.clamp(WB.blockOffset+delta,0,Math.max(0,blockList().size()-LIST_ROWS));
            return true;
        }
        return false;
    }

    @Override public void tick() { WB.tick(); }
}
