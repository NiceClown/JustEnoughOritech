package com.justenoughoritech.client;

import com.justenoughoritech.sim.ReactorDesigner;
import com.justenoughoritech.sim.ReactorParts;
import com.justenoughoritech.sim.ReactorSimulation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.justenoughoritech.client.SimulatorWidget.tr;

final class AutoDesignScreen extends Screen {
    private static final Workbench WB=Workbench.INSTANCE;
    private static final int LIGHT=0xFFF1F3F5, MUTED=0xFFB8C4D0, WARNING=0xFFFF9A83;
    private static final int HEIGHT=244;
    private final Screen parent;
    private final TextViewport text=new TextViewport();
    private final List<ReactorParts.Part> rods;
    private final Set<String> allowed=new LinkedHashSet<>();
    private final List<Button> rodButtons=new ArrayList<>();
    private final List<Integer> rodPositions=new ArrayList<>();
    private ReactorDesigner.Risk risk=ReactorDesigner.Risk.NORMAL;
    private ReactorDesigner.CoolingMode coolingMode=ReactorDesigner.CoolingMode.AUTO;
    private ReactorDesigner.Result result;
    private ReactorDesigner.Request completedRequest;
    private DesignGuard guard;
    private String targetText="";
    private EditBox target;
    private CycleButton<ReactorDesigner.Risk> riskButton;
    private CycleButton<ReactorDesigner.CoolingMode> coolingButton;
    private Button search,resize,applySafe,applyDanger;
    private boolean busy,attempted,allowResize;
    private long searchToken;
    private int left,top,panelWidth,coolingWidth;

    AutoDesignScreen(Screen parent) {
        super(tr("auto_title"));
        this.parent=parent;
        rods=WB.parts.stream().filter(p -> p.kind()==ReactorParts.Kind.ROD)
                .sorted(java.util.Comparator.comparingInt(ReactorParts.Part::rodCount)).toList();
        var last=WB.lastRequest;
        if (last==null) rods.forEach(rod -> allowed.add(rod.id()));
        else {
            rods.stream().map(ReactorParts.Part::id).filter(last.allowedRods()::contains).forEach(allowed::add);
            risk=last.risk();
            coolingMode=last.coolingMode();
            allowResize=last.allowResize();
            if (last.target()>0) targetText=Long.toString(last.target());
        }
    }

    @Override protected void init() {
        super.init();
        panelWidth=Math.min(328,width-12);
        left=(width-panelWidth)/2;
        top=Math.max(2,(height-HEIGHT)/2);
        int inputWidth=panelWidth/2-16;
        target=addRenderableWidget(new EditBox(font,left+8,top+47,inputWidth,18,tr("auto_target")));
        target.setMaxLength(15);
        target.setFilter(value -> value.chars().allMatch(c -> c>='0' && c<='9'));
        target.setValue(targetText);
        target.setResponder(value -> {targetText=value; invalidateResult();});

        riskButton=addRenderableWidget(CycleButton.builder(AutoDesignScreen::riskName,risk)
                .withValues(ReactorDesigner.Risk.values()).displayOnlyValue()
                .withTooltip(value -> Tooltip.create(tr("auto_risk_note."+value.name().toLowerCase(Locale.ROOT))))
                .create(left+panelWidth/2,top+47,panelWidth/2-8,18,tr("auto_risk"),(button,value) -> {
                    risk=value;
                    invalidateResult();
                }));
        rodButtons.clear();
        rodPositions.clear();
        coolingWidth=Math.min(96,panelWidth/3);
        int available=panelWidth-24-coolingWidth;
        int columnWidth=Math.min(76,available/Math.max(1,rods.size()));
        for (int i=0;i<rods.size();i++) {
            var rod=rods.get(i);
            int x=left+8+i*columnWidth;
            rodPositions.add(x);
            var button=addRenderableWidget(Button.builder(rodToggle(rod),control -> {
                if (!allowed.remove(rod.id())) allowed.add(rod.id());
                control.setMessage(rodToggle(rod));
                invalidateResult();
            }).bounds(x+19,top+83,Math.max(24,columnWidth-21),18).build());
            button.setTooltip(Tooltip.create(rod.block().getName()));
            rodButtons.add(button);
        }
        coolingButton=addRenderableWidget(CycleButton.builder(AutoDesignScreen::coolingName,coolingMode)
                .withValues(ReactorDesigner.CoolingMode.values()).displayOnlyValue()
                .withTooltip(value -> Tooltip.create(tr("auto_cooling_tip")))
                .create(left+panelWidth-coolingWidth-8,top+83,coolingWidth,18,tr("auto_cooling"),(button,value) -> {
                    coolingMode=value;
                    invalidateResult();
                }));

        search=addRenderableWidget(Button.builder(tr("auto_search"),button -> search())
                .bounds(left+8,top+108,104,18).build());
        resize=addRenderableWidget(Button.builder(resizeLabel(),button -> {
            allowResize=!allowResize;
            button.setMessage(resizeLabel());
            invalidateResult();
        }).bounds(left+120,top+108,panelWidth-128,18)
                .tooltip(Tooltip.create(tr("auto_resize_tip",ReactorDesigner.growthLimit(ClientRuntimeConfig.config().maxSize())))).build());
        applySafe=addRenderableWidget(Button.builder(tr("auto_apply_safe"),button -> apply(result==null ? null : result.safe()))
                .bounds(left+8,top+216,panelWidth/2-13,20).build());
        applyDanger=addRenderableWidget(Button.builder(tr("auto_review_risky"),button -> confirmDanger())
                .bounds(left+panelWidth/2+3,top+216,panelWidth/2-11,20).build());
        setInitialFocus(target);
        updateButtons();
    }

