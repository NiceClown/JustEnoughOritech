package com.justenoughoritech.compat.jade;

import com.justenoughoritech.JustEnoughOritech;
import com.justenoughoritech.printer.PrinterBlock;
import com.justenoughoritech.printer.PrinterControlRequest;
import com.justenoughoritech.printer.PrinterEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.BoxStyle;
import snownee.jade.api.ui.JadeUI;
import snownee.jade.api.view.ProgressView;

/** Optional Jade tooltip provider for the Reactor 3D Printer. */
@WailaPlugin
public final class JEOJadePlugin implements IWailaPlugin {
    private static final Identifier UID = Identifier.fromNamespaceAndPath(JustEnoughOritech.MOD_ID, "reactor_printer");

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(PrinterDataProvider.INSTANCE, PrinterEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(PrinterComponentProvider.INSTANCE, PrinterBlock.class);
    }

    private enum PrinterDataProvider implements IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public Identifier getUid() {
            return UID;
        }

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof PrinterEntity printer)) return;
            var layout = printer.layout();
            if (layout == null) {
                data.putBoolean("hasJob", false);
                return;
            }
            data.putBoolean("hasJob", true);
            data.putString("name", printer.blueprintName());
            data.putInt("w", layout.width());
            data.putInt("l", layout.length());
            data.putInt("h", layout.height());
            data.putInt("state", printer.stateId());
            data.putInt("mode", printer.operationMode().ordinal());
            data.putInt("redstone", printer.redstoneMode().ordinal());
            data.putInt("done", printer.progress());
            data.putInt("total", printer.totalPlacements());
            var report = printer.report();
            if (report != null && !report.missing().isEmpty()) {
                var first = report.missing().getFirst();
                data.putString("missingItem", first.item().toString());
                data.putInt("missingCount", first.count());
            }
        }
    }

    private enum PrinterComponentProvider implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public Identifier getUid() {
            return UID;
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            var data = accessor.getServerData();
            if (!data.contains("hasJob")) return;
            if (!data.getBooleanOr("hasJob", false)) {
                tooltip.add(Component.translatable("gui.just_enough_oritech.printer_state.0").withStyle(ChatFormatting.GRAY));
                return;
            }
            String name = data.getStringOr("name", "");
            int w = data.getIntOr("w", 0), l = data.getIntOr("l", 0), h = data.getIntOr("h", 0);
            Component size = Component.literal(w + "×" + l + "×" + h).withStyle(ChatFormatting.AQUA);
            if (!name.isBlank()) {
                tooltip.add(Component.literal(name + " ").withStyle(ChatFormatting.GOLD).append(size));
            } else {
                tooltip.add(Component.translatable("gui.just_enough_oritech.jade.core", w, l, h).withStyle(ChatFormatting.AQUA));
            }

            int modeOrd = Math.clamp(data.getIntOr("mode", 0), 0, PrinterControlRequest.OperationMode.values().length - 1);
            int rsOrd = Math.clamp(data.getIntOr("redstone", 0), 0, PrinterControlRequest.RedstoneMode.values().length - 1);
            var mode = PrinterControlRequest.OperationMode.values()[modeOrd];
            var rs = PrinterControlRequest.RedstoneMode.values()[rsOrd];
            Component modeText = Component.translatable("gui.just_enough_oritech.printer_mode." + (mode == PrinterControlRequest.OperationMode.DECONSTRUCT ? "deconstruct" : "build"))
                    .withStyle(mode == PrinterControlRequest.OperationMode.DECONSTRUCT ? ChatFormatting.GOLD : ChatFormatting.AQUA);
            Component rsText = Component.translatable("gui.just_enough_oritech.printer_rs." + switch (rs) {
                case LOW -> "low";
                case ALWAYS -> "always";
                case HIGH -> "high";
            }).withStyle(ChatFormatting.GRAY);
            tooltip.add(modeText.copy().append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY)).append(rsText));

            int state = data.getIntOr("state", 0);
            ChatFormatting stateColor = state == 5 ? ChatFormatting.GREEN
                    : (state == 3 || state == 4 || state == 6 || state == 7) ? ChatFormatting.RED : ChatFormatting.WHITE;
            tooltip.add(Component.translatable("gui.just_enough_oritech.printer_state." + state).withStyle(stateColor));

            int done = Math.max(0, data.getIntOr("done", 0));
            int total = Math.max(1, data.getIntOr("total", 1));
            float ratio = Math.clamp(done / (float) total, 0f, 1f);
            int barColor = state == 5 ? 0xFF3C8527
                    : (mode == PrinterControlRequest.OperationMode.DECONSTRUCT ? 0xFFD18B33 : 0xFF35B8CC);
            Component progressLabel = Component.literal(done + " / " + total + " (" + Math.round(ratio * 100f) + "%)");
            tooltip.add(JadeUI.progress(new ProgressView(
                    ProgressView.Part.of(ratio, barColor),
                    progressLabel,
                    JadeUI.progressStyle(),
                    BoxStyle.DEFAULT_NESTED_BOX)));

            int missingCount = data.getIntOr("missingCount", 0);
            if (missingCount > 0) {
                var id = Identifier.tryParse(data.getStringOr("missingItem", ""));
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                    var stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id));
                    tooltip.add(JadeUI.smallItem(stack));
                    tooltip.append(Component.translatable("gui.just_enough_oritech.jade.missing",
                            stack.getHoverName(), missingCount).withStyle(ChatFormatting.RED));
                }
            }
        }
    }
}
