package com.justenoughoritech.client;

import com.justenoughoritech.printer.PrinterMenu;
import com.justenoughoritech.printer.PrinterReport;
import com.justenoughoritech.printer.PrinterContent;
import com.justenoughoritech.printer.PrinterEntity;
import com.justenoughoritech.sim.ReactorLayout;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

import static com.justenoughoritech.client.SimulatorWidget.tr;

final class PrinterScreen extends AbstractContainerScreen<PrinterMenu> {
    private static final int MODEL_X=176, MODEL_Y=36, MODEL_W=120, MODEL_H=79;
    private static final int MATERIALS_X=MODEL_X, MATERIALS_Y=164, MATERIALS_W=MODEL_W, ROWS=5;
    private static final int HELP_X=284, HELP_Y=7, HELP_SIZE=12;
    private static final int TEXT=0xFFF3F6F9, RED=0xFFFF8176, MUTED=0xFFB8C4D0;
    private final TextViewport textViewport = new TextViewport();
    private final Button[] positions = new Button[6];
    private Button cancel;
    private Button preview;
    private int offset;
    private ReactorLayout previewLayout;
    private List<ReactorLayout.Placement> shell=List.of();
    private float pitch=AssemblyPreview.DEFAULT_PITCH, yaw=AssemblyPreview.DEFAULT_YAW;
    private boolean rotating;

    PrinterScreen(PrinterMenu menu,Inventory inventory,Component title) {
        super(menu,inventory,title,304,234);
    }

    @Override protected void init() {
        super.init();
        rotating=false;
        for (int id=0;id<6;id++) {
            final int action=id;
            positions[id]=addRenderableWidget(Button.builder(Component.literal(id%2==0?"-":"+"),button -> send(action))
                    .bounds(leftPos+(id%2==0 ? 88:111),topPos+48+id/2*19,20,17).build());
        }
        preview=addRenderableWidget(Button.builder(tr("printer_preview_short"),button -> {
            var printer=menu.printer();
            if (printer!=null && printer.layout()!=null) {
                onClose();
                ProjectionController.previewPrinter(printer);
            }
        }).bounds(leftPos+8,topPos+105,136,18).build());
        cancel=addRenderableWidget(Button.builder(tr("printer_cancel"),button -> send(6))
                .bounds(leftPos+8,topPos+124,136,16).build());
    }

    private void send(int id) {
        if (minecraft.gameMode!=null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId,id);
    }

    @Override protected void containerTick() {
        for (var button : positions) button.active=menu.canMove();
        preview.active=menu.printer()!=null && menu.printer().layout()!=null;
        cancel.active=menu.canCancel();
    }

