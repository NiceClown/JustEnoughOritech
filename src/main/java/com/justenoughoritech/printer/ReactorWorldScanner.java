package com.justenoughoritech.printer;

import com.justenoughoritech.sim.ReactorLayout;
import com.justenoughoritech.sim.ReactorParts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Scans an existing Oritech multiblock reactor in the world into a {@link ReactorLayout}. */
public final class ReactorWorldScanner {
    private static final String REDSTONE_PORT = "oritech:reactor_redstone_port";
    private static final Set<String> SHELL_BLOCKS = Set.of(
            ReactorLayout.WALL,
            ReactorLayout.CONTROLLER,
            ReactorLayout.ENERGY_PORT,
            ReactorLayout.FUEL_PORT,
            ReactorLayout.COOLANT_PORT,
            REDSTONE_PORT
    );

    public record ScanResult(ReactorLayout layout, BlockPos origin, BlockPos controllerPos, int energyPorts) {}

    private ReactorWorldScanner() {}

    public static Optional<ScanResult> scan(Level level, BlockPos controllerPos, int maxInterior) {
        if (level == null || controllerPos == null || !level.hasChunkAt(controllerPos)) return Optional.empty();
        var parts = discoveredParts();
        Function<BlockPos, String> lookup = pos -> {
            if (!level.hasChunkAt(pos)) return "";
            Block block = level.getBlockState(pos).getBlock();
            if (block == Blocks.AIR) return "";
            var key = BuiltInRegistries.BLOCK.getKey(block);
            return key == null ? "" : key.toString();
        };
        return scan(lookup, controllerPos, maxInterior, parts);
    }

    public static Optional<ScanResult> scanNearPlayer(Level level, Player player, int maxInterior) {
        if (level == null || player == null) return Optional.empty();
        var hit = player.pick(10.0, 0f, false);
        if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            var direct = scan(level, bhr.getBlockPos(), maxInterior);
            if (direct.isPresent()) return direct;
            var nearHit = findControllerInRadius(level, bhr.getBlockPos(), 10, maxInterior);
            if (nearHit.isPresent()) return nearHit;
        }
        return findControllerInRadius(level, player.blockPosition(), 12, maxInterior);
    }

    private static Optional<ScanResult> findControllerInRadius(Level level, BlockPos center, int radius, int maxInterior) {
        var cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.hasChunkAt(cursor)) continue;
                    var key = BuiltInRegistries.BLOCK.getKey(level.getBlockState(cursor).getBlock());
                    if (key != null && ReactorLayout.CONTROLLER.equals(key.toString())) {
                        double d = center.distSqr(cursor);
                        if (d < bestDist) {
                            bestDist = d;
                            best = cursor.immutable();
                        }
                    }
                }
            }
        }
        return best == null ? Optional.empty() : scan(level, best, maxInterior);
    }

    static Optional<ScanResult> scan(Function<BlockPos, String> blockIdAt, BlockPos controllerPos,
                                     int maxInterior, Map<String, ReactorParts.Part> parts) {
        if (blockIdAt == null || controllerPos == null || parts == null || maxInterior < 1) return Optional.empty();
        if (!ReactorLayout.CONTROLLER.equals(blockIdAt.apply(controllerPos))) return Optional.empty();

        int scanSpan = Math.min(PrinterLayout.LIMIT + 3, maxInterior + 4);
        int minY = controllerPos.getY();
        for (int step = 1; step < scanSpan; step++) {
            if (!isShell(blockIdAt.apply(controllerPos.below(step)))) break;
            minY = controllerPos.getY() - step;
        }
        int maxY = controllerPos.getY();
        for (int step = 1; step < scanSpan; step++) {
            if (!isShell(blockIdAt.apply(controllerPos.above(step)))) break;
            maxY = controllerPos.getY() + step;
        }

        BlockPos floorStart = new BlockPos(controllerPos.getX(), minY, controllerPos.getZ());
        if (!isShell(blockIdAt.apply(floorStart))) return Optional.empty();

        int minX = floorStart.getX();
        for (int step = 1; step < scanSpan; step++) {
            if (!isShell(blockIdAt.apply(floorStart.west(step)))) break;
            minX = floorStart.getX() - step;
        }
        int maxX = floorStart.getX();
        for (int step = 1; step < scanSpan; step++) {
            if (!isShell(blockIdAt.apply(floorStart.east(step)))) break;
            maxX = floorStart.getX() + step;
        }
        int minZ = floorStart.getZ();
        for (int step = 1; step < scanSpan; step++) {
            if (!isShell(blockIdAt.apply(floorStart.north(step)))) break;
            minZ = floorStart.getZ() - step;
        }
        int maxZ = floorStart.getZ();
        for (int step = 1; step < scanSpan; step++) {
            if (!isShell(blockIdAt.apply(floorStart.south(step)))) break;
            maxZ = floorStart.getZ() + step;
        }

        int width = maxX - minX - 1;
        int length = maxZ - minZ - 1;
        int height = maxY - minY - 1;
        int limit = Math.min(PrinterLayout.LIMIT, maxInterior);
        if (width < 1 || width > limit || length < 1 || length > limit || height < 1 || height > limit) {
            return Optional.empty();
        }
        // Verify all 6 faces of the bounding box are intact shell blocks and count side energy ports.
        int portCount = 0;
        for (int y = minY; y <= maxY; y++) {
            boolean sideY = y > minY && y < maxY;
            for (int z = minZ; z <= maxZ; z++) {
                boolean sideZ = z == minZ || z == maxZ;
                for (int x = minX; x <= maxX; x++) {
                    boolean sideX = x == minX || x == maxX;
                    if (sideY && !sideX && !sideZ) continue;
                    String id = blockIdAt.apply(new BlockPos(x, y, z));
                    if (!isShell(id)) return Optional.empty();
                    if (sideY && ReactorLayout.ENERGY_PORT.equals(id)) portCount++;
                }
            }
        }
        int ports = Math.clamp(portCount, 1, ReactorLayout.maxEnergyPorts(width, length, height));

        var layout = new ReactorLayout();
        layout.resize(width, length, limit);
        layout.setHeight(height, limit);
        layout.setEnergyPorts(ports);

        for (int lz = 0; lz < length; lz++) {
            for (int lx = 0; lx < width; lx++) {
                ReactorParts.Part found = ReactorParts.Part.EMPTY;
                for (int y = minY + 1; y <= maxY - 1; y++) {
                    String id = blockIdAt.apply(new BlockPos(minX + 1 + lx, y, minZ + 1 + lz));
                    ReactorParts.Part part = parts.get(id);
                    if (part != null && part.kind() != ReactorParts.Kind.EMPTY) {
                        found = part;
                        break;
                    }
                }
                layout.set(lx, lz, found);
            }
        }

        if (!layout.validationErrors(limit).isEmpty()) return Optional.empty();
        return Optional.of(new ScanResult(layout, new BlockPos(minX, minY, minZ), controllerPos, ports));
    }

    private static boolean isShell(String id) {
        return id != null && SHELL_BLOCKS.contains(id);
    }

    private static Map<String, ReactorParts.Part> discoveredParts() {
        return ReactorParts.discover().stream().collect(Collectors.toMap(ReactorParts.Part::id, Function.identity()));
    }
}
