package com.justenoughoritech.printer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

public final class PrinterMenu extends AbstractContainerMenu {
    private final BlockPos position;
    private final Inventory inventory;
    private final DataSlot[] values = new DataSlot[11];

    public PrinterMenu(int id, Inventory inventory, BlockPos position) {
        super(PrinterContent.MENU.get(), id);
        this.position = position;
        this.inventory = inventory;
        var entity = printer();
        Container blueprintContainer = entity != null ? entity.blueprintContainer() : new SimpleContainer(1);
        addSlot(new Slot(blueprintContainer, 0, 9, 25) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return PrinterEntity.isBlueprintStack(stack);
            }

            @Override
            public boolean mayPickup(Player player) {
                var current = printer();
                return current == null || current.authorized(player);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }

            @Override
            public void setByPlayer(ItemStack newStack, ItemStack oldStack) {
                super.setByPlayer(newStack, oldStack);
                notifySlotOwner();
            }

            @Override
            public void setChanged() {
                super.setChanged();
                notifySlotOwner();
            }

            private void notifySlotOwner() {
                var current = printer();
                if (current != null) current.onBlueprintSlotChanged(inventory.player);
            }
        });
        addStandardInventorySlots(inventory, 9, 162);
        for (int i = 0; i < values.length; i++) {
            final int field = i;
            values[i] = addDataSlot(inventory.player instanceof ServerPlayer ? new DataSlot() {
                @Override
                public int get() {
                    var p = printer();
                    if (p == null) return 0;
                    return switch (field) {
                        case 0, 1, 2 -> p.offset(field);
                        case 3 -> p.stateId();
                        case 4 -> p.progress() > 0 ? 1 : 0;
                        case 5 -> p.authorized(inventory.player) ? 1 : 0;
                        case 6 -> p.creativeJob() ? 1 : 0;
                        case 7 -> p.anchored() ? 1 : 0;
                        case 8 -> p.attachFace().get3DDataValue();
                        case 9 -> p.rotation().ordinal();
                        default -> p.mirror().ordinal();
                    };
                }

                @Override
                public void set(int ignored) {}
            } : DataSlot.standalone());
        }
    }

    public BlockPos blockPos() { return position; }

    public PrinterEntity printer() {
        return inventory.player.level().getBlockEntity(position) instanceof PrinterEntity printer ? printer : null;
    }

    public int offset(int axis) { return values[axis].get(); }
    public int state() { return values[3].get(); }
    public boolean started() { return values[4].get() != 0; }
    public boolean authorized() { return values[5].get() != 0; }
    public boolean creativeJob() { return values[6].get() != 0; }
    public boolean anchored() { return values[7].get() != 0; }
    public Direction attachFace() { return Direction.from3DDataValue(values[8].get()); }
    public Rotation rotation() {
        Rotation[] all = Rotation.values();
        int idx = values[9].get();
        return idx >= 0 && idx < all.length ? all[idx] : Rotation.NONE;
    }
    public Mirror mirror() {
        Mirror[] all = Mirror.values();
        int idx = values[10].get();
        return idx >= 0 && idx < all.length ? all[idx] : Mirror.NONE;
    }
    public boolean hasJob() { return state() != 0; }
    public boolean canEditProjection() { return hasJob() && authorized() && !started(); }
    public boolean canMove() { return canEditProjection(); }
    public boolean canCancel() { return hasJob() && authorized(); }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(ContainerLevelAccess.create(player.level(), position), player, PrinterContent.PRINTER.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        var slot = getSlot(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        var stack = slot.getItem();
        var original = stack.copy();
        if (index == 0) {
            if (!moveItemStackTo(stack, 1, 37, true)) return ItemStack.EMPTY;
        } else {
            var blueprintSlot = getSlot(0);
            if (blueprintSlot.mayPlace(stack) && !blueprintSlot.hasItem()) {
                if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
            } else if (index < 28) {
                if (!moveItemStackTo(stack, 28, 37, false)) return ItemStack.EMPTY;
            } else if (!moveItemStackTo(stack, 1, 28, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY, original);
        else slot.setChanged();
        return original;
    }
}