    private static Component riskName(ReactorDesigner.Risk risk) {
        return tr("auto_risk."+risk.name().toLowerCase(Locale.ROOT));
    }
    private static Component coolingName(ReactorDesigner.CoolingMode mode) {
        return tr("auto_cooling."+mode.name().toLowerCase(Locale.ROOT));
    }
    private Component rodToggle(ReactorParts.Part rod) {
        return Component.literal(allowed.contains(rod.id()) ? "+" : "-");
    }

    private Component resizeLabel() {
        return tr(allowResize ? "auto_resize_on" : "auto_resize_off");
    }

    private void invalidateResult() {
        result=null;
        completedRequest=null;
        guard=null;
        attempted=false;
        updateButtons();
    }

    private void search() {
        if (busy || allowed.isEmpty()) return;
        long goal=targetText.isEmpty() ? 0 : Long.parseLong(targetText);
        var request=new ReactorDesigner.Request(goal,risk,allowed,allowResize,coolingMode);
        long token=++searchToken;
        busy=true;
        attempted=false;
        result=null;
        completedRequest=null;
        guard=null;
        updateButtons();
        WB.autoDesign(request,found -> {
            if (token!=searchToken) return;
            busy=false;
            attempted=true;
            result=found;
            completedRequest=found==null ? null : request;
            guard=found==null ? null : DesignGuard.capture();
            updateButtons();
        });
    }

    private void updateButtons() {
        if (target!=null) target.setEditable(!busy);
        if (riskButton!=null) riskButton.active=!busy;
        if (coolingButton!=null) coolingButton.active=!busy;
        if (resize!=null) resize.active=!busy;
        for (var button : rodButtons) button.active=!busy;
        if (search!=null) search.active=!busy && !allowed.isEmpty();
        if (applySafe!=null) applySafe.active=!busy && result!=null && result.safe()!=null;
        if (applyDanger!=null) applyDanger.active=!busy && result!=null && result.dangerous()!=null;
    }

    /** Apply a completed design, refusing anything computed from a blueprint or server snapshot that changed. */
    private boolean apply(ReactorDesigner.Design design) {
        if (design==null || completedRequest==null || guard==null || !guard.isCurrent()) {
            result=null;
            completedRequest=null;
            guard=null;
            attempted=true;
            updateButtons();
            return false;
        }
        WB.applyDesign(design,completedRequest.target());
        onClose();
        return true;
    }

