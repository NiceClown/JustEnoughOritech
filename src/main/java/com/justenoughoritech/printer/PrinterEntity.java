package com.justenoughoritech.printer;

import com.justenoughoritech.network.ServerConfigReader;
import com.justenoughoritech.sim.ReactorLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.neoforged.neoforge.common.util.BlockSnapshot;
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
    static final int SHIFT = 64;
    static final int MAX_BUILD_DISTANCE = 128;
    private static final int IDLE = 0, WAITING = 1, PRINTING = 2, MATERIALS = 3, OBSTRUCTED = 4, COMPLETE = 5, OWNER = 6;
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
        return player != null && player.level() == server && worldPosition.closerToCenterThan(player.position(), RANGE)
                && player.isCreative();
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

    int progress() { return cursor; }
    int stateId() { return state; }
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
                    && worldPosition.closerThan(worldAnchor.origin(), MAX_BUILD_DISTANCE)
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
        if (!fits(server, candidate, checked, rotation) || !worldPosition.closerThan(candidate, MAX_BUILD_DISTANCE)) {
            for (Direction fallback : FALLBACK_FACES) {
                BlockPos alt = checked.faceOrigin(worldPosition, fallback, rotation);
                if (fits(server, alt, checked, rotation) && worldPosition.closerThan(alt, MAX_BUILD_DISTANCE)) {
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
            case RESET_PROGRESS -> true;
        };
    }

    private boolean moveOffset(ServerLevel server, int stepX, int stepY, int stepZ) {
        int nextX = Math.clamp(dx + stepX, -SHIFT, SHIFT);
        int nextY = Math.clamp(dy + stepY, -SHIFT, SHIFT);
        int nextZ = Math.clamp(dz + stepZ, -SHIFT, SHIFT);
        var target = origin.offset(nextX, nextY, nextZ);
        if (!worldPosition.closerThan(target, MAX_BUILD_DISTANCE) || !fits(server, target, layout, rotation)) return false;
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
        if (!worldPosition.closerThan(target, MAX_BUILD_DISTANCE) || !fits(server, target, layout, nextRot)) return false;
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
        if (!worldPosition.closerThan(newOrigin, MAX_BUILD_DISTANCE) || !fits(server, newOrigin, layout, rotation)) return false;
        anchored = false;
        attachFace = face;
        origin = newOrigin;
        dx = dy = dz = 0;
        resetInspection();
        sync();
        return true;
    }

    private boolean setWorldAnchor(ServerLevel server, BlockPos newOrigin, Rotation newRotation) {
        if (!worldPosition.closerThan(newOrigin, MAX_BUILD_DISTANCE) || !fits(server, newOrigin, layout, newRotation)) return false;
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
        boolean powered = printer.layout != null && printer.state != COMPLETE && level.hasNeighborSignal(pos);
        if (powered && !printer.clientPower)
            com.justenoughoritech.client.ProjectionController.offerPoweredPrinter(printer);
        printer.clientPower = powered;
    }

    static void tick(Level level, BlockPos pos, BlockState block, PrinterEntity printer) {
        if (!(level instanceof ServerLevel server) || printer.layout == null) return;
        var owner = printer.owner == null ? null : server.getServer().getPlayerList().getPlayer(printer.owner);
        if (owner != null && (owner.level() != server || !pos.closerToCenterThan(owner.position(), RANGE))) owner = null;
        printer.inspect(server, owner);
        if (printer.cursor >= printer.placements.size()) return;
        if (!level.hasNeighborSignal(pos)) {
            printer.state = WAITING;
            return;
        }
        if (owner == null) {
            printer.state = OWNER;
            return;
        }
        var plan = printer.placements.get(printer.cursor);
        var where = printer.anchor().offset(plan.x(), plan.y(), plan.z());
        if (!server.hasChunkAt(where) || !server.mayInteract(owner, where)) {
            printer.state = OBSTRUCTED;
            return;
        }
        var blockId = Identifier.tryParse(plan.blockId());
        if (blockId == null || !BuiltInRegistries.BLOCK.containsKey(blockId)) {
            printer.state = OBSTRUCTED;
            return;
        }
        var build = BuiltInRegistries.BLOCK.getValue(blockId);
        if (server.getBlockState(where).is(build)) {
            printer.advance();
            return;
        }
        BlockState next = printer.oriented(build.defaultBlockState(), plan);
        if (!server.isEmptyBlock(where) || !server.isUnobstructed(next, where, CollisionContext.empty())) {
            printer.state = OBSTRUCTED;
            return;
        }

        if (owner.isCreative()) {
            if (place(server, owner, where, next)) printer.advance();
            else printer.state = OBSTRUCTED;
            return;
        }

        // Adjacent storage feeds sustained jobs. The owner's main inventory is the fallback.
        Item required = build.asItem();
        if (required == Items.AIR) {
            printer.state = MATERIALS;
            return;
        }
        try (var transaction = Transaction.openRoot()) {
            boolean adjacent = printer.extractAdjacent(server, required, transaction);
            int slot = adjacent ? -1 : findInInventory(owner.getInventory(), required);
            if (!adjacent && slot < 0) {
                printer.state = MATERIALS;
                return;
            }
            if (!place(server, owner, where, next)) {
                printer.state = OBSTRUCTED;
                return;
            }
            transaction.commit();
            if (slot >= 0) owner.getInventory().removeItem(slot, 1);
            printer.advance();
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
        if (level.getGameTime() < nextInspection || (owner == null &&
                level.getNearestPlayer(worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5, 48, false) == null))
            return;
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

    private void advance() {
        if (inspection != null) inspection.placed(cursor);
        cursor++;
        state = cursor == placements.size() ? COMPLETE : PRINTING;
        if (state == COMPLETE) {
            resetInspection();
            sync();
        } else setChanged();
    }

    private void sync() {
        setChanged();
        if (level instanceof ServerLevel server)
            server.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
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
        layout = blueprint == null || origin == null ? null : blueprint.resolve(PrinterLayout.LIMIT);
        placements = layout == null ? List.of() : layout.placements(rotation, mirror);
        cursor = Math.clamp(input.getIntOr("progress", 0), 0, placements.size());
        state = layout == null ? IDLE : cursor == placements.size() ? COMPLETE : WAITING;
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
