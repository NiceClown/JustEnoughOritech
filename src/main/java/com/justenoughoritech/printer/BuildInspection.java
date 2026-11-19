package com.justenoughoritech.printer;

import com.justenoughoritech.sim.ReactorLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Inspects the remaining placements in build order without loading chunks. */
public final class BuildInspection {
    private final List<ReactorLayout.Placement> placements;
    private final BlockPos origin;
    private final Map<String,Block> palette = new LinkedHashMap<>();
    private final Map<Item,Integer> required = new LinkedHashMap<>();
    private final BitSet counted = new BitSet();
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private int index;
    private BlockPos obstruction;
    private boolean unloaded;

    public BuildInspection(List<ReactorLayout.Placement> placements, BlockPos origin, int from) {
        this.placements = placements;
        this.origin = origin;
        index = from;
    }

    public boolean scan(Level level, int budget, boolean stopAtObstruction) {
        int end = Math.min(placements.size(), index+budget);
        while (index<end) {
            int slot = index++;
            var placement = placements.get(slot);
            var block = block(placement);
            position.set(origin.getX()+placement.x(),origin.getY()+placement.y(),origin.getZ()+placement.z());
            if (!level.hasChunkAt(position)) {
                unloaded = true;
            } else {
                var state = level.getBlockState(position);
                if (state.is(block)) continue;
                if (obstruction==null && !state.isAir()) {
                    obstruction = position.immutable();
                    if (stopAtObstruction) return true;
                }
            }
            if (!stopAtObstruction) {
                required.merge(block.asItem(),1,Integer::sum);
                counted.set(slot);
            }
        }
        return index==placements.size();
    }

    void placed(int slot) {
        if (!counted.get(slot)) return;
        counted.clear(slot);
        Item item = block(placements.get(slot)).asItem();
        required.computeIfPresent(item,(ignored,count) -> count==1 ? null : count-1);
    }

    private Block block(ReactorLayout.Placement placement) {
        return palette.computeIfAbsent(placement.blockId(), id -> BuiltInRegistries.BLOCK.getValue(Identifier.parse(id)));
    }

    public BlockPos origin() { return origin; }
    public BlockPos obstruction() { return obstruction; }
    boolean unloaded() { return unloaded; }
    Map<Item,Integer> required() { return new LinkedHashMap<>(required); }
}
