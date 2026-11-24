package com.justenoughoritech.printer;

import com.justenoughoritech.network.ServerConfigReader;
import com.justenoughoritech.sim.ReactorLayout;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PrinterEntity extends BlockEntity implements MenuProvider {
    static final int RANGE = 32;
    static final int ANCHOR_RANGE = 256;
    static final int SHIFT = 256;
    static final int MIN_BUILD_DISTANCE = 128;
    static final int TARGET_BUILD_TICKS = 3_600;
    static final int MAX_BLOCKS_PER_TICK = 512;
    private static final int IDLE = 0, WAITING = 1, PRINTING = 2, MATERIALS = 3, OBSTRUCTED = 4,
            COMPLETE = 5, OWNER = 6, STORAGE_FULL = 7, DECONSTRUCTING = 8;
    private static final int BUILD_PARTICLE_COLOR = 0x35B8CC;
    private static final int DISMANTLE_PARTICLE_COLOR = 0xD18B33;
    private static final Direction[] FALLBACK_FACES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN
    };

    private boolean loading;

    private final SimpleContainer blueprintSlot = new SimpleContainer(1) {
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return isBlueprintStack(stack);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (!loading) onBlueprintSlotChanged(null);
        }
    };

    private UUID owner;
    private PrinterLayout blueprint;
    private BlueprintData loadedBlueprintData;
    private ReactorLayout layout;
    private Rotation rotation = Rotation.NONE;
    private Mirror mirror = Mirror.NONE;
    private Direction attachFace = Direction.UP;
    private boolean anchored;
    private List<ReactorLayout.Placement> placements = List.of();
    private BlockPos origin;
    private int dx, dy, dz, cursor;
    private int state;
    private boolean clientPower;
    private PrinterControlRequest.RedstoneMode redstoneMode = PrinterControlRequest.RedstoneMode.HIGH;
    private PrinterControlRequest.OperationMode operationMode = PrinterControlRequest.OperationMode.BUILD;
    private BuildInspection inspection;
    private PrinterReport report;
    private long nextInspection;

    PrinterEntity(BlockPos pos, BlockState state) {
        super(PrinterContent.ENTITY.get(), pos, state);
    }

    public static boolean isBlueprintStack(ItemStack stack) {
        return !stack.isEmpty()
                && stack.is(PrinterContent.BLUEPRINT.get())
                && stack.get(PrinterContent.BLUEPRINT_DATA.get()) != null;
    }

    Container blueprintContainer() {
        return blueprintSlot;
    }

    boolean creativeJob() {
        if (!(level instanceof ServerLevel server) || owner == null) return false;
        var player = server.getServer().getPlayerList().getPlayer(owner);
        return player != null && player.level() == server && player.isCreative();
    }

    private ServerPlayer actingPlayer(ServerLevel server, ServerPlayer onlineOwner) {
        if (onlineOwner != null) return onlineOwner;
        if (owner != null) return FakePlayerFactory.get(server, new GameProfile(owner, "[ReactorPrinter]"));
        return FakePlayerFactory.getMinecraft(server);
    }

    boolean authorized(Player player) {
        return owner == null || (player != null && owner.equals(player.getUUID()));
    }

    public ReactorLayout layout() { return layout; }
    public Rotation rotation() { return rotation; }
    public Mirror mirror() { return mirror; }
    public Direction attachFace() { return attachFace; }
    public boolean anchored() { return anchored; }
    public List<ReactorLayout.Placement> placements() { return placements; }
    public BlockPos anchor() { return origin == null ? null : origin.offset(dx, dy, dz); }
    public PrinterReport report() { return report; }
    public Direction controllerFacing() {
        return layout == null ? Direction.WEST : layout.controllerFacing(rotation, mirror);
    }

    public void setReport(PrinterReport update) {
        if (update.origin().equals(anchor())) report = update;
    }

    public String blueprintName() { return loadedBlueprintData == null ? "" : loadedBlueprintData.name(); }
    public int progress() { return cursor; }
    public int totalPlacements() { return placements.size(); }
    public int stateId() { return state; }

    /** Scale per-tick placement rate linearly so vanilla max (66x66x66) finishes within <= 3 minutes. */
    public static int blocksPerTick(int totalPlacements) {
        return PrinterControlRequest.blocksPerTick(totalPlacements);
    }
    public PrinterControlRequest.RedstoneMode redstoneMode() { return redstoneMode; }
    public PrinterControlRequest.OperationMode operationMode() { return operationMode; }

    boolean redstoneActive(Level level, BlockPos pos) {
        return PrinterControlRequest.isRedstoneSatisfied(redstoneMode, level.hasNeighborSignal(pos));
    }

    public int analogOutputSignal() {
        return PrinterControlRequest.analogOutputSignal(layout != null, cursor, placements.size(), state == COMPLETE);
    }
    int offset(int axis) { return switch (axis) { case 0 -> dx; case 1 -> dy; default -> dz; }; }

    void onBlueprintSlotChanged(Player actor) {
        if (!(level instanceof ServerLevel server)) return;
        var stack = blueprintSlot.getItem(0);
        if (!isBlueprintStack(stack)) {
            if (blueprint != null || layout != null || loadedBlueprintData != null) clearJob();
            return;
        }
        var data = stack.get(PrinterContent.BLUEPRINT_DATA.get());
        if (data == null) return;
        if (data.equals(loadedBlueprintData)) {
            if (owner == null && actor != null) owner = actor.getUUID();
            return;
        }
        loadBlueprintData(server, actor, data);
    }

    private void loadBlueprintData(ServerLevel server, Player actor, BlueprintData data) {
        var settings = ServerConfigReader.read(server.getServer());
        var checked = settings.compatible() ? data.layout().resolve(settings.maxSize()) : null;
        if (checked == null) {
            clearJob();
            return;
        }
        if (actor != null) owner = actor.getUUID();
        blueprint = data.layout();
        loadedBlueprintData = data;
        layout = checked;
        mirror = Mirror.NONE;
        operationMode = PrinterControlRequest.OperationMode.BUILD;
        dx = dy = dz = cursor = 0;
        applyDefaultPlacement(server, checked, data);
        placements = checked.placements(rotation, mirror);
        state = WAITING;
        resetInspection();
        sync();
    }

    private void applyDefaultPlacement(ServerLevel server, ReactorLayout checked, BlueprintData data) {
        if (data.anchor().isPresent()) {
            var worldAnchor = data.anchor().get();
            if (worldAnchor.dimension().equals(server.dimension().identifier())
                    && withinBuildDistance(worldAnchor.origin(), checked)
                    && fits(server, worldAnchor.origin(), checked, worldAnchor.rotation())) {
                anchored = true;
                attachFace = Direction.UP;
                rotation = worldAnchor.rotation();
                origin = worldAnchor.origin();
                return;
            }
        }
        anchored = false;
        rotation = data.anchor().map(BlueprintData.Anchor::rotation).orElse(Rotation.NONE);
        attachFace = Direction.UP;
        BlockPos candidate = checked.faceOrigin(worldPosition, Direction.UP, rotation);
        if (!fits(server, candidate, checked, rotation) || !withinBuildDistance(candidate, checked)) {
            for (Direction fallback : FALLBACK_FACES) {
                BlockPos alt = checked.faceOrigin(worldPosition, fallback, rotation);
                if (fits(server, alt, checked, rotation) && withinBuildDistance(alt, checked)) {
                    attachFace = fallback;
                    candidate = alt;
                    break;
                }
            }
        }
        origin = candidate;
    }

    private void clearJob() {
        blueprint = null;
        loadedBlueprintData = null;
        owner = null;
        layout = null;
        rotation = Rotation.NONE;
        mirror = Mirror.NONE;
        attachFace = Direction.UP;
        operationMode = PrinterControlRequest.OperationMode.BUILD;
        anchored = false;
        origin = null;
        placements = List.of();
        cursor = state = dx = dy = dz = 0;
        resetInspection();
        sync();
    }

    boolean handleControl(ServerPlayer player, PrinterControlRequest req) {
        if (!authorized(player) || layout == null || !(level instanceof ServerLevel server)) return false;
        if (owner == null && player != null) owner = player.getUUID();
        if (req.action() == PrinterControlRequest.Action.CYCLE_REDSTONE) {
            var modes = PrinterControlRequest.RedstoneMode.values();
            redstoneMode = modes[(redstoneMode.ordinal() + 1) % modes.length];
            if (state != COMPLETE) state = WAITING;
            sync();
            return true;
        }
        if (req.action() == PrinterControlRequest.Action.TOGGLE_MODE) {
            operationMode = operationMode == PrinterControlRequest.OperationMode.BUILD
                    ? PrinterControlRequest.OperationMode.DECONSTRUCT : PrinterControlRequest.OperationMode.BUILD;
            cursor = 0;
            state = WAITING;
            resetInspection();
            sync();
            return true;
        }
        if (req.action() == PrinterControlRequest.Action.RESET_PROGRESS) {
            cursor = 0;
            state = WAITING;
            resetInspection();
            sync();
            return true;
        }
        if (cursor != 0) return false;
        return switch (req.action()) {
            case MOVE -> moveOffset(server, req.x(), req.y(), req.z());
            case ROTATE -> {
                var next = new ReactorLayout.Orientation(rotation, mirror).rotated(req.x());
                yield applyOrientation(server, next.rotation(), next.mirror());
            }
            case FLIP_X -> {
                var next = new ReactorLayout.Orientation(rotation, mirror).flippedWorldX();
                yield applyOrientation(server, next.rotation(), next.mirror());
            }
            case FLIP_Z -> {
                var next = new ReactorLayout.Orientation(rotation, mirror).flippedWorldZ();
                yield applyOrientation(server, next.rotation(), next.mirror());
            }
            case SET_FACE -> setAttachFace(server, req.face());
            case SET_ANCHOR -> setWorldAnchor(server, new BlockPos(req.x(), req.y(), req.z()), req.rotation());
            case RESET_POSITION -> resetToBlueprintDefault(server, player);
            case RESET_PROGRESS, CYCLE_REDSTONE, TOGGLE_MODE -> true;
        };
    }

    private boolean withinBuildDistance(BlockPos target, ReactorLayout shape) {
        if (target == null || shape == null) return false;
        int maxDim = Math.max(shape.width(), Math.max(shape.length(), shape.height()));
        double limit = Math.clamp(MIN_BUILD_DISTANCE + maxDim, MIN_BUILD_DISTANCE, 512);
        return worldPosition.closerThan(target, limit);
    }

    private boolean moveOffset(ServerLevel server, int stepX, int stepY, int stepZ) {
        int nextX = Math.clamp(dx + stepX, -SHIFT, SHIFT);
        int nextY = Math.clamp(dy + stepY, -SHIFT, SHIFT);
        int nextZ = Math.clamp(dz + stepZ, -SHIFT, SHIFT);
        var target = origin.offset(nextX, nextY, nextZ);
        if (!withinBuildDistance(target, layout) || !fits(server, target, layout, rotation)) return false;
        dx = nextX;
        dy = nextY;
        dz = nextZ;
        resetInspection();
        sync();
        return true;
    }

    private boolean applyOrientation(ServerLevel server, Rotation nextRot, Mirror nextMirror) {
        BlockPos newOrigin = anchored ? origin : layout.faceOrigin(worldPosition, attachFace, nextRot);
        var target = newOrigin.offset(dx, dy, dz);
        if (!withinBuildDistance(target, layout) || !fits(server, target, layout, nextRot)) return false;
        origin = newOrigin;
        rotation = nextRot;
        mirror = nextMirror;
        placements = layout.placements(nextRot, nextMirror);
        resetInspection();
        sync();
        return true;
    }

    private boolean setAttachFace(ServerLevel server, Direction face) {
        BlockPos newOrigin = layout.faceOrigin(worldPosition, face, rotation);
        if (!withinBuildDistance(newOrigin, layout) || !fits(server, newOrigin, layout, rotation)) return false;
        anchored = false;
        attachFace = face;
        origin = newOrigin;
        dx = dy = dz = 0;
        resetInspection();
        sync();
        return true;
    }

    private boolean setWorldAnchor(ServerLevel server, BlockPos newOrigin, Rotation newRotation) {
        if (!withinBuildDistance(newOrigin, layout) || !fits(server, newOrigin, layout, newRotation)) return false;
        anchored = true;
        origin = newOrigin;
        rotation = newRotation;
        dx = dy = dz = 0;
        placements = layout.placements(rotation, mirror);
        resetInspection();
        sync();
        return true;
    }

    private boolean resetToBlueprintDefault(ServerLevel server, ServerPlayer player) {
        var data = blueprintSlot.getItem(0).get(PrinterContent.BLUEPRINT_DATA.get());
        if (data != null) {
            loadBlueprintData(server, player, data);
            return true;
        }
        if (blueprint == null) return false;
        anchored = false;
        attachFace = Direction.UP;
        rotation = Rotation.NONE;
        mirror = Mirror.NONE;
        dx = dy = dz = 0;
        origin = layout.faceOrigin(worldPosition, Direction.UP, Rotation.NONE);
        placements = layout.placements(rotation, mirror);
        resetInspection();
        sync();
        return true;
    }

    private boolean fits(ServerLevel level, BlockPos target, ReactorLayout shape, Rotation rotation) {
        if (shape == null || target == null || !level.isInWorldBounds(target)) return false;
        var far = target.offset(shape.buildWidth(rotation) + 1, shape.height() + 1, shape.buildLength(rotation) + 1);
        var area = level.getWorldBorder();
        return level.isInWorldBounds(far) && area.isWithinBounds(target) && area.isWithinBounds(far);
    }

    static void clientTick(Level level, BlockPos pos, BlockState block, PrinterEntity printer) {
        boolean powered = printer.layout != null && printer.state != COMPLETE && printer.redstoneActive(level, pos);
        if (powered && !printer.clientPower)
            com.justenoughoritech.client.ProjectionController.offerPoweredPrinter(printer);
        printer.clientPower = powered;
    }

    static void tick(Level level, BlockPos pos, BlockState block, PrinterEntity printer) {
        if (!(level instanceof ServerLevel server) || printer.layout == null) return;
        var onlineOwner = printer.owner == null ? null : server.getServer().getPlayerList().getPlayer(printer.owner);
        if (onlineOwner != null && onlineOwner.level() != server) onlineOwner = null;
        printer.inspect(server, onlineOwner);
        if (printer.cursor >= printer.placements.size()) return;
        if (!printer.redstoneActive(level, pos)) {
            printer.state = WAITING;
            return;
        }
        ServerPlayer actor = printer.actingPlayer(server, onlineOwner);
        boolean creative = onlineOwner != null && onlineOwner.isCreative();
        boolean deconstruct = printer.operationMode == PrinterControlRequest.OperationMode.DECONSTRUCT;
        int budget = blocksPerTick(printer.placements.size());
        int scanCap = Math.max(budget * 4, 256);
        int actions = 0, scanned = 0, progressed = 0;
        boolean halted = false;
        BlockPos lastEffectPos = null;
        BlockState lastEffectState = null;

        while (printer.cursor < printer.placements.size() && actions < budget && scanned < scanCap) {
            scanned++;
            if (deconstruct) {
                int res = printer.stepDeconstruct(server, actor, onlineOwner, creative);
                if (res < 0) {
                    halted = true;
                    break;
                }
                progressed++;
                if (res > 0) {
                    actions++;
                    var donePlan = printer.placements.get(printer.placements.size() - printer.cursor);
                    lastEffectPos = printer.anchor().offset(donePlan.x(), donePlan.y(), donePlan.z());
                    lastEffectState = BuiltInRegistries.BLOCK.getValue(Identifier.parse(donePlan.blockId())).defaultBlockState();
                }
            } else {
                BlockState placedState = printer.stepBuild(server, actor, onlineOwner, creative);
                if (placedState == null) {
                    halted = printer.state != PRINTING && printer.state != DECONSTRUCTING && printer.state != COMPLETE;
                    if (halted) break;
                    // MATCH skip: stepBuild already advanced cursor
                    progressed++;
                    continue;
                }
                if (placedState.isAir()) {
                    // Sentinel: unexpected, handled above
                    progressed++;
                    continue;
                }
                progressed++;
                actions++;
                var donePlan = printer.placements.get(printer.cursor - 1);
                lastEffectPos = printer.anchor().offset(donePlan.x(), donePlan.y(), donePlan.z());
                lastEffectState = placedState;
            }
        }

        if (lastEffectPos != null) {
            emitEffect(server, pos, lastEffectPos, lastEffectState, deconstruct);
        }
        if (printer.cursor >= printer.placements.size()) {
            printer.state = COMPLETE;
            printer.resetInspection();
            printer.sync();
        } else if (progressed > 0) {
            if (!halted) {
                printer.state = deconstruct ? DECONSTRUCTING : PRINTING;
            }
            printer.setChanged();
            level.updateNeighbourForOutputSignal(pos, block.getBlock());
        }
    }

    /** Returns the placed BlockState if a block was placed, null when skipped (state==PRINTING) or halted (state==error). */
    private BlockState stepBuild(ServerLevel server, ServerPlayer actor, ServerPlayer onlineOwner, boolean creative) {
        var plan = placements.get(cursor);
        var where = anchor().offset(plan.x(), plan.y(), plan.z());
        if (!server.hasChunkAt(where) || !server.mayInteract(actor, where)) {
            state = OBSTRUCTED;
            return null;
        }
        var blockId = Identifier.tryParse(plan.blockId());
        if (blockId == null || !BuiltInRegistries.BLOCK.containsKey(blockId)) {
            state = OBSTRUCTED;
            return null;
        }
        var build = BuiltInRegistries.BLOCK.getValue(blockId);
        if (server.getBlockState(where).is(build)) {
            advanceCursor();
            state = PRINTING;
            return null;
        }
        BlockState next = oriented(build.defaultBlockState(), plan);
        if (!server.isEmptyBlock(where) || !server.isUnobstructed(next, where, CollisionContext.empty())) {
            state = OBSTRUCTED;
            return null;
        }
        if (creative) {
            if (place(server, actor, where, next)) {
                advanceCursor();
                return next;
            }
            state = OBSTRUCTED;
            return null;
        }
        // Adjacent storage feeds sustained jobs at any distance; online owner inventory is the fallback.
        Item required = build.asItem();
        if (required == Items.AIR) {
            state = MATERIALS;
            return null;
        }
        try (var transaction = Transaction.openRoot()) {
            boolean adjacent = extractAdjacent(server, required, transaction);
            int slot = adjacent || onlineOwner == null ? -1 : findInInventory(onlineOwner.getInventory(), required);
            if (!adjacent && slot < 0) {
                state = MATERIALS;
                return null;
            }
            if (!place(server, actor, where, next)) {
                state = OBSTRUCTED;
                return null;
            }
            transaction.commit();
            if (slot >= 0) onlineOwner.getInventory().removeItem(slot, 1);
            advanceCursor();
            return next;
        }
    }

    /** Returns 1 if removed, 0 if skipped (already gone), -1 if halted (obstructed / storage full). */
    private int stepDeconstruct(ServerLevel server, ServerPlayer actor, ServerPlayer onlineOwner, boolean creative) {
        int index = placements.size() - 1 - cursor;
        var plan = placements.get(index);
        var where = anchor().offset(plan.x(), plan.y(), plan.z());
        if (!server.hasChunkAt(where) || !server.mayInteract(actor, where)) {
            state = OBSTRUCTED;
            return -1;
        }
        var blockId = Identifier.tryParse(plan.blockId());
        if (blockId == null || !BuiltInRegistries.BLOCK.containsKey(blockId)) {
            advanceCursor();
            return 0;
        }
        var expected = BuiltInRegistries.BLOCK.getValue(blockId);
        var current = server.getBlockState(where);
        if (!current.is(expected)) {
            advanceCursor();
            return 0;
        }
        if (CommonHooks.fireBlockBreak(server, actor.gameMode(), actor, where, current).isCanceled()) {
            state = OBSTRUCTED;
            return -1;
        }
        if (creative) {
            if (server.removeBlock(where, false)) {
                advanceCursor();
                return 1;
            }
            state = OBSTRUCTED;
            return -1;
        }
        Item drop = expected.asItem();
        if (drop == Items.AIR) {
            state = OBSTRUCTED;
            return -1;
        }
        try (var transaction = Transaction.openRoot()) {
            boolean stored = insertAdjacent(server, drop, transaction);
            boolean canGivePlayer = !stored && onlineOwner != null && canAcceptInventory(onlineOwner.getInventory(), drop);
            if (!stored && !canGivePlayer) {
                state = STORAGE_FULL;
                return -1;
            }
            if (!server.removeBlock(where, false)) {
                state = OBSTRUCTED;
                return -1;
            }
            transaction.commit();
            if (canGivePlayer) onlineOwner.getInventory().add(new ItemStack(drop));
            advanceCursor();
            return 1;
        }
    }

    private static void emitEffect(ServerLevel server, BlockPos printerPos, BlockPos targetPos, BlockState state, boolean deconstruct) {
        var sound = state.getSoundType();
        var event = deconstruct ? sound.getBreakSound() : sound.getPlaceSound();
        float pitch = 0.95f + server.getRandom().nextFloat() * 0.15f;
        server.playSound(null, targetPos, event, SoundSource.BLOCKS, 0.45f, pitch);
        var particle = new DustParticleOptions(deconstruct ? DISMANTLE_PARTICLE_COLOR : BUILD_PARTICLE_COLOR, 0.7f);
        double sx = printerPos.getX() + 0.5, sy = printerPos.getY() + 1.0, sz = printerPos.getZ() + 0.5;
        double tx = targetPos.getX() + 0.5, ty = targetPos.getY() + 0.5, tz = targetPos.getZ() + 0.5;
        server.sendParticles(particle, tx, ty, tz, 2, 0.12, 0.12, 0.12, 0.0);
        for (int step = 1; step <= 3; step++) {
            double t = step / 4.0;
            server.sendParticles(particle, sx + (tx - sx) * t, sy + (ty - sy) * t, sz + (tz - sz) * t, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private static boolean place(ServerLevel level, ServerPlayer owner, BlockPos pos, BlockState state) {
        var snapshot = BlockSnapshot.create(level.dimension(), level, pos);
        if (!level.setBlock(pos, state, Block.UPDATE_CLIENTS)) return false;
        if (EventHooks.onBlockPlace(owner, snapshot, Direction.UP)) {
            snapshot.restore();
            return false;
        }
        level.updateNeighborsAt(pos, state.getBlock());
        return true;
    }

    private BlockState oriented(BlockState state, ReactorLayout.Placement block) {
        var facing = ReactorLayout.wallFacing(block, layout.buildWidth(rotation));
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            return state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        if (state.hasProperty(BlockStateProperties.FACING)
                && (block.blockId().equals(ReactorLayout.CONTROLLER) || block.blockId().equals(ReactorLayout.ENERGY_PORT)))
            return state.setValue(BlockStateProperties.FACING, facing);
        return state;
    }

    private boolean extractAdjacent(ServerLevel level, Item item, Transaction transaction) {
        for (var dir : Direction.values()) {
            var handler = adjacentStorage(level, dir);
            if (handler == null) continue;
            for (int i = 0; i < handler.size(); i++) {
                ItemResource resource = handler.getResource(i);
                if (!resource.isEmpty() && resource.getItem() == item && handler.extract(i, resource, 1, transaction) == 1)
                    return true;
            }
        }
        return false;
    }

    private boolean insertAdjacent(ServerLevel level, Item item, Transaction transaction) {
        var resource = ItemResource.of(item);
        for (var dir : Direction.values()) {
            var handler = adjacentStorage(level, dir);
            if (handler == null) continue;
            if (handler.insert(resource, 1, transaction) == 1) return true;
        }
        return false;
    }

    private static boolean canAcceptInventory(Inventory inventory, Item item) {
        for (int i = 0; i < 36; i++) {
            var stack = inventory.getItem(i);
            if (stack.isEmpty() || (stack.is(item) && stack.getCount() < stack.getMaxStackSize())) return true;
        }
        return false;
    }

    private ResourceHandler<ItemResource> adjacentStorage(ServerLevel level, Direction direction) {
        var pos = worldPosition.relative(direction);
        return level.hasChunkAt(pos) ? level.getCapability(Capabilities.Item.BLOCK, pos, direction.getOpposite()) : null;
    }

    private void resetInspection() {
        inspection = null;
        report = null;
        nextInspection = 0;
    }

    private void inspect(ServerLevel level, ServerPlayer owner) {
        if (level.getGameTime() < nextInspection)
            return;
        if (operationMode == PrinterControlRequest.OperationMode.DECONSTRUCT) {
            report = new PrinterReport(worldPosition, anchor(), null, false, List.of());
            PacketDistributor.sendToPlayersTrackingChunk(level, ChunkPos.containing(worldPosition), report);
            inspection = null;
            nextInspection = level.getGameTime() + 20;
            return;
        }
        if (inspection == null) inspection = new BuildInspection(placements, anchor(), cursor);
        if (!inspection.scan(level, 2048, false)) return;
        var remaining = inspection.required();
        if (owner != null && owner.isCreative()) remaining.clear();
        else subtractSupplies(level, owner, remaining);
        var missing = remaining.entrySet().stream().filter(entry -> entry.getValue() > 0)
                .map(entry -> new PrinterReport.Missing(BuiltInRegistries.ITEM.getKey(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparing(entry -> entry.item().toString())).toList();
        report = new PrinterReport(worldPosition, inspection.origin(), inspection.obstruction(), inspection.unloaded(), missing);
        PacketDistributor.sendToPlayersTrackingChunk(level, ChunkPos.containing(worldPosition), report);
        inspection = null;
        nextInspection = level.getGameTime() + 20;
    }

    private void subtractSupplies(ServerLevel level, ServerPlayer owner, Map<Item, Integer> remaining) {
        if (remaining.isEmpty()) return;
        // One uncommitted transaction accounts for shared inventories without consuming items.
        try (var transaction = Transaction.openRoot()) {
            for (var direction : Direction.values()) {
                var storage = adjacentStorage(level, direction);
                if (storage == null) continue;
                for (int slot = 0; slot < storage.size(); slot++) {
                    var resource = storage.getResource(slot);
                    if (resource.isEmpty()) continue;
                    int need = remaining.getOrDefault(resource.getItem(), 0);
                    if (need > 0) remaining.put(resource.getItem(), need - storage.extract(slot, resource, need, transaction));
                }
            }
        }
        if (owner == null) return;
        for (int slot = 0; slot < 36; slot++) {
            var item = owner.getInventory().getItem(slot);
            if (!item.isEmpty()) remaining.computeIfPresent(item.getItem(), (ignored, need) -> Math.max(0, need - item.getCount()));
        }
    }

    private static int findInInventory(Inventory inventory, Item item) {
        for (int i = 0; i < 36; i++) if (inventory.getItem(i).is(item)) return i;
        return -1;
    }

    private void advanceCursor() {
        if (inspection != null) inspection.placed(cursor);
        cursor++;
    }

    private void sync() {
        setChanged();
        if (level instanceof ServerLevel server) {
            server.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
            server.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState blockState) {
        super.preRemoveSideEffects(pos, blockState);
        if (level != null && !level.isClientSide() && !blueprintSlot.getItem(0).isEmpty()) {
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    blueprintSlot.removeItemNoUpdate(0));
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!blueprintSlot.getItem(0).isEmpty())
            output.store("blueprint_item", ItemStack.CODEC, blueprintSlot.getItem(0));
        output.storeNullable("owner", UUIDUtil.CODEC, owner);
        output.storeNullable("blueprint", PrinterLayout.CODEC, blueprint);
        output.storeNullable("origin", BlockPos.CODEC, origin);
        output.putBoolean("anchored", anchored);
        output.store("attach_face", Direction.CODEC, attachFace);
        if (rotation != Rotation.NONE) output.store("rotation", Rotation.CODEC, rotation);
        if (mirror != Mirror.NONE) output.store("mirror", Mirror.CODEC, mirror);
        output.putInt("dx", dx);
        output.putInt("dy", dy);
        output.putInt("dz", dz);
        output.putInt("progress", cursor);
        output.putString("redstone_mode", redstoneMode.name());
        output.putString("operation_mode", operationMode.name());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        resetInspection();
        loading = true;
        try {
            blueprintSlot.setItem(0, input.read("blueprint_item", ItemStack.CODEC).orElse(ItemStack.EMPTY));
        } finally {
            loading = false;
        }
        loadedBlueprintData = blueprintSlot.getItem(0).get(PrinterContent.BLUEPRINT_DATA.get());
        owner = input.read("owner", UUIDUtil.CODEC).orElse(null);
        blueprint = input.read("blueprint", PrinterLayout.CODEC)
                .orElse(loadedBlueprintData == null ? null : loadedBlueprintData.layout());
        origin = input.read("origin", BlockPos.CODEC).orElse(null);
        anchored = input.getBooleanOr("anchored", false);
        attachFace = input.read("attach_face", Direction.CODEC).orElse(Direction.UP);
        rotation = input.read("rotation", Rotation.CODEC).orElse(Rotation.NONE);
        mirror = ReactorLayout.Orientation.canonicalMirror(input.read("mirror", Mirror.CODEC).orElse(Mirror.NONE));
        dx = Math.clamp(input.getIntOr("dx", 0), -SHIFT, SHIFT);
        dy = Math.clamp(input.getIntOr("dy", 0), -SHIFT, SHIFT);
        dz = Math.clamp(input.getIntOr("dz", 0), -SHIFT, SHIFT);
        redstoneMode = parseEnum(PrinterControlRequest.RedstoneMode.class,
                input.getStringOr("redstone_mode", PrinterControlRequest.RedstoneMode.HIGH.name()),
                PrinterControlRequest.RedstoneMode.HIGH);
        operationMode = parseEnum(PrinterControlRequest.OperationMode.class,
                input.getStringOr("operation_mode", PrinterControlRequest.OperationMode.BUILD.name()),
                PrinterControlRequest.OperationMode.BUILD);
        layout = blueprint == null || origin == null ? null : blueprint.resolve(PrinterLayout.LIMIT);
        placements = layout == null ? List.of() : layout.placements(rotation, mirror);
        cursor = Math.clamp(input.getIntOr("progress", 0), 0, placements.size());
        state = layout == null ? IDLE : cursor == placements.size() ? COMPLETE : WAITING;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String name, E fallback) {
        if (name == null || name.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider lookup) {
        return saveWithoutMetadata(lookup);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.just_enough_oritech.reactor_printer");
    }

    @Override
    public PrinterMenu createMenu(int id, Inventory inventory, Player player) {
        return new PrinterMenu(id, inventory, worldPosition);
    }
}