    @Override public void extractBackground(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partialTick) {
        graphics.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xFF293039);
        graphics.outline(leftPos,topPos,imageWidth,imageHeight,0xFF768494);
        for (var slot : menu.slots) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED,Identifier.withDefaultNamespace("container/slot"),
                    leftPos+slot.x-1,topPos+slot.y-1,18,18);
        }
    }

    @Override protected void extractLabels(GuiGraphicsExtractor graphics,int mouseX,int mouseY) {
        line(graphics,title,8,8,HELP_X-12,TEXT);
        graphics.outline(HELP_X,HELP_Y,HELP_SIZE,HELP_SIZE,0xFF8594A4);
        line(graphics,Component.literal("?"),HELP_X+3,HELP_Y+2,8,TEXT);
        textViewport.paragraph(graphics,font,tr("printer_state."+menu.state()),8,27,136,2,TEXT);
        for (int i=0;i<3;i++) {
            line(graphics,Component.literal("XYZ".charAt(i)+" : "+menu.offset(i)),12,53+i*19,72,TEXT);
        }
        line(graphics,playerInventoryTitle,8,143,140,MUTED);
        var printer=menu.printer();
        drawModel(graphics,printer);
        if (printer!=null && printer.layout()!=null) drawReport(graphics,printer.report());
    }

    @Override protected void extractTooltip(GuiGraphicsExtractor graphics,int mouseX,int mouseY) {
        super.extractTooltip(graphics,mouseX,mouseY);
        int x=mouseX-leftPos,y=mouseY-topPos;
        if (x>=HELP_X && x<HELP_X+HELP_SIZE && y>=HELP_Y && y<HELP_Y+HELP_SIZE)
            graphics.setTooltipForNextFrame(font,PrinterContent.instructions(),Optional.empty(),mouseX,mouseY);
        else if (!rotating && overModel(mouseX,mouseY) && previewLayout!=null)
            graphics.setTooltipForNextFrame(tr("printer_model_drag"),mouseX,mouseY);
    }

    private void drawModel(GuiGraphicsExtractor graphics,PrinterEntity printer) {
        line(graphics,tr("printer_model"),MODEL_X,23,MODEL_W,TEXT);
        graphics.fill(MODEL_X,MODEL_Y,MODEL_X+MODEL_W,MODEL_Y+MODEL_H,0xFF1B2127);
        graphics.outline(MODEL_X,MODEL_Y,MODEL_W,MODEL_H,0xFF44515D);
        var layout=printer==null ? null : printer.layout();
        if (layout==null) {
            previewLayout=null;
            shell=List.of();
            textViewport.paragraph(graphics,font,tr("printer_model_empty"),MODEL_X+6,MODEL_Y+12,MODEL_W-12,4,MUTED);
            return;
        }
        int width=layout.buildWidth(printer.rotation()), height=layout.height(), length=layout.buildLength(printer.rotation());
        if (layout!=previewLayout) {
            previewLayout=layout;
            shell=AssemblyPreview.shell(printer.placements(),width,height,length);
        }
        AssemblyPreview.draw(graphics,shell,width,height,length,-1,null,null,pitch,yaw,MODEL_X,MODEL_Y,MODEL_W,MODEL_H);
    }

    private void drawReport(GuiGraphicsExtractor graphics,PrinterReport report) {
        line(graphics,tr(menu.creativeJob() ? "printer_materials_heading"
                : report!=null && report.unloaded() ? "printer_missing_estimate" : "printer_missing_title"),
                MATERIALS_X,150,MATERIALS_W,TEXT);
        if (report==null) {
            line(graphics,tr("printer_checking"),MATERIALS_X,MATERIALS_Y,MATERIALS_W,MUTED);
            return;
        }
        var blocked=report.obstruction();
        if (blocked!=null) textViewport.paragraph(graphics,font,tr("printer_obstruction",blocked.getX(),blocked.getY(),blocked.getZ()),
                MATERIALS_X,122,MATERIALS_W,2,RED);
        else line(graphics,tr(report.unloaded() ? "printer_partial" : "printer_clear"),MATERIALS_X,122,MATERIALS_W,MUTED);
        var missing=report.missing();
        if (menu.creativeJob()) {
            textViewport.paragraph(graphics,font,tr("printer_free_creative"),MATERIALS_X,MATERIALS_Y,MATERIALS_W,3,MUTED);
            return;
        }
        if (missing.isEmpty()) {
            textViewport.paragraph(graphics,font,tr(report.unloaded() ? "printer_check_loaded" : "printer_materials_ready"),
                    MATERIALS_X,MATERIALS_Y,MATERIALS_W,3,MUTED);
            return;
        }
        int max=Math.max(0,missing.size()-ROWS);
        offset=Math.clamp(offset,0,max);
        graphics.fill(MATERIALS_X,MATERIALS_Y,MATERIALS_X+MATERIALS_W,MATERIALS_Y+ROWS*12,0xFF1B2127);
        for (int row=0;row<ROWS && offset+row<missing.size();row++) {
            var entry=missing.get(offset+row);
            int y=MATERIALS_Y+2+row*12;
            String count=Integer.toString(entry.count());
            int countX=MATERIALS_X+MATERIALS_W-9-font.width(count);
            line(graphics,new ItemStack(BuiltInRegistries.ITEM.getValue(entry.item())).getHoverName(),
                    MATERIALS_X+3,y,countX-(MATERIALS_X+6),TEXT);
            line(graphics,Component.literal(count),countX,y,font.width(count),RED);
        }
        if (max>0) {
            int x=MATERIALS_X+MATERIALS_W-5, height=ROWS*12;
            int thumb=Math.max(10,height*ROWS/missing.size());
            int y=MATERIALS_Y+(height-thumb)*offset/max;
            graphics.fill(x,MATERIALS_Y,x+4,MATERIALS_Y+height,0xFF101417);
            graphics.fill(x,y,x+4,y+thumb,0xFF8594A4);
        }
    }

    private List<PrinterReport.Missing> missing() {
        var printer=menu.printer();
        var report=printer==null ? null : printer.report();
        return report==null ? List.of() : report.missing();
    }

    private boolean overMaterials(double x,double y) {
        return x>=leftPos+MATERIALS_X && x<leftPos+MATERIALS_X+MATERIALS_W
                && y>=topPos+MATERIALS_Y && y<topPos+MATERIALS_Y+ROWS*12;
    }

    private boolean overModel(double x,double y) {
        return x>=leftPos+MODEL_X && x<leftPos+MODEL_X+MODEL_W
                && y>=topPos+MODEL_Y && y<topPos+MODEL_Y+MODEL_H;
    }

    @Override public boolean mouseScrolled(double x,double y,double dx,double dy) {
        if (overMaterials(x,y) && dy!=0) {
            offset=Math.clamp(offset+(dy<0 ? 1 : -1),0,Math.max(0,missing().size()-ROWS));
            return true;
        }
        return super.mouseScrolled(x,y,dx,dy);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick) {
        double x=event.x(), y=event.y();
        if (event.button()==0 && previewLayout!=null && overModel(x,y)) {
            rotating=true;
            return true;
        }
        int max=Math.max(0,missing().size()-ROWS);
        if (event.button()==0 && max>0 && overMaterials(x,y) && x>=leftPos+MATERIALS_X+MATERIALS_W-6) {
            offset=Math.clamp((int)Math.round((y-topPos-MATERIALS_Y)*max/(ROWS*12-1)),0,max);
            return true;
        }
        return super.mouseClicked(event,doubleClick);
    }

    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy) {
        if (rotating && event.button()==0) {
            yaw=(yaw+(float)dx*.035f)%(float)(2*Math.PI);
            pitch=Math.clamp(pitch+(float)dy*.035f,-1.35f,1.35f);
            return true;
        }
        return super.mouseDragged(event,dx,dy);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (rotating && event.button()==0) {
            rotating=false;
            return true;
        }
        return super.mouseReleased(event);
    }

    private void line(GuiGraphicsExtractor graphics,Component label,int x,int y,int width,int color) {
        textViewport.line(graphics,font,label,x,y,width,color,false);
    }
}
