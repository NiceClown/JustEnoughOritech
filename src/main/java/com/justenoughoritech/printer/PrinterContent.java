package com.justenoughoritech.printer;

import com.justenoughoritech.JustEnoughOritech;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.List;

public final class PrinterContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(JustEnoughOritech.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(JustEnoughOritech.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,JustEnoughOritech.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU,JustEnoughOritech.MOD_ID);
    private static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS = DeferredRegister.create(Registries.DATA_COMPONENT_TYPE,JustEnoughOritech.MOD_ID);
    public static final Supplier<DataComponentType<BlueprintData>> BLUEPRINT_DATA =
            DATA_COMPONENTS.register("blueprint_data", () -> DataComponentType.<BlueprintData>builder()
                    .persistent(BlueprintData.CODEC)
                    .networkSynchronized(BlueprintData.STREAM_CODEC)
                    .build());
    public static final net.neoforged.neoforge.registries.DeferredBlock<PrinterBlock> PRINTER =
            BLOCKS.registerBlock("reactor_printer",PrinterBlock::new,
                    props -> props.strength(4f));
    public static final DeferredItem<ReactorBlueprintItem> BLUEPRINT =
            ITEMS.registerItem("reactor_blueprint", props -> new ReactorBlueprintItem(props.stacksTo(1)));
    public static final Supplier<BlockEntityType<PrinterEntity>> ENTITY =
            ENTITIES.register("reactor_printer",() -> new BlockEntityType<>(PrinterEntity::new,PRINTER.get()));
    public static final Supplier<MenuType<PrinterMenu>> MENU =
            MENUS.register("reactor_printer",() -> new MenuType<>(
                    (net.neoforged.neoforge.network.IContainerFactory<PrinterMenu>)(id,inventory,buf) ->
                            new PrinterMenu(id,inventory,buf.readBlockPos()), FeatureFlags.DEFAULT_FLAGS));

    private PrinterContent() {}
    public static List<Component> instructions() {
        String key="gui.just_enough_oritech.printer_help.";
        return List.of(
                Component.translatable(key+"design", Component.keybind("key.just_enough_oritech.open_simulator")),
                Component.translatable(key+"anchor", Component.keybind("key.just_enough_oritech.lock_projection")),
                Component.translatable(key+"write"),
                Component.translatable(key+"slot"),
                Component.translatable(key+"power"));
    }

    public static void register(IEventBus bus) {
        ITEMS.registerItem("reactor_printer",props -> new BlockItem(PRINTER.get(),props.useBlockDescriptionPrefix()) {
            @Override public void appendHoverText(ItemStack stack,Item.TooltipContext context,TooltipDisplay display,
                                                  Consumer<Component> lines,TooltipFlag flags) {
                super.appendHoverText(stack,context,display,lines,flags);
                instructions().forEach(line -> lines.accept(line.copy().withStyle(net.minecraft.ChatFormatting.GRAY)));
            }
        });
        bus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey().equals(net.minecraft.world.item.CreativeModeTabs.REDSTONE_BLOCKS)) event.accept(PRINTER);
        });
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
        DATA_COMPONENTS.register(bus);
    }
}
