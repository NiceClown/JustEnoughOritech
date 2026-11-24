package com.justenoughoritech.printer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

public final class ReactorBlueprintItem extends Item {
    public ReactorBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> lines, TooltipFlag flags) {
        super.appendHoverText(stack, context, display, lines, flags);
        var data = stack.get(PrinterContent.BLUEPRINT_DATA.get());
        if (data == null) {
            lines.accept(Component.translatable("gui.just_enough_oritech.blueprint_empty").withStyle(ChatFormatting.GRAY));
            return;
        }
        var layout = data.layout();
        lines.accept(Component.translatable("gui.just_enough_oritech.blueprint_size",
                layout.width(), layout.length(), layout.height(), layout.ports()).withStyle(ChatFormatting.AQUA));
        if (data.anchor().isPresent()) {
            var anchor = data.anchor().get();
            lines.accept(Component.translatable("gui.just_enough_oritech.blueprint_anchored",
                    anchor.origin().getX(), anchor.origin().getY(), anchor.origin().getZ(),
                    BlueprintData.rotationDegrees(anchor.rotation())).withStyle(ChatFormatting.GREEN));
        } else {
            lines.accept(Component.translatable("gui.just_enough_oritech.blueprint_unanchored").withStyle(ChatFormatting.YELLOW));
        }
        lines.accept(Component.translatable("gui.just_enough_oritech.blueprint_hint").withStyle(ChatFormatting.GRAY));
    }
}
