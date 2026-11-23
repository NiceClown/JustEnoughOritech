package com.justenoughoritech.printer;

import com.justenoughoritech.sim.ReactorLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Inspects the remaining placements in build order without loading chunks. */
public final class BuildInspection {
    enum CellStatus { UNLOADED, MATCH, AIR, OBSTRUCTED }

    private final List<ReactorLayout.Placement> placements;
    private final BlockPos origin;
    private final Map<String,Block> palette = new LinkedHashMap<>();
    private final Map<String,Integer> requiredIds = new LinkedHashMap<>();
    private final BitSet counted = new BitSet();
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private int index;
    private BlockPos obstruction;
    private boolean unloaded;

    public BuildInspection(List<ReactorLayout.Placement> placements, BlockPos origin, int from) {
        this.placements = placements;
        this.origin = origin;
        index = Math.clamp(from, 0, placements.size());
    }

    public boolean scan(Level level, int budget, boolean stopAtObstruction) {
        return scan((pos, blockId) -> {
            if (!level.hasChunkAt(pos)) return CellStatus.UNLOADED;
            var state = level.getBlockState(pos);
            if (state.is(block(blockId))) return CellStatus.MATCH;
            return state.isAir() ? CellStatus.AIR : CellStatus.OBSTRUCTED;
        }, budget, stopAtObstruction);
    }

    boolean scan(BiFunction<BlockPos, String, CellStatus> probe, int budget, boolean stopAtObstruction) {
        int end = (int) Math.min(placements.size(), (long) index + Math.max(0, budget));
        while (index<end) {
            int slot = index++;
            var placement = placements.get(slot);
            position.set(origin.getX()+placement.x(),origin.getY()+placement.y(),origin.getZ()+placement.z());
            var status = probe.apply(position, placement.blockId());
            if (status == CellStatus.MATCH) continue;
            if (status == CellStatus.UNLOADED) {
                unloaded = true;
            } else if (status == CellStatus.OBSTRUCTED && obstruction == null) {
                obstruction = position.immutable();
                if (stopAtObstruction) return true;
            }
            if (!stopAtObstruction) {
                requiredIds.merge(placement.blockId(),1,Integer::sum);
                counted.set(slot);
            }
        }
        return index==placements.size();
    }

    void placed(int slot) {
        if (slot < 0 || slot >= placements.size() || !counted.get(slot)) return;
        counted.clear(slot);
        requiredIds.computeIfPresent(placements.get(slot).blockId(),(ignored,count) -> count==1 ? null : count-1);
    }

    private Block block(String blockId) {
        return palette.computeIfAbsent(blockId, id -> {
            var parsed = Identifier.tryParse(id);
            return parsed == null ? Blocks.AIR : BuiltInRegistries.BLOCK.getValue(parsed);
        });
    }

    public BlockPos origin() { return origin; }
    public BlockPos obstruction() { return obstruction; }
    boolean unloaded() { return unloaded; }
    Map<String,Integer> requiredIds() { return new LinkedHashMap<>(requiredIds); }
    Map<Item,Integer> required() {
        var out = new LinkedHashMap<Item,Integer>();
        requiredIds.forEach((id, count) -> out.merge(block(id).asItem(), count, Integer::sum));
        return out;
    }
}
