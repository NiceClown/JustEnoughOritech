package com.justenoughoritech.printer;

import com.justenoughoritech.JustEnoughOritech;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class PrinterContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(JustEnoughOritech.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(JustEnoughOritech.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,JustEnoughOritech.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU,JustEnoughOritech.MOD_ID);
    public static final net.neoforged.neoforge.registries.DeferredBlock<PrinterBlock> PRINTER =
            BLOCKS.registerBlock("reactor_printer",PrinterBlock::new,
                    props -> props.strength(4f));
    public static final Supplier<BlockEntityType<PrinterEntity>> ENTITY =
            ENTITIES.register("reactor_printer",() -> new BlockEntityType<>(PrinterEntity::new,PRINTER.get()));
    public static final Supplier<MenuType<PrinterMenu>> MENU =
            MENUS.register("reactor_printer",() -> new MenuType<>(
                    (net.neoforged.neoforge.network.IContainerFactory<PrinterMenu>)(id,inventory,buf) ->
                            new PrinterMenu(id,inventory,buf.readBlockPos()), FeatureFlags.DEFAULT_FLAGS));

    private PrinterContent() {}
    public static void register(IEventBus bus) {
        ITEMS.registerSimpleBlockItem(PRINTER);
        bus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey().equals(net.minecraft.world.item.CreativeModeTabs.REDSTONE_BLOCKS)) event.accept(PRINTER);
        });
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
    }
}