    private void confirmDanger() {
        var current=result;
        if (current==null || current.dangerous()==null || guard==null) return;
        var design=current.dangerous();
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed && apply(design)) return;
            minecraft.setScreen(this);
        },tr("auto_danger_title"),dangerSummary(design),tr("auto_apply_risky"),Component.translatable("gui.cancel")));
    }

    private static Component dangerSummary(ReactorDesigner.Design design) {
        String message=design.outcome()==ReactorSimulation.Status.SAFE_COOLDOWN ? "auto_risky_shutdown" : "auto_risky_meltdown";
        return tr(message,design.exportRate(),design.ticks());
    }

    @Override public void removed() {
        if (busy) {
            searchToken++;
            busy=false;
            WB.cancelAutoDesign();
        }
        super.removed();
    }

    @Override public void onClose() {
        // Abandon this screen's callback before Workbench reports the cancellation.
        searchToken++;
        busy=false;
        WB.cancelAutoDesign();
        minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen() { return false; }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partialTick) {
        graphics.fill(0,0,width,height,0xBF10151A);
        graphics.fill(left,top,left+panelWidth,top+HEIGHT,0xFF22282F);
        graphics.outline(left,top,panelWidth,HEIGHT,0xFF72808D);
        line(graphics,title,8,8,panelWidth-16,LIGHT);
        line(graphics,tr("auto_dimensions",WB.layout.width(),WB.layout.length(),WB.layout.height()),8,23,panelWidth-16,MUTED);
        line(graphics,tr("auto_target"),8,35,panelWidth/2-15,LIGHT);
        line(graphics,tr("auto_risk"),panelWidth/2,35,panelWidth/2-8,LIGHT);
        line(graphics,tr("auto_rods"),8,71,panelWidth-24-coolingWidth,LIGHT);
        line(graphics,tr("auto_cooling"),panelWidth-coolingWidth-8,71,coolingWidth,LIGHT);
        for (int i=0;i<rods.size();i++)
            graphics.fakeItem(new ItemStack(rods.get(i).block()),rodPositions.get(i),top+84);

        if (busy) {
            line(graphics,tr("auto_busy"),8,129,panelWidth-16,MUTED);
        } else if (allowed.isEmpty()) {
            line(graphics,tr("auto_require_rods"),8,129,panelWidth-16,WARNING);
        } else if (targetText.isEmpty()) {
            line(graphics,tr("auto_target_hint"),8,129,panelWidth-16,MUTED);
        }
        if (result!=null && result.safe()!=null) {
            var design=result.safe();
            long goal=completedRequest==null ? 0 : completedRequest.target();
            var summary=goal>0 && design.exportRate()<goal ? tr("auto_goal_missed",design.exportRate(),goal)
                    : tr("auto_safe_result",design.exportRate());
            paragraph(graphics,summary,8,142,panelWidth-16,2,LIGHT);
            line(graphics,tr("auto_candidate_details",design.rodBlocks(),design.peakHeat(),design.layout().energyPorts()),
                    8,162,panelWidth-16,MUTED);
            var layout=design.layout();
            if (layout.width()!=WB.layout.width() || layout.length()!=WB.layout.length() || layout.height()!=WB.layout.height())
                line(graphics,tr("auto_result_dimensions",layout.width(),layout.length(),layout.height()),8,173,panelWidth-16,LIGHT);
            if (design.coolantPerSecond()>0)
                line(graphics,tr("auto_coolant_rate",String.format(Locale.ROOT,"%.1f",design.coolantPerSecond())),
                        8,184,panelWidth-16,MUTED);
        } else if (attempted) {
            paragraph(graphics,tr("auto_failed"),8,142,panelWidth-16,2,WARNING);
        }
        if (result!=null && result.dangerous()!=null)
            paragraph(graphics,dangerSummary(result.dangerous()),8,196,panelWidth-16,2,WARNING);
        super.extractRenderState(graphics,mouseX,mouseY,partialTick);
    }

    private void line(GuiGraphicsExtractor graphics,Component label,int x,int y,int width,int color) {
        text.line(graphics,font,label,left+x,top+y,width,color,false);
    }

    private void paragraph(GuiGraphicsExtractor graphics,Component label,int x,int y,int width,int rows,int color) {
        text.paragraph(graphics,font,label,left+x,top+y,width,rows,color);
    }
}
