package com.justenoughoritech.printer;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class PrinterBlock extends BaseEntityBlock {
    public static final MapCodec<PrinterBlock> CODEC = simpleCodec(PrinterBlock::new);
    PrinterBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<PrinterBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new PrinterEntity(pos,state); }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                         Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof PrinterEntity printer)
            server.openMenu(printer,buf -> buf.writeBlockPos(pos));
        return InteractionResult.SUCCESS;
    }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type,PrinterContent.ENTITY.get(),
                level.isClientSide() ? PrinterEntity::clientTick : PrinterEntity::tick);
    }
}
