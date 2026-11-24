package com.justenoughoritech.printer;

import com.justenoughoritech.network.ServerConfigReader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Rotation;

import java.util.Optional;
import java.util.function.Consumer;

public final class ReactorBlueprintItem extends Item {
    public ReactorBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        var data = stack.get(PrinterContent.BLUEPRINT_DATA.get());
        if (data != null && !data.name().isBlank()) {
            return Component.translatable("item.just_enough_oritech.reactor_blueprint_named", data.name());
        }
        return super.getName(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        var level = context.getLevel();
        var pos = context.getClickedPos();
        if (player == null || !player.isSecondaryUseActive()) return InteractionResult.PASS;
        var key = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        if (key == null || !"oritech:nuclear_reactor_controller".equals(key.toString())) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide() || !(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }
        var settings = ServerConfigReader.read(server.getServer());
        if (!settings.compatible()) {
            player.sendOverlayMessage(Component.translatable("gui.just_enough_oritech.blueprint_scan_failed"));
            return InteractionResult.CONSUME;
        }
        var resultOpt = ReactorWorldScanner.scan(server, pos, settings.maxSize());
        if (resultOpt.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("gui.just_enough_oritech.blueprint_scan_failed"));
            return InteractionResult.CONSUME;
        }
        var scanned = resultOpt.get();
        var stack = context.getItemInHand();
        var existing = stack.get(PrinterContent.BLUEPRINT_DATA.get());
        String label = existing != null && !existing.name().isBlank()
                ? existing.name()
                : scanned.layout().width() + "x" + scanned.layout().length() + "x" + scanned.layout().height();
        var nextData = new BlueprintData(
                PrinterLayout.from(scanned.layout()),
                Optional.of(new BlueprintData.Anchor(server.dimension().identifier(), scanned.origin(), Rotation.NONE)),
                label);
        stack.set(PrinterContent.BLUEPRINT_DATA.get(), nextData);
        server.playSound(null, pos, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.PLAYERS, 0.7f, 1.1f);
        player.sendOverlayMessage(Component.translatable("gui.just_enough_oritech.blueprint_scanned",
                scanned.layout().width(), scanned.layout().length(), scanned.layout().height()));
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> lines, TooltipFlag flags) {
        super.appendHoverText(stack, context, display, lines, flags);
        var data = stack.get(PrinterContent.BLUEPRINT_DATA.get());
        if (data == null) {
            lines.accept(Component.translatable("gui.just_enough_oritech.blueprint_empty").withStyle(ChatFormatting.GRAY));
            lines.accept(Component.translatable("gui.just_enough_oritech.blueprint_scan_hint").withStyle(ChatFormatting.DARK_GRAY));
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
        lines.accept(Component.translatable("gui.just_enough_oritech.blueprint_scan_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
