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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

public final class SimulatorWidget implements IRecipeWidget, IJeiGuiEventListener {
    private static final int W = 420, H = 230, CELL = 17, GX = 6, GY = 40, GRID = 5;
    private static final int LIST_TOP = 165, LIST_HEIGHT = 59, LIST_ROWS = 5;
    private static final int EYE_X=244, EYE_Y=29;
    private static final int LIGHT = 0xFFF1F3F5, MUTED = 0xFFB8C4D0, ALERT = 0xFFFF8176;
    private static final int COOL=0xFF418C50, WARM=0xFFBE9D3D, HOT=0xFFFF414A, SELECTED=0xFFFFDA66;
    private static final int WARM_THRESHOLD=65, HOT_THRESHOLD=100;
    private static final Workbench WB = Workbench.INSTANCE;
    private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
    private static final Identifier HOVER = Identifier.withDefaultNamespace("widget/button_highlighted");
    private static final Identifier DISABLED = Identifier.withDefaultNamespace("widget/button_disabled");
    private static GridViewport gridViewport;
    private final float scale;
    private final TextViewport textViewport = new TextViewport();
    private int previewRevision = -1;
    private int previewLayer = -2;
    private List<ReactorLayout.Placement> blueprint = List.of();
    private List<ReactorLayout.Placement> preview = List.of();
    private boolean draggingBar;

