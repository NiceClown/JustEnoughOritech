package com.justenoughoritech.client;

import com.justenoughoritech.printer.PrinterMenu;
import com.justenoughoritech.printer.PrinterReport;
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

import static com.justenoughoritech.client.SimulatorWidget.tr;

final class PrinterScreen extends AbstractContainerScreen<PrinterMenu> {
    private static final int MATERIALS_X=153, MATERIALS_Y=62, MATERIALS_W=143, ROWS=6;
    private static final int TEXT=0xFFF3F6F9, RED=0xFFFF8176, MUTED=0xFFB8C4D0;
    private final TextViewport textViewport = new TextViewport();
    private final Button[] positions = new Button[6];
    private Button cancel;
    private Button preview;
    private int offset;

    PrinterScreen(PrinterMenu menu,Inventory inventory,Component title) {
        super(menu,inventory,title,304,234);
    }

    @Override protected void init() {
        super.init();
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
        line(graphics,title,8,8,imageWidth-16,TEXT);
        textViewport.paragraph(graphics,font,tr("printer_state."+menu.state()),8,27,136,2,TEXT);
        for (int i=0;i<3;i++) {
            line(graphics,Component.literal("XYZ".charAt(i)+" : "+menu.offset(i)),12,53+i*19,72,TEXT);
        }
        line(graphics,playerInventoryTitle,8,143,140,MUTED);
        var printer=menu.printer();
        if (printer!=null && printer.layout()!=null) drawReport(graphics,printer.report());
    }

    private void drawReport(GuiGraphicsExtractor graphics,PrinterReport report) {
        line(graphics,tr(menu.creativeJob() ? "printer_materials_heading"
                : report!=null && report.unloaded() ? "printer_missing_estimate" : "printer_missing_title"),
                MATERIALS_X,48,MATERIALS_W,TEXT);
        if (report==null) {
            line(graphics,tr("printer_checking"),MATERIALS_X,MATERIALS_Y,MATERIALS_W,MUTED);
            return;
        }
        var blocked=report.obstruction();
        if (blocked!=null) textViewport.paragraph(graphics,font,tr("printer_obstruction",blocked.getX(),blocked.getY(),blocked.getZ()),
                MATERIALS_X,25,MATERIALS_W,2,RED);
        else line(graphics,tr(report.unloaded() ? "printer_partial" : "printer_clear"),MATERIALS_X,26,MATERIALS_W,MUTED);
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

    @Override public boolean mouseScrolled(double x,double y,double dx,double dy) {
        if (overMaterials(x,y) && dy!=0) {
            offset=Math.clamp(offset+(dy<0 ? 1 : -1),0,Math.max(0,missing().size()-ROWS));
            return true;
        }
        return super.mouseScrolled(x,y,dx,dy);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick) {
        double x=event.x(), y=event.y();
        int max=Math.max(0,missing().size()-ROWS);
        if (event.button()==0 && max>0 && overMaterials(x,y) && x>=leftPos+MATERIALS_X+MATERIALS_W-6) {
            offset=Math.clamp((int)Math.round((y-topPos-MATERIALS_Y)*max/(ROWS*12-1)),0,max);
            return true;
        }
        return super.mouseClicked(event,doubleClick);
    }

    private void line(GuiGraphicsExtractor graphics,Component label,int x,int y,int width,int color) {
        textViewport.line(graphics,font,label,x,y,width,color,false);
    }
}
