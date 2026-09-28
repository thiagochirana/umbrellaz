package dev.chirana.umbrellaz.lock;

import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class LockEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(LockEvents.class);
    static final double VANILLA_BLOCK_INTERACTION_MARGIN = 1.0D;
    private static volatile LockEvents runtime;

    private final LockService lockService;
    private final LockRepository repository;
    private final DatabaseExecutor databaseExecutor;
    private final AuthorizationService authorizationService;
    private final LockPlacementTracker placements = new LockPlacementTracker();
    private final LockMarkerService markers;
    private final LockProtection protection;
    private final Set<BreakKey> pendingBreaks = ConcurrentHashMap.newKeySet();
    private final Map<BreakKey, PendingBreak> breakOperations = new ConcurrentHashMap<>();
    private final Map<UUID, CreationReservation> reservations = new ConcurrentHashMap<>();
    private final Map<ReservationKey, UUID> reservationKeys = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile MinecraftServer activeServer;

    private LockEvents(LockService lockService, LockRepository repository, DatabaseExecutor databaseExecutor,
                       AuthorizationService authorizationService) {
        this.lockService = Objects.requireNonNull(lockService, "lockService");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        this.protection = new LockProtection(lockService.cache(), placements);
        this.markers = new LockMarkerService(lockService.cache());
    }

    public static void register(LockService lockService, LockRepository repository,
                                DatabaseExecutor databaseExecutor, AuthorizationService authorizationService) {
        LockEvents events = new LockEvents(lockService, repository, databaseExecutor, authorizationService);
        runtime = events;
        UseBlockCallback.EVENT.register(events::useBlock);
        PlayerBlockBreakEvents.BEFORE.register(events::beforeBreak);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            events.cancelReservations(server, handler.player);
            lockService.disconnect(handler.player.getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(events::shutdownReservations);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            events.activeServer = server;
            try {
                LockWorldIdentity.initialize(server);
                events.markers.reconcileAll(server);
            } catch (RuntimeException failure) {
                LOGGER.error("Unable to initialize the lock world identity; lock interactions remain blocked", failure);
            }
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, wasGenerated) -> events.markers.reconcileChunk(level,
                chunk.getPos()));
    }

    public static java.util.concurrent.CompletableFuture<Void> loadPlacementSnapshot() {
        LockEvents events = runtime;
        if (events == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("Lock events are not registered"));
        }
        return events.lockService.loadPlacementSnapshot(events.repository, events.databaseExecutor, events.placements);
    }

    public static void shutdown(MinecraftServer server) {
        LockEvents events = runtime;
        if (events != null) {
            events.shutdownReservations(server);
        }
    }

    public static void afterSuccessfulPlacement(Level level, Player player, BlockPos position, BlockState state) {
        LockEvents events = runtime;
        if (events != null) {
            events.recordPlacement(level, player, position, state);
        }
    }

    public static boolean beforePlacement(Level level, Player player, BlockPos position, BlockState state) {
        LockEvents events = runtime;
        return events == null ? LockBlockAdapter.classify(state).isEmpty()
                : events.allowPlacement(level, player, position, state);
    }

    public static void reconcileMarkers() {
        LockEvents events = runtime;
        MinecraftServer server = events == null ? null : events.activeServer;
        if (events != null && server != null) {
            try {
                server.execute(() -> events.markers.reconcileAll(server));
            } catch (RuntimeException ignored) {
                // The server is stopping; no further entity mutation is permitted.
            }
        }
    }

    public static LockProtection.Decision environmentalProtection(ServerLevel level, BlockPos position) {
        LockEvents events = runtime;
        return events == null ? LockProtection.unavailableDecision(level, position)
                : events.protection.decision(level, position);
    }

    public static boolean blocksEnvironmentalMutation(ServerLevel level, BlockPos position) {
        LockProtection.Decision decision = environmentalProtection(level, position);
        return decision.blocksEnvironmentalMutation();
    }

    private boolean allowPlacement(Level level, Player player, BlockPos position, BlockState state) {
        Optional<LockBlockType> type = LockBlockAdapter.classify(state);
        if (type.isEmpty()) {
            return true;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer)
                || !LockWorldIdentity.isReady() || !placements.isReady() || !lockService.cache().isReady()) {
            return false;
        }
        if (isBreakPending(serverLevel, position)) {
            return false;
        }
        if (cachedProvenance(serverLevel, position).isPresent()) {
            return false;
        }
        if ((type.get() == LockBlockType.CHEST || type.get() == LockBlockType.TRAPPED_CHEST)
                && Direction.Plane.HORIZONTAL.stream().anyMatch(direction -> {
                    BlockPos neighbor = position.relative(direction);
                    BlockState neighborState = serverLevel.getBlockState(neighbor);
                    return neighborState.getBlock() == state.getBlock()
                            && cachedProvenance(serverLevel, neighbor).isPresent();
                })) {
            return false;
        }
        BlockPos connected = LockBlockAdapter.connectedChestPosition(serverLevel, position, state);
        return connected.equals(position) || cachedProvenance(serverLevel, connected).isEmpty();
    }

    private void recordPlacement(Level level, Player player, BlockPos position, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (!LockWorldIdentity.isReady()) {
            LOGGER.error("Ignoring lock placement because the world identity is unavailable");
            return;
        }
        Optional<LockBlockType> type = LockBlockAdapter.classify(state);
        if (type.isEmpty()) {
            return;
        }
        PlacementProvenance placement = placements.record(serverLevel, position, serverPlayer.getUUID(), type.get());
        placements.markPending(placement);
        lockService.recordPlacementAsync(repository, databaseExecutor, placement)
                .thenRun(() -> placements.markPersisted(placement))
                .exceptionally(failure -> {
                    placements.remove(placement);
                    LOGGER.error("Unable to persist lock placement at {}", position, failure);
                    return null;
                });
    }

    private InteractionResult useBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (isBreakPending(serverLevel, hit.getBlockPos())) {
            return InteractionResult.FAIL;
        }
        Optional<TargetContext> context = resolve(serverLevel, hit.getBlockPos());
        if (context.isEmpty()) {
            if (!LockWorldIdentity.isReady()
                    && LockBlockAdapter.classify(serverLevel.getBlockState(hit.getBlockPos())).isPresent()) {
                message(serverPlayer, "Lock world identity is unavailable; try again later.");
                return InteractionResult.FAIL;
            }
            if (!lockService.cache().isReady()
                    || !placements.isReady()) {
                if (LockBlockAdapter.classify(serverLevel.getBlockState(hit.getBlockPos())).isPresent()) {
                    message(serverPlayer, "Lock data is still loading; try again.");
                    return InteractionResult.FAIL;
                }
            }
            if (LockBlockAdapter.classify(serverLevel.getBlockState(hit.getBlockPos())).isPresent()
                    && LockItem.isMarked(serverPlayer.getItemInHand(hand))) {
                message(serverPlayer, "This block has no valid placement provenance for locking.");
                return InteractionResult.FAIL;
            }
            if (cachedProvenance(serverLevel, hit.getBlockPos()).isPresent()) {
                message(serverPlayer, "The locked target provenance is no longer valid.");
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        }
        TargetContext target = context.get();
        if (!isWithinInteractionRange(serverPlayer, target)) {
            return InteractionResult.FAIL;
        }
        ItemStack held = serverPlayer.getItemInHand(hand);
        boolean removalGesture = serverPlayer.isShiftKeyDown() && held.isEmpty();
        LockAction action = removalGesture ? LockAction.REMOVE : LockAction.OPEN;
        LockAccessResult access = lockService.lookupAccess(serverPlayer.getUUID(), target.placements(), action);
        if (access.decision() == LockAccessDecision.NOT_READY) {
            message(serverPlayer, "Lock data is still loading; try again.");
            return InteractionResult.FAIL;
        }
        if (access.decision() == LockAccessDecision.NOT_LOCKED) {
            if (removalGesture) {
                return InteractionResult.PASS;
            }
            if (LockItem.isMarked(held)) {
                openCreationMenu(serverPlayer, hand, target);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }
        boolean administrator = authorizationService.isAdministrator(serverPlayer.getUUID());
        if (administrator || access.decision() == LockAccessDecision.GRANTED) {
            if (removalGesture) {
                removeLocker(serverPlayer, target, access.lockerId());
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }
        openPasswordMenu(serverPlayer, target, action);
        return InteractionResult.SUCCESS;
    }

    private boolean beforeBreak(Level level, Player player, BlockPos position, BlockState state, BlockEntity blockEntity) {
        if (!(level instanceof ServerLevel world) || !(player instanceof ServerPlayer serverPlayer)) {
            return true;
        }
        if (LockBlockAdapter.classify(state).isEmpty()) {
            return true;
        }
        Optional<LockTarget> worldTarget = LockWorldIdentity.tryTarget(world, position);
        if (worldTarget.isEmpty()) {
            return false;
        }
        Optional<TargetContext> resolved = resolveForMutation(world, position);
        if (resolved.isPresent()) {
            PlacementProvenance currentPlacement = resolved.get().clickedPlacement();
            BreakKey currentKey = new BreakKey(serverPlayer.getUUID(), worldTarget.get(),
                    currentPlacement.generationId());
            if (pendingBreaks.remove(currentKey)) {
                return true;
            }
            if (isBreakPending(worldTarget.get())) {
                return false;
            }
            pendingBreaks.removeIf(key -> key.playerUuid().equals(serverPlayer.getUUID())
                    && key.target().equals(worldTarget.get())
                    && !key.generationId().equals(currentPlacement.generationId()));
        }
        if (resolved.isEmpty()) {
            if (isBreakPending(worldTarget.get())) {
                return false;
            }
            if (!LockWorldIdentity.isReady()
                    && LockBlockAdapter.classify(world.getBlockState(position)).isPresent()) {
                message(serverPlayer, "Lock world identity is unavailable; try again later.");
                return false;
            }
            if ((!lockService.cache().isReady() || !placements.isReady())
                    && LockBlockAdapter.classify(world.getBlockState(position)).isPresent()) {
                message(serverPlayer, "Lock data is still loading; try again.");
                return false;
            }
            if (cachedProvenance(world, position).isPresent()) {
                message(serverPlayer, "The locked target provenance is no longer valid.");
                return false;
            }
            return true;
        }
        TargetContext target = resolved.get();
        if (!isWithinInteractionRange(serverPlayer, target)) {
            return false;
        }
        LockAccessResult access = lockService.lookupAccess(serverPlayer.getUUID(), target.placements(), LockAction.BREAK);
        if (access.decision() == LockAccessDecision.NOT_READY) {
            message(serverPlayer, "Lock data is still loading; try again.");
            return false;
        }
        if (access.decision() == LockAccessDecision.NOT_LOCKED) {
            beginAuthorizedBreak(serverPlayer, target, false);
            return false;
        }
        if (!target.completeTopology()) {
            message(serverPlayer, "The double-chest topology is not fully known; try again.");
            return false;
        }
        if (authorizationService.isAdministrator(serverPlayer.getUUID())
                || access.decision() == LockAccessDecision.GRANTED) {
            beginAuthorizedBreak(serverPlayer, target, true);
        } else {
            openPasswordMenu(serverPlayer, target, LockAction.BREAK);
        }
        return false;
    }

    private void openCreationMenu(ServerPlayer player, InteractionHand hand, TargetContext target) {
        MinecraftServer server = server(player);
        UUID playerUuid = player.getUUID();
        Object menuToken = new Object();
        AtomicBoolean submitted = new AtomicBoolean();
        player.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inventory, ignored) ->
                        new LockAnvilMenu(id, inventory, playerUuid, menuToken,
                                current -> current == player && isCreationContextValid(current, hand, target),
                                password -> {
                                    if (submitted.compareAndSet(false, true)) {
                                        submitCreation(server, player, hand, target, password, submitted);
                                    }
                                }),
                Component.literal("Cadeado")));
    }

    private void submitCreation(MinecraftServer server, ServerPlayer menuPlayer, InteractionHand hand,
                                TargetContext target,
                                String password, AtomicBoolean submitted) {
        UUID playerUuid = menuPlayer.getUUID();
        ServerPlayer player = activePlayer(server, playerUuid);
        if (stopping.get() || player != menuPlayer || !target.completeTopology()
                || !isCreationContextValid(player, hand, target)) {
            submitted.set(false);
            messageIfValid(server, playerUuid, target, "This lock target is no longer valid.");
            return;
        }
        if (!new PasswordPolicy().isValid(password)) {
            submitted.set(false);
            message(player, "Password must be one letter followed by three digits.");
            return;
        }
        CreationReservation reservation = reserveCreation(server, player, hand, target);
        if (reservation == null) {
            submitted.set(false);
            message(player, "The lock item is no longer available for this target.");
            return;
        }
        lockService.createPasswordAsync(password).whenComplete((hash, failure) -> {
            if (stopping.get() || reservation.cancelled().get()) {
                return;
            }
            try {
                server.execute(() -> {
                    if (stopping.get() || reservation.cancelled().get()) {
                        return;
                    }
                    if (failure != null) {
                        submitted.set(false);
                        restoreReservation(server, reservation);
                        messageIfValid(server, playerUuid, target,
                                "Unable to create the lock password; try again.");
                        return;
                    }
                    ServerPlayer current = activePlayer(server, playerUuid);
                    if (!reservationValid(current, reservation)) {
                        submitted.set(false);
                        restoreReservation(server, reservation);
                        messageIfValid(server, playerUuid, target,
                                "The target or lock item changed before confirmation.");
                        return;
                    }
                    UUID lockerId = UUID.randomUUID();
                    LockerMetadata locker = new LockerMetadata(lockerId, playerUuid, hash,
                            Instant.now(), Instant.now(), 1, members(lockerId, target));
                    createLocker(server, reservation, locker, submitted);
                });
            } catch (RuntimeException dispatchFailure) {
                LOGGER.warn("Unable to dispatch lock creation completion for {}", playerUuid, dispatchFailure);
            }
        });
    }

    private void createLocker(MinecraftServer server, CreationReservation reservation,
                              LockerMetadata locker, AtomicBoolean submitted) {
        reservation.lockerId().set(locker.lockerId());
        lockService.createLockerAsync(repository, databaseExecutor, locker)
                .whenComplete((ignored, failure) -> {
                    if (stopping.get() || reservation.cancelled().get()) {
                        compensateLocker(server, reservation, locker.lockerId(), null);
                        return;
                    }
                    try {
                        server.execute(() -> {
                            if (stopping.get() || reservation.cancelled().get()) {
                                compensateLocker(server, reservation, locker.lockerId(), null);
                                return;
                            }
                    ServerPlayer current = activePlayer(server, reservation.playerUuid());
                    if (failure != null) {
                        submitted.set(false);
                        compensateLocker(server, reservation, locker.lockerId(),
                                "Unable to save the lock; the item was not consumed.");
                        return;
                    }
                    if (!reservationValid(current, reservation)) {
                        submitted.set(false);
                        compensateLocker(server, reservation, locker.lockerId(),
                                "The target changed before lock creation completed.");
                        return;
                    }
                    reservations.remove(reservation.token(), reservation);
                    reservationKeys.remove(reservation.key(), reservation.token());
                    reservation.itemFinalized().set(true);
                    current.closeContainer();
                    markers.reconcileTargets(reservation.level(), reservation.target().placements());
                    message(current, "Cadeado criado.");
                        });
                    } catch (RuntimeException dispatchFailure) {
                        LOGGER.warn("Unable to dispatch lock persistence completion for {}",
                                reservation.playerUuid(), dispatchFailure);
                    }
                });
    }

    private CreationReservation reserveCreation(MinecraftServer server, ServerPlayer player,
                                                 InteractionHand hand, TargetContext target) {
        if (stopping.get() || player == null) {
            return null;
        }
        ItemStack held = player.getItemInHand(hand);
        if (!(player.containerMenu instanceof LockAnvilMenu menu)
                || !LockItem.isMarked(held) || held.getCount() < 1
                || !isCreationContextValid(player, hand, target)) {
            return null;
        }
        UUID token = UUID.randomUUID();
        ReservationKey key = new ReservationKey(player.getUUID(), player);
        if (reservationKeys.putIfAbsent(key, token) != null) {
            return null;
        }
        CreationReservation reservation = new CreationReservation(token, player.getUUID(), player, hand,
                menu, key, target, player.level(), target.clickedPosition(), held.copyWithCount(1),
                new AtomicBoolean(false), new AtomicBoolean(false), new AtomicReference<>());
        reservations.put(token, reservation);
        held.shrink(1);
        return reservation;
    }

    private boolean reservationValid(ServerPlayer player, CreationReservation reservation) {
        return !reservation.cancelled().get() && player != null && player == reservation.session() && player.isAlive()
                && player.level() == reservation.level()
                && player.containerMenu == reservation.menu()
                && reservations.get(reservation.token()) == reservation
                && isCreationTargetValid(player, reservation.target());
    }

    private void restoreReservation(MinecraftServer server, CreationReservation reservation) {
        restoreReservation(server, reservation, false);
    }

    private void restoreReservation(MinecraftServer server, CreationReservation reservation, boolean dropOnly) {
        reservation.cancelled().set(true);
        if (!reservations.remove(reservation.token(), reservation)) {
            return;
        }
        reservationKeys.remove(reservation.key(), reservation.token());
        if (!reservation.itemFinalized().compareAndSet(false, true)) {
            return;
        }
        ServerPlayer current = activePlayer(server, reservation.playerUuid());
        if (!dropOnly && current != null && current == reservation.session()
                && current.containerMenu == reservation.menu()
                && isContextValid(current, reservation.target())) {
            ItemStack restored = reservation.item().copy();
            if (!current.getInventory().add(restored)) {
                current.drop(restored, false, net.minecraft.util.Prediction.SERVER_ONLY);
            }
        } else {
            dropItem(reservation.level(), reservation.position(), reservation.item().copy());
        }
    }

    private void cancelReservations(MinecraftServer server, ServerPlayer session) {
        for (CreationReservation reservation : List.copyOf(reservations.values())) {
            if (reservation.playerUuid().equals(session.getUUID()) && reservation.session() == session) {
                cancelReservation(server, reservation, true);
            }
        }
    }

    private void shutdownReservations(MinecraftServer server) {
        stopping.set(true);
        for (CreationReservation reservation : List.copyOf(reservations.values())) {
            cancelReservation(server, reservation, true);
        }
    }

    private void cancelReservation(MinecraftServer server, CreationReservation reservation, boolean dropOnly) {
        if (!reservation.cancelled().compareAndSet(false, true)) {
            return;
        }
        restoreReservation(server, reservation, dropOnly);
        UUID lockerId = reservation.lockerId().get();
        if (lockerId != null) {
            compensateLocker(server, reservation, lockerId, null);
        }
    }

    private void compensateLocker(MinecraftServer server, CreationReservation reservation, UUID lockerId,
                                  String message) {
        lockService.removeLockerAsync(repository, databaseExecutor, lockerId, reservation.target().placements())
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        LOGGER.error("Unable to compensate stale lock creation for {}", lockerId, failure);
                    }
                    if (stopping.get() || reservation.cancelled().get() || message == null) {
                        return;
                    }
                    try {
                        server.execute(() -> {
                            if (failure != null) {
                                return;
                            }
                            restoreReservation(server, reservation);
                            messageIfValid(server, reservation.playerUuid(), reservation.target(), message);
                        });
                    } catch (RuntimeException dispatchFailure) {
                        LOGGER.warn("Unable to dispatch lock compensation completion for {}", lockerId,
                                dispatchFailure);
                    }
                });
    }

    private void openPasswordMenu(ServerPlayer player, TargetContext target, LockAction action) {
        MinecraftServer server = server(player);
        UUID playerUuid = player.getUUID();
        Object menuToken = new Object();
        AtomicBoolean submitted = new AtomicBoolean();
        player.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inventory, ignored) ->
                        new LockAnvilMenu(id, inventory, playerUuid, menuToken,
                                current -> current == player && isContextValid(current, target)
                                        && (action == LockAction.OPEN || target.completeTopology()),
                                password -> {
                                    if (submitted.compareAndSet(false, true)) {
                                        submitPassword(server, player, menuToken, target, action, password, submitted);
                                    }
                                }),
                Component.literal("Cadeado")));
    }

    private void submitPassword(MinecraftServer server, ServerPlayer menuPlayer, Object menuToken,
                                TargetContext target, LockAction action,
                                String password, AtomicBoolean submitted) {
        UUID playerUuid = menuPlayer.getUUID();
        if (activePlayer(server, playerUuid) != menuPlayer || !hasMenu(menuPlayer, menuToken)
                || !isContextValid(menuPlayer, target)
                || (action != LockAction.OPEN && !target.completeTopology())) {
            submitted.set(false);
            return;
        }
        lockService.confirmPassword(playerUuid, target.placements(), action, password)
                .whenComplete((result, failure) -> server.execute(() -> {
                    ServerPlayer player = activePlayer(server, playerUuid);
                    if (player == null || player != menuPlayer || !hasMenu(player, menuToken)
                            || !isContextValid(player, target)
                            || (action != LockAction.OPEN && !target.completeTopology())) {
                        submitted.set(false);
                        return;
                    }
                    if (failure != null) {
                        submitted.set(false);
                        message(player, "Unable to verify the password; try again.");
                        return;
                    }
                    if (result.decision() == LockAccessDecision.GRANTED) {
                        player.closeContainer();
                        if (action == LockAction.OPEN) {
                            openVanillaTarget(player, target);
                        } else if (action == LockAction.BREAK) {
                            beginAuthorizedBreak(player, target, true);
                        } else {
                            removeLocker(player, target, result.lockerId());
                        }
                        return;
                    }
                    submitted.set(false);
                    message(player, switch (result.decision()) {
                        case COOLDOWN -> "Too many attempts; wait before trying again.";
                        case VERIFICATION_BUSY -> "Password verification is busy; try again.";
                        default -> "Wrong password.";
                    });
                }));
    }

    private void openVanillaTarget(ServerPlayer player, TargetContext target) {
        if (!isContextValid(player, target)) {
            message(player, "The target changed before it could be opened.");
            return;
        }
        BlockPos clicked = target.clickedPosition();
        BlockHitResult hit = new BlockHitResult(player.position(), net.minecraft.core.Direction.UP,
                clicked, false);
        player.level().getBlockState(clicked).useWithoutItem(player.level(), player, hit);
    }

    private void removeLocker(ServerPlayer player, TargetContext target, UUID lockerId) {
        if (!target.completeTopology() || !isContextValid(player, target)) {
            message(player, "The target changed before removal.");
            return;
        }
        MinecraftServer server = server(player);
        UUID playerUuid = player.getUUID();
        ServerLevel originalLevel = player.level();
        lockService.removeLockerAsync(repository, databaseExecutor, lockerId, target.placements())
                .whenComplete((removed, failure) -> server.execute(() -> {
                    ServerPlayer current = activePlayer(server, playerUuid);
                    if (failure != null) {
                        messageIfValid(server, playerUuid, target,
                                "Unable to remove the lock; nothing was changed.");
                        return;
                    }
                    if (!Boolean.TRUE.equals(removed)) {
                        messageIfValid(server, playerUuid, target, "The target changed before removal.");
                        return;
                    }
                    markers.reconcileAfterRemoval(originalLevel, target.placements());
                    if (current == null) {
                        dropReturnedItem(originalLevel, target.clickedPosition());
                        return;
                    }
                    if (current != player || !isContextValid(current, target)
                            || !isWithinInteractionRange(current, target)) {
                        dropReturnedItem(originalLevel, target.clickedPosition());
                        return;
                    }
                    ItemStack returned = LockItem.create();
                    if (!current.getInventory().add(returned)) {
                        current.drop(returned, false, net.minecraft.util.Prediction.SERVER_ONLY);
                    }
                    message(current, "Cadeado removido.");
                }));
    }

    private void beginAuthorizedBreak(ServerPlayer player, TargetContext target, boolean requireCompleteTopology) {
        MinecraftServer server = server(player);
        if ((requireCompleteTopology && !target.completeTopology())
                || activePlayer(server, player.getUUID()) != player
                || !isMutationContextValid(player, target)) {
            message(player, "The target changed before breaking.");
            return;
        }
        BreakKey key = new BreakKey(player.getUUID(), target.clickedPlacement().target(),
                target.clickedPlacement().generationId());
        PendingBreak operation = new PendingBreak(key, player, target, new BreakTransaction());
        if (breakOperations.putIfAbsent(key, operation) != null) {
            return;
        }
        pendingBreaks.add(key);
        boolean destroyed;
        try {
            destroyed = player.gameMode.destroyBlock(target.clickedPosition());
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to destroy lock block at {}", target.clickedPosition(), failure);
            destroyed = false;
        } finally {
            pendingBreaks.remove(key);
        }
        if (!operation.transaction().markPhysicalDestruction(destroyed)) {
            breakOperations.remove(key, operation);
            return;
        }
        queueBreakInvalidation(server, operation);
    }

    private void queueBreakInvalidation(MinecraftServer server, PendingBreak operation) {
        lockService.invalidatePlacement(repository, databaseExecutor, operation.target().clickedPlacement())
                .whenComplete((invalidated, failure) -> {
                    if (failure != null) {
                        LOGGER.error("Unable to invalidate the destroyed lock generation; break remains pending",
                                failure);
                        return;
                    }
                    if (!Boolean.TRUE.equals(invalidated)) {
                        LOGGER.error("Destroyed lock generation was no longer current; break remains pending");
                        return;
                    }
                    try {
                        server.execute(() -> commitBreakInvalidation(operation));
                    } catch (RuntimeException dispatchFailure) {
                        LOGGER.warn("Unable to dispatch destroyed lock invalidation; break remains pending",
                                dispatchFailure);
                    }
                });
    }

    private void commitBreakInvalidation(PendingBreak operation) {
        if (!operation.transaction().markInvalidation(true)) {
            return;
        }
        placements.remove(operation.target().clickedPlacement());
        breakOperations.remove(operation.key(), operation);
        markers.reconcileAfterRemoval(operation.target().level(), operation.target().placements());
    }

    private Optional<TargetContext> resolve(ServerLevel level, BlockPos position) {
        return resolve(level, position, false);
    }

    private Optional<TargetContext> resolveForMutation(ServerLevel level, BlockPos position) {
        return resolve(level, position, true);
    }

    private Optional<TargetContext> resolve(ServerLevel level, BlockPos position, boolean includePending) {
        BlockPos primaryPosition = position.immutable();
        BlockState state = level.getBlockState(primaryPosition);
        Optional<LockBlockType> type = LockBlockAdapter.classify(state);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        Optional<PlacementProvenance> primary = provenance(level, primaryPosition, includePending);
        BlockPos connected = LockBlockAdapter.connectedChestPosition(level, primaryPosition, state);
        if (connected.equals(primaryPosition)) {
            if (primary.isEmpty() || primary.get().blockType() != type.get()) {
                return Optional.empty();
            }
            return Optional.of(new TargetContext(level, primaryPosition, List.of(primary.get()), true));
        }
        BlockState otherState = level.getBlockState(connected);
        Optional<LockBlockType> otherType = LockBlockAdapter.classify(otherState);
        Optional<PlacementProvenance> other = provenance(level, connected, includePending);
        if (primary.isPresent() && other.isPresent() && otherType.isPresent()
                && lockService.canGroupDoubleChest(primary.get(), type.get(), other.get(), otherType.get())) {
            List<PlacementProvenance> members = canonicalMembers(List.of(primary.get(), other.get()));
            return Optional.of(new TargetContext(level, primaryPosition, members, true));
        }
        Optional<PlacementProvenance> knownLocked = primary.flatMap(value ->
                cachedProvenance(level, primaryPosition));
        if (knownLocked.isEmpty()) {
            knownLocked = other.flatMap(value -> cachedProvenance(level, connected));
        }
        if (knownLocked.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TargetContext(level, primaryPosition,
                canonicalMembers(java.util.stream.Stream.of(primary, other).flatMap(Optional::stream).toList()), false));
    }

    private Optional<PlacementProvenance> provenance(ServerLevel level, BlockPos position) {
        return placements.find(level, position);
    }

    private Optional<PlacementProvenance> provenance(ServerLevel level, BlockPos position,
                                                     boolean includePending) {
        return includePending ? placements.findForMutation(level, position) : provenance(level, position);
    }

    private Optional<PlacementProvenance> cachedProvenance(ServerLevel level, BlockPos position) {
        return LockWorldIdentity.tryTarget(level, position).flatMap(lockService.cache()::provenance);
    }

    private boolean isBreakPending(ServerLevel level, BlockPos position) {
        return LockWorldIdentity.tryTarget(level, position).map(this::isBreakPending).orElse(false);
    }

    private boolean isBreakPending(LockTarget target) {
        return breakOperations.values().stream()
                .anyMatch(operation -> operation.target().placements().stream()
                        .anyMatch(placement -> placement.target().equals(target)));
    }

    private boolean isCreationContextValid(ServerPlayer player, InteractionHand hand, TargetContext expected) {
        return player != null && player.isAlive() && LockItem.isMarked(player.getItemInHand(hand))
                && isCreationTargetValid(player, expected);
    }

    private boolean isCreationTargetValid(ServerPlayer player, TargetContext expected) {
        return player != null && player.isAlive() && expected.completeTopology()
                && lockService.cache().isReady() && player.level() == expected.level()
                && player.level() instanceof ServerLevel serverLevel
                && resolve(serverLevel, playerPosition(expected)).map(expected::sameMembers).orElse(false)
                && isWithinInteractionRange(player, expected)
                && lockService.lookupAccess(player.getUUID(), expected.placements(), LockAction.OPEN).decision()
                == LockAccessDecision.NOT_LOCKED;
    }

    private boolean isContextValid(ServerPlayer player, TargetContext expected) {
        return player.isAlive() && player.level() == expected.level()
                && player.level() instanceof ServerLevel serverLevel
                && isWithinInteractionRange(player, expected)
                && resolve(serverLevel, playerPosition(expected))
                .map(expected::sameMembers).orElse(false);
    }

    private boolean isMutationContextValid(ServerPlayer player, TargetContext expected) {
        return player.isAlive() && player.level() == expected.level()
                && player.level() instanceof ServerLevel serverLevel
                && isWithinInteractionRange(player, expected)
                && resolveForMutation(serverLevel, playerPosition(expected))
                .map(expected::sameMembers).orElse(false);
    }

    private BlockPos playerPosition(TargetContext target) {
        return target.clickedPosition();
    }

    private Set<LockerMember> members(UUID lockerId, TargetContext target) {
        return target.placements().stream().map(placement -> new LockerMember(lockerId, placement.target(),
                placement.generationId(), placement.placedBy(), placement.blockType())).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private void message(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text));
    }

    private void messageIfValid(MinecraftServer server, UUID playerUuid, TargetContext target, String text) {
        ServerPlayer player = activePlayer(server, playerUuid);
        if (player != null && isContextValid(player, target) && isWithinInteractionRange(player, target)) {
            message(player, text);
        }
    }

    private ServerPlayer activePlayer(MinecraftServer server, UUID playerUuid) {
        return server.getPlayerList().getPlayer(playerUuid);
    }

    private boolean hasMenu(ServerPlayer player, Object token) {
        return player.containerMenu instanceof LockAnvilMenu menu && menu.contextToken() == token;
    }

    private void dropReturnedItem(ServerLevel level, BlockPos position) {
        dropItem(level, position, LockItem.create());
    }

    private void dropItem(ServerLevel level, BlockPos position, ItemStack stack) {
        ItemEntity item = new ItemEntity(level, position.getX() + 0.5D, position.getY() + 0.5D,
                position.getZ() + 0.5D, stack);
        item.setDefaultPickUpDelay();
        level.addFreshEntity(item);
    }

    private MinecraftServer server(ServerPlayer player) {
        return ((ServerLevel) player.level()).getServer();
    }

    private static List<PlacementProvenance> canonicalMembers(List<PlacementProvenance> members) {
        return members.stream().sorted(java.util.Comparator.comparingInt((PlacementProvenance value) -> value.target().position().x())
                .thenComparing(value -> value.target().position().y())
                .thenComparing(value -> value.target().position().z())).toList();
    }

    private boolean isWithinInteractionRange(ServerPlayer player, TargetContext target) {
        return player.isWithinBlockInteractionRange(target.clickedPosition(), VANILLA_BLOCK_INTERACTION_MARGIN);
    }

    private record TargetContext(ServerLevel level, BlockPos clickedPosition,
                                 List<PlacementProvenance> placements, boolean completeTopology) {
        private TargetContext {
            placements = List.copyOf(new ArrayList<>(placements));
        }

        private PlacementProvenance primary() {
            return placements.getFirst();
        }

        private PlacementProvenance clickedPlacement() {
            return placements.stream().filter(value -> value.target().position().x() == clickedPosition.getX()
                    && value.target().position().y() == clickedPosition.getY()
                    && value.target().position().z() == clickedPosition.getZ()).findFirst().orElse(primary());
        }

        private boolean sameMembers(TargetContext other) {
            return placements.equals(other.placements) && completeTopology == other.completeTopology;
        }
    }

    private record BreakKey(UUID playerUuid, LockTarget target, UUID generationId) {
    }

    private record PendingBreak(BreakKey key, ServerPlayer player, TargetContext target,
                                BreakTransaction transaction) {
    }

    private record CreationReservation(UUID token, UUID playerUuid, ServerPlayer session,
                                       InteractionHand hand, LockAnvilMenu menu, ReservationKey key,
                                       TargetContext target, ServerLevel level,
                                       BlockPos position, ItemStack item, AtomicBoolean cancelled,
                                       AtomicBoolean itemFinalized, AtomicReference<UUID> lockerId) {
    }

    private record ReservationKey(UUID playerUuid, ServerPlayer session) {
    }
}
