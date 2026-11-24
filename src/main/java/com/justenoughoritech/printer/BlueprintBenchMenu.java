package com.justenoughoritech.printer;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Objects;

/** Drafting table container where players place Paper into a real slot to craft a Reactor Blueprint. */
public final class BlueprintBenchMenu extends AbstractContainerMenu {
    private final Player player;
    private BlueprintData data;
    private final SimpleContainer input = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            refreshResult();
        }
    };
    private final ResultContainer result = new ResultContainer();

    public BlueprintBenchMenu(int id, Inventory inventory, BlueprintData data) {
        super(PrinterContent.BENCH_MENU.get(), id);
        this.player = inventory.player;
        this.data = Objects.requireNonNull(data, "data");

        addSlot(new Slot(input, 0, 26, 54) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(Items.PAPER);
            }

            @Override
            public void setChanged() {
                super.setChanged();
                refreshResult();
            }
        });

        addSlot(new Slot(result, 0, 114, 54) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public boolean mayPickup(Player actor) {
                return hasPaper() || actor.isCreative();
            }

            @Override
            public void onTake(Player actor, ItemStack stack) {
                consumePaperIfNeeded(actor);
                super.onTake(actor, stack);
                refreshResult();
            }
        });

        addStandardInventorySlots(inventory, 12, 148);
        refreshResult();
    }

    public BlueprintData data() {
        return data;
    }

    public boolean hasPaper() {
        ItemStack stack = input.getItem(0);
        return !stack.isEmpty() && stack.is(Items.PAPER);
    }

    public int stagedPaperCount() {
        return hasPaper() ? input.getItem(0).getCount() : 0;
    }

    public boolean canCraft() {
        return hasPaper() || player.isCreative();
    }

    public void setBlueprintName(String rawName) {
        String clean = BlueprintData.sanitizeName(rawName);
        if (clean.equals(data.name())) return;
        data = new BlueprintData(data.layout(), data.anchor(), clean);
        refreshResult();
    }

    private void consumePaperIfNeeded(Player actor) {
        if (!actor.isCreative() && hasPaper()) {
            input.removeItem(0, 1);
        }
    }

    private void refreshResult() {
        if (canCraft()) {
            var blueprint = new ItemStack(PrinterContent.BLUEPRINT.get());
            blueprint.set(PrinterContent.BLUEPRINT_DATA.get(), data);
            result.setItem(0, blueprint);
        } else {
            result.setItem(0, ItemStack.EMPTY);
        }
        broadcastChanges();
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        refreshResult();
    }

    @Override
    public boolean stillValid(Player actor) {
        return true;
    }

    @Override
    public void removed(Player actor) {
        super.removed(actor);
        clearContainer(actor, input);
    }

    @Override
    public ItemStack quickMoveStack(Player actor, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        var slot = getSlot(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        var stack = slot.getItem();
        var original = stack.copy();
        if (index == 1) {
            if (!slot.mayPickup(actor)) return ItemStack.EMPTY;
            if (!moveItemStackTo(stack, 2, 38, true)) return ItemStack.EMPTY;
            slot.onTake(actor, original);
            // Craft one non-stackable blueprint per Shift-click so player inventory is not flooded.
            return ItemStack.EMPTY;
        }
        if (index == 0) {
            if (!moveItemStackTo(stack, 2, 38, false)) return ItemStack.EMPTY;
            if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY, original);
            else slot.setChanged();
            refreshResult();
            return original;
        }
        if (stack.is(Items.PAPER) && moveItemStackTo(stack, 0, 1, false)) {
            if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY, original);
            else slot.setChanged();
            refreshResult();
            return original;
        }
        if (index < 29) {
            if (!moveItemStackTo(stack, 29, 38, false)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 2, 29, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY, original);
        else slot.setChanged();
        return original;
    }
}