    private record GridViewport(Screen screen,double left,double top,double right,double bottom) {
        boolean contains(double x,double y) { return x>=left && x<right && y>=top && y<bottom; }
    }

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
            gridViewport=new GridViewport(Minecraft.getInstance().screen,
                    pose.m20()+GX*pose.m00(),pose.m21()+GY*pose.m11(),
                    pose.m20()+(GX+GRID*CELL)*pose.m00(),pose.m21()+(GY+GRID*CELL)*pose.m11());
            graphics.enableScissor(0,0,W,H);
            try {
                drawEditor(graphics);
                for (var button : buttons()) button.draw(graphics,textViewport,mouseX/scale,mouseY/scale);
                drawEye(graphics);
            } finally {
                graphics.disableScissor();
            }
        } finally {
            pose.popMatrix();
        }
    }

    static void clearGridViewport(ScreenEvent.Render.Pre event) {
        gridViewport=null;
    }

    static void handleGridScroll(ScreenEvent.MouseScrolled.Pre event) {
        var viewport=gridViewport;
        // JEI handles category navigation before it offers the wheel to recipe widgets.
        if (viewport!=null && event.getScreen()==viewport.screen()
                && viewport.contains(event.getMouseX(),event.getMouseY())
                && panGrid(event.getScrollDeltaX(),event.getScrollDeltaY())) event.setCanceled(true);
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
        text(g,4,16,128,status, compatibility == ClientRuntimeConfig.Compatibility.VERIFIED ? 0xFF89D39D : ALERT);
        drawHeatLegend(g);

        text(g,GX,30,96,tr("interior",WB.layout.width(),WB.layout.length()),LIGHT);
        drawGrid(g);
        var selected = WB.selected().block();
        text(g,GX,127,96,tr("selected",selected == null ? tr("empty") : selected.getName()),LIGHT);
        text(g,GX,192,96,tr("layer_number",WB.selectedLayer+1,WB.layout.height()),MUTED);
        text(g,GX,203,96,tr("ports",WB.layout.energyPorts()),MUTED);

        text(g,110,31,128,tr(WB.layersMode ? "preview_layer" : "preview_3d"),LIGHT);
        int editWidth=Math.min(GRID,WB.layout.width()-WB.gridOffsetX);
        int editLength=Math.min(GRID,WB.layout.length()-WB.gridOffsetZ);
        text(g,110,43,148,tr("edit_area_coords",WB.gridOffsetX+1,WB.gridOffsetX+editWidth,
                WB.gridOffsetZ+1,WB.gridOffsetZ+editLength),MUTED);
        int layer = WB.layersMode ? WB.selectedLayer+1 : -1;
        if (previewRevision != WB.revision()) {
            blueprint = WB.layout.placements();
            previewRevision = WB.revision();
            previewLayer = -2;
        }
        if (previewLayer != layer) {
            previewLayer = layer;
            preview = layer>=0 ? blueprint.stream().filter(block -> block.y()==layer).toList()
                    : AssemblyPreview.shell(blueprint,WB.layout.width(),WB.layout.height(),WB.layout.length());
        }
        var editArea=WB.showEditArea ? new AssemblyPreview.EditArea(WB.gridOffsetX+1,WB.gridOffsetZ+1,
                editWidth,editLength,WB.selectedLayer+2) : null;
        AssemblyPreview.draw(g,preview,WB.layout.width(),WB.layout.height(),WB.layout.length(),layer,WB.simulation,
                editArea,AssemblyPreview.DEFAULT_PITCH,AssemblyPreview.DEFAULT_YAW,109,53,150,102);
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
            if (cx == WB.cursorX && cz == WB.cursorZ) g.outline(px,py,CELL-1,CELL-1,SELECTED);
        }
    }

    private void drawHeatLegend(GuiGraphicsExtractor g) {
        var font=Minecraft.getInstance().font;
        var label=tr("heat_legend");
        int x=134;
        g.text(font,label,x,16,MUTED,false);
        x+=font.width(label)+7;
        x=legendEntry(g,x,COOL,tr("heat_legend_cool",WARM_THRESHOLD),false);
        x=legendEntry(g,x,WARM,tr("heat_legend_warm",WARM_THRESHOLD,HOT_THRESHOLD),false);
        x=legendEntry(g,x,HOT,tr("heat_legend_hot",HOT_THRESHOLD),false);
        legendEntry(g,x,SELECTED,tr("heat_legend_selected"),true);
    }

    private int legendEntry(GuiGraphicsExtractor g,int x,int color,Component label,boolean outline) {
        if (outline) g.outline(x,16,7,7,color);
        else g.fill(x,16,x+7,23,color);
        int width=Minecraft.getInstance().font.width(label);
        text(g,x+10,16,Math.min(width,414-x-10),label,MUTED);
        return x+width+18;
    }

    static int heatColor(int heat) {
        long maximum=Math.max(1,ClientRuntimeConfig.config().maxHeat());
        long scaled=(long)heat*100;
        return scaled>=maximum*HOT_THRESHOLD ? HOT : scaled>=maximum*WARM_THRESHOLD ? WARM : COOL;
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
        drawConsumption(g,205,"fuel",sim.fuelItems());
        drawConsumption(g,217,"coolant",sim.coolantItems());
    }

    private void drawConsumption(GuiGraphicsExtractor g,int y,String resource,int count) {
        var font=Minecraft.getInstance().font;
        String amount=Integer.toString(count);
        g.text(font,tr(resource),270,y,MUTED);
        g.text(font,amount,412-font.width(amount),y,LIGHT);
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

    private void drawEye(GuiGraphicsExtractor g) {
        int x=EYE_X+3,y=EYE_Y+3;
        g.fill(x+2,y,x+6,y+1,LIGHT);
        g.fill(x+1,y+1,x+7,y+2,LIGHT);
        g.fill(x,y+2,x+2,y+4,LIGHT);
        g.fill(x+3,y+2,x+5,y+4,LIGHT);
        g.fill(x+6,y+2,x+8,y+4,LIGHT);
        g.fill(x+1,y+4,x+7,y+5,LIGHT);
        g.fill(x+2,y+5,x+6,y+6,LIGHT);
        if (!WB.showEditArea) {
            for (int i=0;i<8;i++) {
                g.fill(x+i,y+6-i,x+i+1,y+8-i,0xFF293039);
                g.fill(x+i,y+6-i,x+i+1,y+7-i,LIGHT);
            }
        }
    }

    private record Control(int x,int y,int w,int h,Component label,Component tooltip,boolean enabled,Runnable action,
                           boolean variableStep) {
        Control(int x,int y,int w,int h,Component label,Component tooltip,boolean enabled,Runnable action) {
            this(x,y,w,h,label,tooltip,enabled,action,false);
        }
        boolean at(double mx,double my) { return mx>=x && mx<x+w && my>=y && my<y+h; }
        void draw(GuiGraphicsExtractor g,TextViewport viewport,double mx,double my) {
            g.blitSprite(RenderPipelines.GUI_TEXTURED,!enabled ? DISABLED : at(mx,my) ? HOVER : BUTTON,x,y,w,h);
            viewport.line(g,Minecraft.getInstance().font,label,x+3,y+(h-8)/2,w-6,enabled ? LIGHT : MUTED,true);
        }
    }

    private Control control(int x,int y,int width,String key,Runnable action) {
        return new Control(x,y,width,14,tr(key),tr("control."+key),true,action);
    }

    private Control arrow(int x,int y,String label,String actionKey,Runnable action) {
        return new Control(x,y,16,14,Component.literal(label),tr("control."+actionKey),true,action);
    }

    private Control stepControl(int x,int y,String key,IntConsumer action) {
        return new Control(x,y,22,14,tr(key),tr("control."+key),true,() -> {
            var mc=Minecraft.getInstance();
            action.accept(mc.hasControlDown() ? 50 : mc.hasShiftDown() ? 10 : 1);
        },true);
    }

    private List<Control> buttons() {
        var controls = new ArrayList<Control>();
        controls.add(stepControl(6,139,"width_minus",step -> resize(-step,0)));
        controls.add(stepControl(29,139,"width_plus",step -> resize(step,0)));
        controls.add(stepControl(52,139,"length_minus",step -> resize(0,-step)));
        controls.add(stepControl(75,139,"length_plus",step -> resize(0,step)));
        controls.add(stepControl(6,155,"height_minus",step -> height(-step)));
        controls.add(stepControl(29,155,"height_plus",this::height));
        controls.add(control(52,155,45,WB.layersMode ? "assembly" : "layer",() -> WB.layersMode = !WB.layersMode));
        controls.add(arrow(6,173,"<","part_previous",() -> WB.cyclePart(-1)));
        controls.add(arrow(24,173,">","part_next",() -> WB.cyclePart(1)));
        controls.add(control(43,173,54,"ghost",ProjectionController::begin));
        controls.add(arrow(6,213,"<","layer_previous",() -> layer(-1)));
        controls.add(arrow(24,213,">","layer_next",() -> layer(1)));
        controls.add(stepControl(51,213,"port_minus",step -> ports(-step)));
        controls.add(stepControl(75,213,"port_plus",this::ports));
        controls.add(new Control(EYE_X,EYE_Y,14,13,Component.empty(),
                tr(WB.showEditArea ? "edit_area_hide" : "edit_area_show"),true,() -> WB.showEditArea=!WB.showEditArea));
        controls.add(arrow(270,50,"<","fuel_previous",() -> fuel(-1)));
        controls.add(arrow(396,50,">","fuel_next",() -> fuel(1)));
        controls.add(arrow(270,99,"<","coolant_previous",() -> coolant(-1)));
        controls.add(arrow(396,99,">","coolant_next",() -> coolant(1)));
        boolean ready = WB.inputs.available() && ClientRuntimeConfig.compatibility() != ClientRuntimeConfig.Compatibility.INCOMPATIBLE;
        controls.add(new Control(270,124,42,14,tr(WB.running?"pause":"run"),tr(WB.running?"control.pause":"control.run"),ready,() -> {
            if (WB.running) WB.pause(); else WB.start();
        }));
        controls.add(control(314,124,46,"reset",WB::reset));
        controls.add(control(362,124,51,WB.blocksMode?"results":"blocks",() -> WB.blocksMode = !WB.blocksMode));
        controls.add(new Control(110,209,65,16,tr("auto"),tr("control.auto"),ready&&!WB.designing(),() -> {
            var mc=Minecraft.getInstance();
            mc.setScreen(new AutoDesignScreen(mc.screen));
        }));
        controls.add(new Control(177,209,81,16,tr("print_short"),tr("control.print"),ready,ProjectionController::sendToPrinter));
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
            tooltip.add(button.tooltip());
            if (button.variableStep()) tooltip.add(tr("control.step"));
            return;
        }
        if (x>=134 && x<414 && y>=15 && y<25) {
            tooltip.add(tr("heat_legend_tip",ClientRuntimeConfig.config().maxHeat()));
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
        if (grid(x,y)) {
            return panGrid(dx,dy);
        }
        if (list(x,y) && dy!=0) {
            int delta=dy<0 ? 1 : -1;
            WB.blockOffset = Math.clamp(WB.blockOffset+delta,0,Math.max(0,blockList().size()-LIST_ROWS));
            return true;
        }
        return false;
    }

    private static boolean panGrid(double dx,double dy) {
        if (dx==0 && dy==0) return false;
        boolean horizontalWheel=Math.abs(dx)>Math.abs(dy);
        int delta=(horizontalWheel ? dx : dy)<0 ? 1 : -1;
        if (horizontalWheel || Minecraft.getInstance().hasShiftDown())
            WB.gridOffsetX=Math.clamp(WB.gridOffsetX+delta,0,Math.max(0,WB.layout.width()-GRID));
        else WB.gridOffsetZ=Math.clamp(WB.gridOffsetZ+delta,0,Math.max(0,WB.layout.length()-GRID));
        return true;
    }

    @Override public void tick() { WB.tick(); }
}
