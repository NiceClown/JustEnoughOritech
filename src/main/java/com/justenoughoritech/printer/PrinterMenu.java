package com.justenoughoritech.printer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

public final class PrinterMenu extends AbstractContainerMenu {
    private final BlockPos position;
    private final Inventory inventory;
    private final DataSlot[] values = new DataSlot[7];

    public PrinterMenu(int id,Inventory inventory,BlockPos position) {
        super(PrinterContent.MENU.get(),id);
        this.position = position;
        this.inventory = inventory;
        addStandardInventorySlots(inventory,9,154);
        for (int i=0;i<values.length;i++) {
            final int field = i;
            values[i] = addDataSlot(inventory.player instanceof ServerPlayer ? new DataSlot() {
                @Override public int get() {
                    var p = printer();
                    if (p==null) return 0;
                    return switch(field) {
                        case 0,1,2 -> p.offset(field);
                        case 3 -> p.stateId();
                        case 4 -> p.progress()>0 ? 1 : 0;
                        case 5 -> p.authorized(inventory.player) ? 1 : 0;
                        default -> p.creativeJob() ? 1 : 0;
                    };
                }
                @Override public void set(int ignored) {}
            } : DataSlot.standalone());
        }
    }

    public PrinterEntity printer() {
        return inventory.player.level().getBlockEntity(position) instanceof PrinterEntity printer ? printer : null;
    }
    public int offset(int axis) { return values[axis].get(); }
    public int state() { return values[3].get(); }
    public boolean creativeJob() { return values[6].get()!=0; }
    public boolean canMove() { return values[4].get()==0 && canCancel(); }
    public boolean canCancel() { return state()!=0 && values[5].get()!=0; }
    @Override public boolean stillValid(Player player) {
        return stillValid(ContainerLevelAccess.create(player.level(),position),player,PrinterContent.PRINTER.get());
    }
    @Override public ItemStack quickMoveStack(Player player,int index) {
        if (index<0 || index>=slots.size()) return ItemStack.EMPTY;
        var slot=getSlot(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        var stack=slot.getItem();
        var original=stack.copy();
        if (index<27 ? !moveItemStackTo(stack,27,36,false) : !moveItemStackTo(stack,0,27,false))
            return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }
    @Override public boolean clickMenuButton(Player player,int action) {
        return player instanceof ServerPlayer server && stillValid(player) && printer()!=null && printer().command(server,action);
    }
}
