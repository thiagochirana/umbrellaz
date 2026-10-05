package dev.chirana.umbrellaz.lock;

import dev.chirana.umbrellaz.auth.AuthEvents;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.audit.Actor;
import dev.chirana.umbrellaz.audit.ActorType;
import dev.chirana.umbrellaz.audit.AuditActions;
import dev.chirana.umbrellaz.audit.AuditDelivery;
import dev.chirana.umbrellaz.audit.AuditPayload;
import dev.chirana.umbrellaz.audit.AuditRecordContext;
import dev.chirana.umbrellaz.audit.AuditRecordRequest;
import dev.chirana.umbrellaz.audit.AuditService;
import dev.chirana.umbrellaz.audit.Outcome;
import dev.chirana.umbrellaz.audit.Source;
import dev.chirana.umbrellaz.audit.Target;
import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import dev.chirana.umbrellaz.protocol.ActionContextRegistry;
import dev.chirana.umbrellaz.protocol.LockPromptCancelPayload;
import dev.chirana.umbrellaz.protocol.LockPromptKind;
import dev.chirana.umbrellaz.protocol.LockPromptOpenPayload;
import dev.chirana.umbrellaz.protocol.LockPromptResultCode;
import dev.chirana.umbrellaz.protocol.LockPromptResultPayload;
import dev.chirana.umbrellaz.protocol.LockPromptSubmitPayload;
import dev.chirana.umbrellaz.protocol.ProtocolConstants;
import dev.chirana.umbrellaz.protocol.ProtocolSessionManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.Duration;
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
    private static final AtomicBoolean GLOBAL_CALLBACKS_INSTALLED = new AtomicBoolean();

    private final LockService lockService;
    private final LockRepository repository;
    private final DatabaseExecutor databaseExecutor;
    private final AuthorizationService authorizationService;
    private final ProtocolSessionManager protocol;
    private final AuditService auditService;
    private final LockPlacementTracker placements = new LockPlacementTracker();
    private final LockMarkerService markers;
    private final LockProtection protection;
    private final Set<BreakKey> pendingBreaks = ConcurrentHashMap.newKeySet();
    private final Map<BreakKey, PendingBreak> breakOperations = new ConcurrentHashMap<>();
    private final Map<UUID, CreationReservation> reservations = new ConcurrentHashMap<>();
    private final Map<ReservationKey, UUID> reservationKeys = new ConcurrentHashMap<>();
    private final Map<UUID, PendingCompensation> pendingCompensations = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final Map<ServerPlayer, LockPrompt> prompts = java.util.Collections.synchronizedMap(
            new java.util.IdentityHashMap<>());
    private final Map<UUID, LockPrompt> promptsByToken = new ConcurrentHashMap<>();
    private final Map<PromptSessionKey, LockPrompt> promptsBySession = new ConcurrentHashMap<>();
    private final Map<UUID, PromptPredecessor> promptPredecessors = new ConcurrentHashMap<>();
    private final Map<PromptSessionKey, PendingRetry> pendingRetriesBySession = new ConcurrentHashMap<>();
    private final Map<UUID, PendingRetry> pendingRetriesByToken = new ConcurrentHashMap<>();
    private static final Duration PROMPT_LIFETIME = Duration.ofMinutes(2);
    private static final String CREATE_ACTION = "lock.create";
    private static final String OPEN_ACTION = "lock.confirm.open";
    private static final String BREAK_ACTION = "lock.confirm.break";
    private static final String REMOVE_ACTION = "lock.confirm.remove";
    static final String LOCKED_CHEST_MERGE_MESSAGE = "Destranque o baú para permitir expandi-lo.";

    public LockEvents(LockService lockService, LockRepository repository, DatabaseExecutor databaseExecutor,
                       AuthorizationService authorizationService) {
        this(lockService, repository, databaseExecutor, authorizationService,
                new ProtocolSessionManager(Set.of(ProtocolConstants.FEATURE_LOCK_GUI)), null);
    }

    public LockEvents(LockService lockService, LockRepository repository, DatabaseExecutor databaseExecutor,
                       AuthorizationService authorizationService, AuditService auditService) {
        this(lockService, repository, databaseExecutor, authorizationService,
                new ProtocolSessionManager(Set.of(ProtocolConstants.FEATURE_LOCK_GUI)), auditService);
    }

    public LockEvents(LockService lockService, LockRepository repository, DatabaseExecutor databaseExecutor,
                       AuthorizationService authorizationService, ProtocolSessionManager protocol) {
        this(lockService, repository, databaseExecutor, authorizationService, protocol, null);
    }

    public LockEvents(LockService lockService, LockRepository repository, DatabaseExecutor databaseExecutor,
                       AuthorizationService authorizationService, ProtocolSessionManager protocol,
                       AuditService auditService) {
        this.lockService = Objects.requireNonNull(lockService, "lockService");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.auditService = auditService;
        this.protection = new LockProtection(lockService.cache(), placements);
        this.markers = new LockMarkerService(lockService.cache());
    }

    public static void installGlobalCallbacks() {
        if (!GLOBAL_CALLBACKS_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
            return dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry.findReady(serverLevel.getServer())
                    .map(runtime -> runtime.lockEvents().useBlock(player, level, hand, hit))
                    .orElseGet(() -> LockBlockAdapter.classify(serverLevel.getBlockState(hit.getBlockPos())).isPresent()
                            ? InteractionResult.FAIL : InteractionResult.PASS);
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) ->
                !(world instanceof ServerLevel serverLevel)
                        || dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry.findReady(serverLevel.getServer())
                        .map(runtime -> runtime.lockEvents().beforeBreak(world, player, pos, state, blockEntity))
                        .orElse(LockBlockAdapter.classify(state).isEmpty()));
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, wasGenerated) ->
                dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry.findReady(level.getServer())
                        .ifPresent(runtime -> runtime.lockEvents().markers.reconcileChunk(level, chunk.getPos())));
    }

    public static java.util.concurrent.CompletableFuture<Void> loadPlacementSnapshot() {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("A server runtime is required for lock initialization"));
    }

    public java.util.concurrent.CompletableFuture<Void> loadPlacementSnapshotForRuntime() {
        return lockService.loadPlacementSnapshot(repository, databaseExecutor, placements);
    }

    public void initialize(MinecraftServer server) {
        try {
            LockWorldIdentity.initialize(server);
            markers.reconcileAll(server);
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to initialize the lock world identity; lock interactions remain blocked", failure);
        }
    }

    public void disconnect(MinecraftServer server, ServerPlayer player) {
        for (LockPrompt prompt : promptSnapshot(player)) {
            removePrompt(prompt);
            clearPromptAssociations(prompt);
            protocol.actionContexts().cancel(prompt.context().token(), prompt.context().connectionId(),
                    prompt.context().playerUuid(), prompt.context().nonce(), prompt.context().generation());
            cancelPromptReservation(prompt, true);
            prompt.presentationDeactivate();
        }
        for (PendingRetry pendingRetry : List.copyOf(pendingRetriesBySession.values())) {
            if (pendingRetry.prompt().player() == player) cancelPendingRetry(pendingRetry);
        }
        cancelReservations(server, player);
        lockService.disconnect(player.getUUID());
    }

    public void reconcileMarkers(MinecraftServer server) {
        if (!stopping.get()) {
            markers.reconcileAll(server);
        }
    }

    public static void shutdown(MinecraftServer server) {
        dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry.find(server)
                .ifPresent(runtime -> runtime.lockEvents().shutdownReservations(server));
    }

    public static void afterSuccessfulPlacement(Level level, Player player, BlockPos position, BlockState state) {
        if (level instanceof ServerLevel serverLevel) {
            dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry.findReady(serverLevel.getServer())
                    .ifPresent(runtime -> runtime.lockEvents().recordPlacement(level, player, position, state));
        }
    }

    public static boolean beforePlacement(Level level, Player player, BlockPos position, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel)) return true;
        return dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry.findReady(serverLevel.getServer())
                .map(runtime -> runtime.lockEvents().allowPlacement(level, player, position, state))
                .orElse(LockBlockAdapter.classify(state).isEmpty());
    }

    public static void reconcileMarkers() {
        // Marker reconciliation is owned by the matching ServerRuntime.
    }

    public static LockProtection.Decision environmentalProtection(ServerLevel level, BlockPos position) {
        return dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry.findReady(level.getServer())
                .map(runtime -> runtime.lockEvents().protection.decision(level, position))
                .orElseGet(() -> LockProtection.unavailableDecision(level, position));
    }

    public static boolean blocksEnvironmentalMutation(ServerLevel level, BlockPos position) {
        LockProtection.Decision decision = environmentalProtection(level, position);
        return decision.blocksEnvironmentalMutation();
    }

    private boolean allowPlacement(Level level, Player player, BlockPos position, BlockState state) {
        Optional<LockBlockType> type = LockBlockAdapter.classify(state);
        if (isUnprotectedPlacement(type)) {
            return true;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)
                || !LockWorldIdentity.isReady(serverLevel) || !placements.isReady() || !lockService.cache().isReady()) {
            return false;
        }
        if (isBreakPending(serverLevel, position)) {
            return false;
        }
        if (cachedProvenance(serverLevel, position).isPresent()) {
            return false;
        }
        BlockPos connected = LockBlockAdapter.connectedChestPosition(serverLevel, position, state);
        boolean allowed = allowsCachedPlacement(position, connected,
                neighbor -> cachedProvenance(serverLevel, neighbor).isPresent());
        if (!allowed) {
            message(serverPlayer, LOCKED_CHEST_MERGE_MESSAGE);
        }
        return allowed;
    }

    private void recordPlacement(Level level, Player player, BlockPos position, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (!LockWorldIdentity.isReady(serverLevel)) {
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
        if (AuthEvents.isBlocked(serverPlayer)) {
            return InteractionResult.FAIL;
        }
        if (isBreakPending(serverLevel, hit.getBlockPos())) {
            return InteractionResult.FAIL;
        }
        Optional<TargetContext> context = resolve(serverLevel, hit.getBlockPos());
        if (context.isEmpty()) {
            if (!LockWorldIdentity.isReady(serverLevel)
                    && LockBlockAdapter.classify(serverLevel.getBlockState(hit.getBlockPos())).isPresent()) {
                message(serverPlayer, "A identidade do mundo do cadeado não está disponível. Tente novamente mais tarde.");
                return InteractionResult.FAIL;
            }
            if (!lockService.cache().isReady()
                    || !placements.isReady()) {
                if (LockBlockAdapter.classify(serverLevel.getBlockState(hit.getBlockPos())).isPresent()) {
                    message(serverPlayer, "Os dados dos cadeados ainda estão carregando. Tente novamente.");
                    return InteractionResult.FAIL;
                }
            }
            if (LockBlockAdapter.classify(serverLevel.getBlockState(hit.getBlockPos())).isPresent()
                    && LockItem.isMarked(serverPlayer.getItemInHand(hand))) {
                message(serverPlayer, "Este bloco não possui um registro de colocação válido para ser trancado.");
                return InteractionResult.FAIL;
            }
            if (cachedProvenance(serverLevel, hit.getBlockPos()).isPresent()) {
                message(serverPlayer, "O registro do alvo trancado não é mais válido.");
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
        if (!target.completeTopology()) {
            message(serverPlayer, "A estrutura do baú duplo ainda não foi identificada por completo. Tente novamente.");
            return InteractionResult.FAIL;
        }
        LockAccessResult access = lockService.lookupAccess(serverPlayer.getUUID(), target.placements(), action);
        if (access.decision() == LockAccessDecision.NOT_READY) {
            message(serverPlayer, "Os dados dos cadeados ainda estão carregando. Tente novamente.");
            return InteractionResult.FAIL;
        }
        if (access.decision() == LockAccessDecision.NOT_LOCKED) {
            if (removalGesture) {
                return InteractionResult.PASS;
            }
            if (LockItem.isMarked(held)) {
                return openPrompt(serverPlayer, LockPromptKind.CREATE, CREATE_ACTION, target, hand)
                        ? InteractionResult.SUCCESS : InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        }
        if (shouldPassLockedTargetToVanilla(held.getItem() instanceof BlockItem, access.decision())) {
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
        return openPrompt(serverPlayer, LockPromptKind.CONFIRM, actionName(action), target, null)
                ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    static boolean shouldPassLockedTargetToVanilla(boolean heldBlockItem, LockAccessDecision accessDecision) {
        return accessDecision == LockAccessDecision.LOCKED
                && heldBlockItem;
    }

    static boolean isUnprotectedPlacement(Optional<LockBlockType> type) {
        return type.isEmpty();
    }

    static boolean allowsCachedPlacement(BlockPos position, BlockPos connected,
                                         java.util.function.Predicate<BlockPos> hasCachedProvenance) {
        return connected.equals(position) || !hasCachedProvenance.test(connected);
    }

    private boolean beforeBreak(Level level, Player player, BlockPos position, BlockState state, BlockEntity blockEntity) {
        if (!(level instanceof ServerLevel world) || !(player instanceof ServerPlayer serverPlayer)) {
            return true;
        }
        if (LockBlockAdapter.classify(state).isEmpty()) {
            return true;
        }
        if (AuthEvents.isBlocked(serverPlayer)) {
            return false;
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
            if (!LockWorldIdentity.isReady(world)
                    && LockBlockAdapter.classify(world.getBlockState(position)).isPresent()) {
                message(serverPlayer, "A identidade do mundo do cadeado não está disponível. Tente novamente mais tarde.");
                return false;
            }
            if ((!lockService.cache().isReady() || !placements.isReady())
                    && LockBlockAdapter.classify(world.getBlockState(position)).isPresent()) {
                message(serverPlayer, "Os dados dos cadeados ainda estão carregando. Tente novamente.");
                return false;
            }
            if (cachedProvenance(world, position).isPresent()) {
                message(serverPlayer, "O registro do alvo trancado não é mais válido.");
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
            message(serverPlayer, "Os dados dos cadeados ainda estão carregando. Tente novamente.");
            return false;
        }
        if (access.decision() == LockAccessDecision.NOT_LOCKED) {
            beginAuthorizedBreak(serverPlayer, target, false);
            return false;
        }
        if (!target.completeTopology()) {
            message(serverPlayer, "A estrutura do baú duplo ainda não foi identificada por completo. Tente novamente.");
            return false;
        }
        if (authorizationService.isAdministrator(serverPlayer.getUUID())
                || access.decision() == LockAccessDecision.GRANTED) {
            beginAuthorizedBreak(serverPlayer, target, true);
        } else {
            if (!openPrompt(serverPlayer, LockPromptKind.CONFIRM, BREAK_ACTION, target, null)) {
                return false;
            }
        }
        return false;
    }

    private boolean openPrompt(ServerPlayer player, LockPromptKind kind, String action,
                               TargetContext target, InteractionHand hand) {
        if (stopping.get() || AuthEvents.isBlocked(player) || !protocol.isCompatible(player)
                || !ServerPlayNetworking.canSend(player, LockPromptOpenPayload.TYPE)) {
            return false;
        }
        UUID bindingId = UUID.randomUUID();
        Optional<ActionContextRegistry.Context> opened = protocol.openContext(player, action,
                bindingId.toString(), PROMPT_LIFETIME);
        if (opened.isEmpty()) return false;
        CreationPresentationGuard presentation = kind == LockPromptKind.CREATE
                ? new CreationPresentationGuard() : null;
        if (presentation != null) presentation.useCustomPrompt();
        LockPrompt prompt = new LockPrompt(player, player.getUUID(), kind, action, bindingId,
                opened.get(), target, hand, presentation);
        replacePrompt(player, prompt);
        ServerPlayNetworking.send(player, new LockPromptOpenPayload(prompt.token(), kind));
        return true;
    }

    /** Entry point for the global typed lock-submit receiver. Must run on the server thread. */
    public void handlePromptSubmit(ServerPlayer player, LockPromptSubmitPayload payload) {
        if (player == null || payload == null || stopping.get()) return;
        Optional<ActionContextRegistry.Context> claimed = protocol.claimContext(player, payload.contextToken());
        LockPrompt prompt = promptsByToken.get(payload.contextToken());
        if (claimed.isEmpty()) {
            if (prompt != null && prompt.player() == player) invalidatePrompt(prompt, true);
            return;
        }
        if (prompt == null || prompt.player() != player || prompt.context() != claimed.get()
                || !prompt.action().equals(claimed.get().action())
                || !prompt.bindingId().toString().equals(claimed.get().target())) {
            if (prompt != null && prompt.player() == player) invalidatePrompt(prompt, true);
            else protocol.actionContexts().complete(claimed.get());
            return;
        }
        if (!isPromptTargetValid(player, prompt)) {
            invalidatePrompt(prompt, true);
            return;
        }
        if (prompt.kind() == LockPromptKind.CREATE) submitPromptCreation(player, prompt, payload.password());
        else submitPromptPassword(player, prompt, payload.password());
    }

    /** Entry point for the global typed lock-cancel receiver. Must run on the server thread. */
    public void handlePromptCancel(ServerPlayer player, LockPromptCancelPayload payload) {
        if (player == null || payload == null) return;
        LockPrompt prompt = promptsByToken.get(payload.contextToken());
        if (prompt == null) {
            PendingRetry pendingRetry = pendingRetriesByToken.get(payload.contextToken());
            if (pendingRetry != null) {
                if (!protocol.isCompatible(player) || !pendingRetry.matches(player)) return;
                cancelPendingRetry(pendingRetry);
                return;
            }
            PromptPredecessor predecessor = promptPredecessors.get(payload.contextToken());
            if (predecessor == null || !predecessor.matches(player)) return;
            prompt = promptsBySession.get(predecessor.sessionKey());
            if (prompt == null || prompt.player() != player
                    || !predecessor.matches(prompt.context())) return;
        }
        if (prompt.player() != player) return;
        ActionContextRegistry.Context context = prompt.context();
        boolean cancelled = protocol.isCompatible(player)
                && protocol.actionContexts().cancel(context.token(), context.connectionId(),
                context.playerUuid(), context.nonce(), context.generation());
        if (cancelled || context.state() == ActionContextRegistry.ContextState.CANCELLED
                || context.state() == ActionContextRegistry.ContextState.EXPIRED
                || context.state() == ActionContextRegistry.ContextState.INVALIDATED) {
            removePrompt(prompt);
            clearPromptAssociations(prompt);
            cancelPromptReservation(prompt, true);
            prompt.presentationDeactivate();
        }
    }

    private void submitPromptCreation(ServerPlayer player, LockPrompt prompt, String password) {
        if (!new PasswordPolicy().isValid(password)) {
            auditCreate(player, prompt.target(), player.getItemInHand(prompt.hand()),
                    Outcome.DENIED, "invalid_password");
            retryPrompt(prompt, LockPromptResultCode.ERROR, 0,
                    "A senha deve ter 1 letra ASCII seguida de 3 dígitos ASCII.");
            return;
        }
        MinecraftServer server = server(player);
        CreationReservation reservation = reserveCreation(server, player, prompt.hand(),
                prompt.target(), prompt.presentation());
        if (reservation == null) {
            auditCreate(player, prompt.target(), player.getItemInHand(prompt.hand()),
                    Outcome.FAILURE, "reservation_unavailable");
            invalidatePrompt(prompt, true);
            return;
        }
        prompt.reservation(reservation);
        lockService.createPasswordAsync(password).whenComplete((hash, failure) -> dispatch(server, () -> {
            if (!isPromptInFlight(prompt)) {
                auditCreateOnce(reservation, Outcome.FAILURE, "prompt_invalidated");
                restoreReservation(server, reservation);
                return;
            }
            if (failure != null) {
                auditCreateOnce(reservation, Outcome.FAILURE, "password_creation_failed");
                restoreReservation(server, reservation);
                retryPrompt(prompt, LockPromptResultCode.ERROR, 0,
                        "Não foi possível criar a senha do cadeado. Tente novamente.");
                return;
            }
            ServerPlayer current = activePlayer(server, prompt.playerUuid());
            if (!reservationValid(current, reservation)) {
                auditCreateOnce(reservation, Outcome.FAILURE, "context_invalidated");
                restoreReservation(server, reservation);
                invalidatePrompt(prompt, true);
                return;
            }
            UUID lockerId = UUID.randomUUID();
            LockerMetadata locker = new LockerMetadata(lockerId, prompt.playerUuid(), hash,
                    Instant.now(), Instant.now(), 1, members(lockerId, prompt.target()));
            createLocker(server, reservation, locker, prompt);
        }));
    }

    private void submitPromptPassword(ServerPlayer player, LockPrompt prompt, String password) {
        LockAction action = actionFor(prompt.action());
        if (action == null || !prompt.target().completeTopology()) {
            auditPasswordVerification(player, prompt, Outcome.FAILURE, "invalid_context");
            invalidatePrompt(prompt, true);
            return;
        }
        MinecraftServer server = server(player);
        lockService.verifyPasswordAsync(player.getUUID(), prompt.target().placements(), action, password)
                .whenComplete((verification, failure) -> dispatch(server, () -> {
                    if (!isPromptInFlight(prompt)) {
                        auditPasswordVerification(player, prompt, Outcome.FAILURE, "prompt_invalidated");
                        if (verification != null) lockService.discardPasswordProof(verification.proof());
                        return;
                    }
                    if (failure != null || verification == null) {
                        auditPasswordVerification(player, prompt, Outcome.FAILURE, "verification_failed");
                        retryPrompt(prompt, LockPromptResultCode.ERROR, 0,
                                "Não foi possível verificar a senha. Tente novamente.");
                        return;
                    }
                    if (!isPromptTargetValid(activePlayer(server, prompt.playerUuid()), prompt)
                            || !prompt.target().completeTopology()) {
                        auditPasswordVerification(player, prompt, Outcome.FAILURE, "target_invalidated");
                        lockService.discardPasswordProof(verification.proof());
                        invalidatePrompt(prompt, true);
                        return;
                    }
                    LockAccessResult result = verification.result();
                    if (result.decision() == LockAccessDecision.GRANTED && verification.hasProof()) {
                        if (!isPromptTargetValid(activePlayer(server, prompt.playerUuid()), prompt)
                                || !protocol.actionContexts().complete(prompt.context(), prompt.context().connectionId(),
                                prompt.context().playerUuid(), prompt.context().nonce(), prompt.context().generation())) {
                            lockService.discardPasswordProof(verification.proof());
                            invalidatePrompt(prompt, true);
                            return;
                        }
                        removePrompt(prompt);
                        clearPromptAssociations(prompt);
                        prompt.presentationDeactivate();
                        LockAccessResult committed = lockService.commitPasswordProof(verification.proof());
                        if (committed.decision() != LockAccessDecision.GRANTED) {
                            auditPasswordVerification(player, prompt, Outcome.FAILURE, "target_invalidated");
                            sendResult(player, prompt, LockPromptResultCode.INVALIDATED, 0,
                                    "O alvo do cadeado não é mais válido.", null);
                            return;
                        }
                        auditPasswordVerification(player, prompt, Outcome.SUCCESS, "verified");
                        sendResult(player, prompt, LockPromptResultCode.SUCCESS, 0, "", null);
                         if (action == LockAction.OPEN) openTarget(player, prompt.target());
                        else if (action == LockAction.BREAK) beginAuthorizedBreak(player, prompt.target(), true);
                        else removeLocker(player, prompt.target(), committed.lockerId());
                        return;
                    }
                    lockService.discardPasswordProof(verification.proof());
                    if (result.decision() == LockAccessDecision.COOLDOWN) {
                        auditPasswordVerification(player, prompt, Outcome.DENIED, "cooldown");
                        retryPrompt(prompt, LockPromptResultCode.COOLDOWN, cooldownSeconds(result),
                                "Muitas tentativas. Aguarde antes de tentar novamente.");
                    } else {
                        auditPasswordVerification(player, prompt,
                                result.decision() == LockAccessDecision.VERIFICATION_BUSY
                                        ? Outcome.FAILURE : Outcome.DENIED,
                                passwordResultCode(result.decision()));
                        retryPrompt(prompt, LockPromptResultCode.ERROR, 0, switch (result.decision()) {
                            case VERIFICATION_BUSY -> "A verificação da senha está ocupada. Tente novamente.";
                            default -> "Senha incorreta.";
                        });
                    }
                }));
    }

    private void dispatch(MinecraftServer server, Runnable operation) {
        try { server.execute(operation); }
        catch (RuntimeException failure) { LOGGER.warn("Unable to dispatch lock prompt completion", failure); }
    }

    private boolean isPromptInFlight(LockPrompt prompt) {
        return !stopping.get() && promptsByToken.get(prompt.token()) == prompt
                && prompt.context().state() == ActionContextRegistry.ContextState.IN_FLIGHT;
    }

    private boolean isPromptTargetValid(ServerPlayer player, LockPrompt prompt) {
        if (player == null || player != prompt.player() || !player.getUUID().equals(prompt.playerUuid())
                || AuthEvents.isBlocked(player)) return false;
        if (prompt.kind() == LockPromptKind.CREATE) {
            return prompt.hand() != null && isCreationContextValid(player, prompt.hand(), prompt.target());
        }
        LockAction action = actionFor(prompt.action());
        return action != null && isContextValid(player, prompt.target())
                && prompt.target().completeTopology();
    }

    private void retryPrompt(LockPrompt prompt, LockPromptResultCode code, int cooldownSeconds, String message) {
        if (!promptsByToken.remove(prompt.token(), prompt)) return;
        PromptSessionKey sessionKey = prompt.sessionKey();
        protocol.actionContexts().complete(prompt.context());
        CreationReservation reservation = prompt.reservation();
        if (reservation != null && reservation.lockerId().get() != null
                && !reservation.itemFinalized().get()) {
            PendingRetry pendingRetry = new PendingRetry(prompt, sessionKey, reservation, code,
                    cooldownSeconds, message);
            pendingRetriesBySession.put(sessionKey, pendingRetry);
            pendingRetriesByToken.put(prompt.token(), pendingRetry);
            cancelReservation(server(prompt.player()), reservation, false,
                    () -> finishPendingRetry(pendingRetry), pendingRetry);
            return;
        }
        cancelPromptReservation(prompt, false);
        openRetryPrompt(prompt, sessionKey, code, cooldownSeconds, message);
    }

    private void openRetryPrompt(LockPrompt prompt, PromptSessionKey sessionKey,
                                 LockPromptResultCode code, int cooldownSeconds, String message) {
        prompt.presentationDeactivate();
        Optional<ActionContextRegistry.Context> fresh = protocol.openContext(prompt.player(), prompt.action(),
                prompt.bindingId().toString(), PROMPT_LIFETIME);
        if (fresh.isEmpty() || !protocol.isCompatible(prompt.player())) {
            clearPromptAssociations(prompt);
            sendResult(prompt.player(), prompt, LockPromptResultCode.INVALIDATED, 0,
                    "Esta solicitação do cadeado não é mais válida.", null);
            return;
        }
        CreationPresentationGuard presentation = prompt.kind() == LockPromptKind.CREATE
                ? new CreationPresentationGuard() : null;
        if (presentation != null) presentation.useCustomPrompt();
        LockPrompt replacement = new LockPrompt(prompt.player(), prompt.playerUuid(), prompt.kind(), prompt.action(),
                prompt.bindingId(), fresh.get(), prompt.target(), prompt.hand(), presentation);
        replacePrompt(prompt.player(), replacement);
        promptPredecessors.put(prompt.token(), new PromptPredecessor(prompt.context(), sessionKey));
        sendResult(prompt.player(), prompt, code, cooldownSeconds, message, replacement.token());
    }

    private void finishPendingRetry(PendingRetry pendingRetry) {
        pendingRetriesByToken.remove(pendingRetry.prompt().token(), pendingRetry);
        pendingRetriesBySession.remove(pendingRetry.sessionKey(), pendingRetry);
        if (pendingRetry.cancelled()) {
            pendingRetry.prompt().presentationDeactivate();
            return;
        }
        openRetryPrompt(pendingRetry.prompt(), pendingRetry.sessionKey(), pendingRetry.code(),
                pendingRetry.cooldownSeconds(), pendingRetry.message());
    }

    private void cancelPendingRetry(PendingRetry pendingRetry) {
        boolean newlyCancelled = pendingRetry.cancel();
        pendingRetriesByToken.remove(pendingRetry.prompt().token(), pendingRetry);
        pendingRetriesBySession.remove(pendingRetry.sessionKey(), pendingRetry);
        if (!newlyCancelled) return;
        pendingRetry.prompt().presentationDeactivate();
        cancelReservation(server(pendingRetry.prompt().player()), pendingRetry.reservation(), true,
                null, pendingRetry);
    }

    private void invalidatePrompt(LockPrompt prompt, boolean send) {
        removePrompt(prompt);
        clearPromptAssociations(prompt);
        protocol.actionContexts().complete(prompt.context());
        cancelPromptReservation(prompt, true);
        prompt.presentationDeactivate();
        if (send) sendResult(prompt.player(), prompt, LockPromptResultCode.INVALIDATED, 0,
                "Esta solicitação do cadeado não é mais válida.", null);
    }

    private void sendResult(ServerPlayer player, LockPrompt prompt, LockPromptResultCode code,
                            int cooldownSeconds, String message, UUID retryToken) {
        if (player == null || player.hasDisconnected() || !ServerPlayNetworking.canSend(player, LockPromptResultPayload.TYPE)) return;
        ServerPlayNetworking.send(player, new LockPromptResultPayload(prompt.token(), code,
                cooldownSeconds, message, retryToken));
    }

    private void replacePrompt(ServerPlayer player, LockPrompt prompt) {
        LockPrompt previous;
        synchronized (prompts) { previous = prompts.put(player, prompt); }
        if (previous != null) {
            promptsByToken.remove(previous.token(), previous);
            promptsBySession.remove(previous.sessionKey(), previous);
            clearPromptAssociations(previous);
            protocol.actionContexts().cancel(previous.context().token(), previous.context().connectionId(),
                    previous.context().playerUuid(), previous.context().nonce(), previous.context().generation());
            cancelPromptReservation(previous);
            previous.presentationDeactivate();
        }
        promptsByToken.put(prompt.token(), prompt);
        promptsBySession.put(prompt.sessionKey(), prompt);
    }

    private void removePrompt(LockPrompt prompt) {
        promptsByToken.remove(prompt.token(), prompt);
        synchronized (prompts) { prompts.remove(prompt.player(), prompt); }
        promptsBySession.remove(prompt.sessionKey(), prompt);
    }

    private void clearPromptAssociations(LockPrompt prompt) {
        promptsBySession.remove(prompt.sessionKey(), prompt);
        promptPredecessors.entrySet().removeIf(entry -> entry.getValue().sessionKey().equals(prompt.sessionKey()));
        PendingRetry pendingRetry = pendingRetriesBySession.remove(prompt.sessionKey());
        if (pendingRetry != null) {
            pendingRetriesByToken.remove(pendingRetry.prompt().token(), pendingRetry);
            pendingRetry.cancel();
        }
    }

    private List<LockPrompt> promptSnapshot() {
        synchronized (prompts) { return List.copyOf(prompts.values()); }
    }

    private List<LockPrompt> promptSnapshot(ServerPlayer player) {
        synchronized (prompts) {
            LockPrompt prompt = prompts.get(player);
            return prompt == null ? List.of() : List.of(prompt);
        }
    }

    private void cancelPromptReservation(LockPrompt prompt) {
        cancelPromptReservation(prompt, false);
    }

    private void cancelPromptReservation(LockPrompt prompt, boolean dropOnly) {
        CreationReservation reservation = prompt.reservation();
        if (reservation != null) cancelReservation(server(prompt.player()), reservation, dropOnly);
    }

    private static int cooldownSeconds(LockAccessResult result) {
        long seconds = Math.max(1L, (result.cooldownRemainingNanos() + 999_999_999L) / 1_000_000_000L);
        return (int) Math.min(ProtocolConstants.MAX_LOCK_PROMPT_COOLDOWN_SECONDS, seconds);
    }

    private static String actionName(LockAction action) {
        return switch (action) {
            case OPEN -> OPEN_ACTION;
            case BREAK -> BREAK_ACTION;
            case REMOVE -> REMOVE_ACTION;
        };
    }

    private static LockAction actionFor(String action) {
        return switch (action) {
            case OPEN_ACTION -> LockAction.OPEN;
            case BREAK_ACTION -> LockAction.BREAK;
            case REMOVE_ACTION -> LockAction.REMOVE;
            default -> null;
        };
    }

    private void createLocker(MinecraftServer server, CreationReservation reservation,
                              LockerMetadata locker, LockPrompt prompt) {
        reservation.lockerId().set(locker.lockerId());
        lockService.createLockerAsync(repository, databaseExecutor, locker)
                .whenComplete((ignored, failure) -> {
                    if (stopping.get() || reservation.cancelled().get()) {
                        try {
                            server.execute(() -> {
                                auditCreateOnce(reservation, Outcome.FAILURE, "cancelled");
                                compensateLocker(server, reservation, locker.lockerId(), null);
                            });
                        } catch (RuntimeException dispatchFailure) {
                            LOGGER.warn("Unable to dispatch cancelled lock creation completion", dispatchFailure);
                        }
                        return;
                    }
                    try {
                        server.execute(() -> {
                            if (stopping.get() || reservation.cancelled().get()) {
                                auditCreateOnce(reservation, Outcome.FAILURE, "cancelled");
                                compensateLocker(server, reservation, locker.lockerId(), null);
                                return;
                            }
                    ServerPlayer current = activePlayer(server, reservation.playerUuid());
                    boolean persistenceSucceeded = failure == null;
                    boolean completionValid = persistenceSucceeded
                            && createdReservationValid(current, reservation, locker)
                            && isPromptInFlight(prompt);
                    if (CreationCompletionDecision.decide(persistenceSucceeded, completionValid)
                            == CreationCompletionDecision.Outcome.COMPENSATE) {
                        auditCreateOnce(reservation, Outcome.FAILURE,
                                failure == null ? "completion_invalid" : "persistence_failed");
                        compensateLocker(server, reservation, locker.lockerId(),
                                failure == null
                                        ? null
                                        : "Não foi possível salvar o cadeado; o item não foi consumido.", prompt);
                        return;
                    }
                    reservations.remove(reservation.token(), reservation);
                    reservationKeys.remove(reservation.key(), reservation.token());
                    reservation.itemFinalized().set(true);
                    reservation.presentation().deactivate();
                    removePrompt(prompt);
                    clearPromptAssociations(prompt);
                    protocol.actionContexts().complete(prompt.context());
                    auditCreateOnce(reservation, Outcome.SUCCESS, "created");
                    sendResult(current, prompt, LockPromptResultCode.SUCCESS, 0, "", null);
                    markers.reconcileTargets(reservation.level(), reservation.target().placements());
                        });
                    } catch (RuntimeException dispatchFailure) {
                        LOGGER.warn("Unable to dispatch lock persistence completion for {}",
                                reservation.playerUuid(), dispatchFailure);
                    }
                });
    }

    private CreationReservation reserveCreation(MinecraftServer server, ServerPlayer player,
                                                 InteractionHand hand, TargetContext target,
                                                 CreationPresentationGuard presentation) {
        if (stopping.get() || player == null) {
            return null;
        }
        ItemStack held = player.getItemInHand(hand);
        if (!LockItem.isMarked(held) || held.getCount() < 1
                || !isCreationPresentationValid(player, presentation)
                || !isCreationContextValid(player, hand, target)) {
            return null;
        }
        UUID token = UUID.randomUUID();
        ReservationKey key = new ReservationKey(player.getUUID(), player);
        if (reservationKeys.putIfAbsent(key, token) != null) {
            return null;
        }
        CreationReservation reservation = new CreationReservation(token, player.getUUID(), player, hand,
                presentation, key, target, player.level(), target.clickedPosition(), held.copyWithCount(1),
                new AtomicBoolean(false), new AtomicBoolean(false), new AtomicBoolean(false), new AtomicReference<>(),
                new AtomicBoolean());
        reservations.put(token, reservation);
        held.shrink(1);
        return reservation;
    }

    private boolean reservationValid(ServerPlayer player, CreationReservation reservation) {
        return reservationIdentityValid(player, reservation)
                && isCreationTargetValid(player, reservation.target());
    }

    private boolean createdReservationValid(ServerPlayer player, CreationReservation reservation,
                                            LockerMetadata locker) {
        return reservationIdentityValid(player, reservation)
                && isCreationTargetContextValid(player, reservation.target())
                && reservation.target().placements().stream()
                .allMatch(placement -> lockService.cache().resolve(placement)
                        .map(locker::equals).orElse(false));
    }

    private boolean reservationIdentityValid(ServerPlayer player, CreationReservation reservation) {
        return !reservation.cancelled().get() && player != null && player == reservation.session() && player.isAlive()
                && !AuthEvents.isBlocked(player)
                && player.level() == reservation.level()
                && isCreationPresentationValid(player, reservation.presentation())
                && reservations.get(reservation.token()) == reservation
                && reservation.item().getCount() == 1
                && LockItem.isMarked(reservation.item());
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
        boolean presentationValid = reservation.presentation().isActive()
                && current != null
                && current == reservation.session()
                && isCreationPresentationValid(current, reservation.presentation());
        if (!dropOnly && presentationValid && isContextValid(current, reservation.target())) {
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

    public void shutdownReservations(MinecraftServer server) {
        stopping.set(true);
        for (PendingRetry pendingRetry : List.copyOf(pendingRetriesBySession.values())) {
            cancelPendingRetry(pendingRetry);
        }
        for (LockPrompt prompt : promptSnapshot()) {
            removePrompt(prompt);
            clearPromptAssociations(prompt);
            protocol.actionContexts().cancel(prompt.context().token(), prompt.context().connectionId(),
                    prompt.context().playerUuid(), prompt.context().nonce(), prompt.context().generation());
            cancelPromptReservation(prompt, true);
            prompt.presentationDeactivate();
        }
        for (CreationReservation reservation : List.copyOf(reservations.values())) {
            cancelReservation(server, reservation, true);
        }
    }

    private void cancelReservation(MinecraftServer server, CreationReservation reservation, boolean dropOnly) {
        cancelReservation(server, reservation, dropOnly, null);
    }

    private void cancelReservation(MinecraftServer server, CreationReservation reservation, boolean dropOnly,
                                   Runnable onCompensated) {
        cancelReservation(server, reservation, dropOnly, onCompensated, null);
    }

    private void cancelReservation(MinecraftServer server, CreationReservation reservation, boolean dropOnly,
                                   Runnable onCompensated, PendingRetry pendingRetry) {
        if (reservation.itemFinalized().get()) return;
        reservation.cancelled().set(true);
        UUID lockerId = reservation.lockerId().get();
        if (lockerId != null) {
            compensateLocker(server, reservation, lockerId, null, null, dropOnly, onCompensated, pendingRetry);
        } else {
            restoreReservation(server, reservation, dropOnly);
            if (onCompensated != null) onCompensated.run();
        }
    }

    private void compensateLocker(MinecraftServer server, CreationReservation reservation, UUID lockerId,
                                   String message) {
        compensateLocker(server, reservation, lockerId, message, null, false, null, null);
    }

    private void compensateLocker(MinecraftServer server, CreationReservation reservation, UUID lockerId,
                                   String message, LockPrompt prompt) {
        compensateLocker(server, reservation, lockerId, message, prompt, false, null, null);
    }

    private void compensateLocker(MinecraftServer server, CreationReservation reservation, UUID lockerId,
                                   String message, LockPrompt prompt, boolean dropOnly,
                                   Runnable onCompensated, PendingRetry pendingRetry) {
        if (reservation.itemFinalized().get()) return;
        reservation.cancelled().set(true);
        PendingCompensation pending = new PendingCompensation(server, reservation, lockerId, message, prompt,
                dropOnly, onCompensated, pendingRetry);
        PendingCompensation existing = pendingCompensations.putIfAbsent(reservation.token(), pending);
        attemptCompensation(existing == null ? pending : existing);
    }

    private void attemptCompensation(PendingCompensation pending) {
        CreationReservation reservation = pending.reservation();
        if (!reservation.compensationStarted().compareAndSet(false, true)) return;
        lockService.removeLockerAsync(repository, databaseExecutor, pending.lockerId(),
                        pending.reservation().target().placements())
                .whenComplete((removed, failure) -> {
                    try {
                        pending.server().execute(() -> finishCompensation(pending, removed, failure));
                    } catch (RuntimeException dispatchFailure) {
                        reservation.compensationStarted().set(false);
                        LOGGER.warn("Unable to dispatch lock compensation completion for {}",
                                pending.lockerId(), dispatchFailure);
                    }
                });
    }

    private void finishCompensation(PendingCompensation pending, Boolean removed, Throwable failure) {
        CreationReservation reservation = pending.reservation();
        boolean confirmedAbsent = !Boolean.TRUE.equals(removed)
                && failure == null && compensationCacheConfirmsAbsent(pending);
        if (failure != null || (!Boolean.TRUE.equals(removed) && !confirmedAbsent)) {
            reservation.compensationStarted().set(false);
            if (failure != null) {
                LOGGER.error("Unable to compensate stale lock creation for {}", pending.lockerId(), failure);
            }
            return;
        }
        if (!pendingCompensations.remove(reservation.token(), pending)) return;
        reservation.compensationStarted().set(false);
        reservation.lockerId().compareAndSet(pending.lockerId(), null);
        boolean dropOnly = pending.dropOnly()
                || (pending.retry() != null && pending.retry().cancelled());
        restoreReservation(pending.server(), reservation, dropOnly);
        if (pending.onCompensated() != null) pending.onCompensated().run();
        LockPrompt prompt = pending.prompt();
        if (prompt != null && promptsByToken.get(prompt.token()) == prompt) {
            if (pending.message() == null) invalidatePrompt(prompt, true);
            else retryPrompt(prompt, LockPromptResultCode.ERROR, 0, pending.message());
            return;
        }
        if (pending.message() != null) {
            messageIfValid(pending.server(), reservation.playerUuid(), reservation.target(),
                    pending.message());
        }
    }

    private boolean compensationCacheConfirmsAbsent(PendingCompensation pending) {
        if (!lockService.cache().isReady()) return false;
        return lockService.cache().lockers().stream()
                .noneMatch(locker -> pending.lockerId().equals(locker.lockerId()));
    }

    public void tick(MinecraftServer server) {
        if (server == null) return;
        for (PendingCompensation pending : List.copyOf(pendingCompensations.values())) attemptCompensation(pending);
    }

    private void openTarget(ServerPlayer player, TargetContext target) {
        if (!isContextValid(player, target)) {
            message(player, "O alvo mudou antes de ser aberto.");
            return;
        }
        BlockPos clicked = target.clickedPosition();
        BlockHitResult hit = new BlockHitResult(player.position(), net.minecraft.core.Direction.UP,
                clicked, false);
        player.level().getBlockState(clicked).useWithoutItem(player.level(), player, hit);
    }

    private void removeLocker(ServerPlayer player, TargetContext target, UUID lockerId) {
        if (!target.completeTopology() || !isContextValid(player, target)) {
            auditLockRemoval(player, target, Outcome.FAILURE, "context_invalid");
            message(player, "O alvo mudou antes da remoção.");
            return;
        }
        MinecraftServer server = server(player);
        UUID playerUuid = player.getUUID();
        ServerLevel originalLevel = player.level();
        lockService.removeLockerAsync(repository, databaseExecutor, lockerId, target.placements())
                .whenComplete((removed, failure) -> server.execute(() -> {
                    ServerPlayer current = activePlayer(server, playerUuid);
                    if (failure != null) {
                        auditLockRemoval(player, target, Outcome.FAILURE, "persistence_failed");
                        messageIfValid(server, playerUuid, target,
                                "Não foi possível remover o cadeado; nada foi alterado.");
                        return;
                    }
                    if (!Boolean.TRUE.equals(removed)) {
                        auditLockRemoval(player, target, Outcome.FAILURE, "target_changed");
                        messageIfValid(server, playerUuid, target, "O alvo mudou antes da remoção.");
                        return;
                    }
                    markers.reconcileAfterRemoval(originalLevel, target.placements());
                    if (current == null) {
                        auditLockRemoval(player, target, Outcome.SUCCESS, "removed");
                        dropReturnedItem(originalLevel, target.clickedPosition());
                        return;
                    }
                    if (current != player || !isContextValid(current, target)
                            || !isWithinInteractionRange(current, target)) {
                        auditLockRemoval(player, target, Outcome.SUCCESS, "removed");
                        dropReturnedItem(originalLevel, target.clickedPosition());
                        return;
                    }
                    ItemStack returned = LockItem.create();
                    if (!current.getInventory().add(returned)) {
                        current.drop(returned, false, net.minecraft.util.Prediction.SERVER_ONLY);
                    }
                    auditLockRemoval(player, target, Outcome.SUCCESS, "removed");
                    message(current, "Cadeado removido.");
                }));
    }

    private void beginAuthorizedBreak(ServerPlayer player, TargetContext target, boolean requireCompleteTopology) {
        MinecraftServer server = server(player);
        if ((requireCompleteTopology && !target.completeTopology())
                || activePlayer(server, player.getUUID()) != player
                || !isMutationContextValid(player, target)) {
            auditLockBreak(player, target, Outcome.FAILURE, "context_invalid");
            message(player, "O alvo mudou antes da quebra.");
            return;
        }
        BreakKey key = new BreakKey(player.getUUID(), target.clickedPlacement().target(),
                target.clickedPlacement().generationId());
        PendingBreak operation = new PendingBreak(key, player, target, new BreakTransaction(), new AtomicBoolean());
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
            auditBreakOnce(operation, Outcome.FAILURE, "physical_destruction_failed");
            return;
        }
        queueBreakInvalidation(server, operation);
    }

    private void queueBreakInvalidation(MinecraftServer server, PendingBreak operation) {
        lockService.invalidatePlacement(repository, databaseExecutor, operation.target().clickedPlacement())
                .whenComplete((invalidated, failure) -> {
                    if (failure != null) {
                        dispatch(server, () -> auditBreakOnce(operation, Outcome.FAILURE, "persistence_failed"));
                        LOGGER.error("Unable to invalidate the destroyed lock generation; break remains pending",
                                failure);
                        return;
                    }
                    if (!Boolean.TRUE.equals(invalidated)) {
                        dispatch(server, () -> auditBreakOnce(operation, Outcome.FAILURE, "generation_changed"));
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
        auditBreakOnce(operation, Outcome.SUCCESS, "broken");
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
        return isCreationTargetContextValid(player, expected)
                && lockService.lookupAccess(player.getUUID(), expected.placements(), LockAction.OPEN).decision()
                == LockAccessDecision.NOT_LOCKED;
    }

    private boolean isCreationTargetContextValid(ServerPlayer player, TargetContext expected) {
        return player != null && player.isAlive() && expected.completeTopology()
                && lockService.cache().isReady() && player.level() == expected.level()
                && player.level() instanceof ServerLevel serverLevel
                && resolve(serverLevel, playerPosition(expected)).map(expected::sameMembers).orElse(false)
                && isWithinInteractionRange(player, expected);
    }

    private boolean isContextValid(ServerPlayer player, TargetContext expected) {
        return player != null && player.isAlive() && expected.completeTopology()
                && player.level() == expected.level()
                && player.level() instanceof ServerLevel serverLevel
                && isWithinInteractionRange(player, expected)
                && resolve(serverLevel, playerPosition(expected))
                .map(expected::sameMembers).orElse(false);
    }

    private boolean isMutationContextValid(ServerPlayer player, TargetContext expected) {
        return player != null && player.isAlive() && expected.completeTopology()
                && player.level() == expected.level()
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

    private boolean isCreationPresentationValid(ServerPlayer player, CreationPresentationGuard presentation) {
        return player != null && presentation != null && presentation.isActive()
                && presentation.isCustomPrompt();
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

    private void auditCreateOnce(CreationReservation reservation, Outcome outcome, String reasonCode) {
        if (reservation.auditRecorded().compareAndSet(false, true)) {
            auditCreate(reservation.session(), reservation.target(), reservation.item(), outcome, reasonCode);
        }
    }

    private void auditCreate(ServerPlayer player, TargetContext target, ItemStack item,
                             Outcome outcome, String reasonCode) {
        if (player == null || item == null) return;
        emitAudit(player, AuditActions.LOCK_CREATED, outcome, target, reasonCode,
                AuditPayload.forAction(AuditActions.LOCK_CREATED,
                        AuditPayload.itemType(itemType(item)), AuditPayload.count(item.getCount()),
                        AuditPayload.world(lockWorld(target)), lockPosition(target),
                        AuditPayload.result(outcome.code())));
    }

    private void auditPasswordVerification(ServerPlayer player, LockPrompt prompt,
                                           Outcome outcome, String reasonCode) {
        if (player == null || prompt == null) return;
        TargetContext target = prompt.target();
        emitAudit(player, AuditActions.LOCK_PASSWORD_VERIFY, outcome, target, reasonCode,
                AuditPayload.forAction(AuditActions.LOCK_PASSWORD_VERIFY,
                        AuditPayload.world(lockWorld(target)), lockPosition(target),
                        AuditPayload.result(outcome.code()), AuditPayload.playerUuid(player.getUUID()),
                        AuditPayload.playerName(player.getName().getString())));
    }

    private void auditLockRemoval(ServerPlayer player, TargetContext target,
                                  Outcome outcome, String reasonCode) {
        emitAudit(player, AuditActions.LOCK_REMOVED, outcome, target, reasonCode,
                AuditPayload.forAction(AuditActions.LOCK_REMOVED,
                        AuditPayload.world(lockWorld(target)), lockPosition(target),
                        AuditPayload.result(outcome.code())));
    }

    private void auditBreakOnce(PendingBreak operation, Outcome outcome, String reasonCode) {
        if (operation.auditRecorded().compareAndSet(false, true)) {
            auditLockBreak(operation.player(), operation.target(), outcome, reasonCode);
        }
    }

    private void auditLockBreak(ServerPlayer player, TargetContext target,
                                Outcome outcome, String reasonCode) {
        emitAudit(player, AuditActions.LOCK_BREAK, outcome, target, reasonCode,
                AuditPayload.forAction(AuditActions.LOCK_BREAK,
                        AuditPayload.blockType(target.clickedPlacement().blockType().name().toLowerCase(java.util.Locale.ROOT)),
                        AuditPayload.world(lockWorld(target)), lockPosition(target),
                        AuditPayload.result(outcome.code()), AuditPayload.playerUuid(player.getUUID()),
                        AuditPayload.playerName(player.getName().getString())));
    }

    private void emitAudit(ServerPlayer player, String action, Outcome outcome, TargetContext target,
                           String reasonCode, AuditPayload payload) {
        if (auditService == null || player == null) return;
        try {
            auditService.record(new AuditRecordRequest(
                    AuditRecordContext.forActor(new Actor(ActorType.PLAYER, player.getUUID(), player.getName().getString())),
                    Source.LOCK, action, outcome,
                    new Target("lock", lockTarget(target)), reasonCode, 1, payload,
                    AuditDelivery.BEST_EFFORT)).exceptionally(failure -> null);
        } catch (RuntimeException ignored) {
            // Audit delivery is best effort and must never alter lock behavior.
        }
    }

    private static String passwordResultCode(LockAccessDecision decision) {
        return switch (decision) {
            case WRONG_PASSWORD -> "wrong_password";
            case VERIFICATION_BUSY -> "verification_busy";
            case COOLDOWN -> "cooldown";
            default -> "verification_denied";
        };
    }

    private static AuditPayload.Field lockPosition(TargetContext target) {
        BlockPos position = target.clickedPosition();
        return AuditPayload.position(position.getX(), position.getY(), position.getZ());
    }

    private static String lockWorld(TargetContext target) {
        LockTarget lockTarget = target.primary().target();
        return lockTarget.world().value() + ":" + lockTarget.dimension().value();
    }

    private static String lockTarget(TargetContext target) {
        LockTarget lockTarget = target.primary().target();
        BlockPosition position = lockTarget.position();
        return lockTarget.world().value() + ":" + lockTarget.dimension().value() + ":"
                + position.x() + "," + position.y() + "," + position.z();
    }

    private static String itemType(ItemStack item) {
        return BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
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
                                BreakTransaction transaction, AtomicBoolean auditRecorded) {
    }

    private record CreationReservation(UUID token, UUID playerUuid, ServerPlayer session,
                                       InteractionHand hand, CreationPresentationGuard presentation,
                                       ReservationKey key,
                                         TargetContext target, ServerLevel level,
                                         BlockPos position, ItemStack item, AtomicBoolean cancelled,
                                         AtomicBoolean itemFinalized, AtomicBoolean compensationStarted,
                                         AtomicReference<UUID> lockerId, AtomicBoolean auditRecorded) {
    }

    private static final class CreationPresentationGuard {
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean customPrompt = new AtomicBoolean();

        private boolean isActive() {
            return active.get();
        }

        private void useCustomPrompt() {
            customPrompt.set(true);
        }

        private boolean isCustomPrompt() {
            return customPrompt.get();
        }

        private void deactivate() {
            active.set(false);
        }
    }

    private static final class LockPrompt {
        private final ServerPlayer player;
        private final UUID playerUuid;
        private final LockPromptKind kind;
        private final String action;
        private final UUID bindingId;
        private final ActionContextRegistry.Context context;
        private final TargetContext target;
        private final InteractionHand hand;
        private final CreationPresentationGuard presentation;
        private final AtomicReference<CreationReservation> reservation = new AtomicReference<>();

        private LockPrompt(ServerPlayer player, UUID playerUuid, LockPromptKind kind, String action,
                           UUID bindingId, ActionContextRegistry.Context context, TargetContext target,
                           InteractionHand hand, CreationPresentationGuard presentation) {
            this.player = player;
            this.playerUuid = playerUuid;
            this.kind = kind;
            this.action = action;
            this.bindingId = bindingId;
            this.context = context;
            this.target = target;
            this.hand = hand;
            this.presentation = presentation;
        }

        private ServerPlayer player() { return player; }
        private UUID playerUuid() { return playerUuid; }
        private LockPromptKind kind() { return kind; }
        private String action() { return action; }
        private UUID bindingId() { return bindingId; }
        private ActionContextRegistry.Context context() { return context; }
        private TargetContext target() { return target; }
        private InteractionHand hand() { return hand; }
        private CreationPresentationGuard presentation() { return presentation; }
        private UUID token() { return context.token(); }
        private PromptSessionKey sessionKey() {
            return new PromptSessionKey(context.connectionId(), playerUuid);
        }
        private void reservation(CreationReservation value) { reservation.set(value); }
        private CreationReservation reservation() { return reservation.get(); }
        private void presentationDeactivate() { if (presentation != null) presentation.deactivate(); }
    }

    private record ReservationKey(UUID playerUuid, ServerPlayer session) {
    }

    private record PromptSessionKey(UUID connectionId, UUID playerUuid) {
    }

    private record PromptPredecessor(ActionContextRegistry.Context context, PromptSessionKey sessionKey) {
        private boolean matches(ServerPlayer player) {
            return player != null && player.getUUID().equals(context.playerUuid())
                    && player.getUUID().equals(sessionKey.playerUuid());
        }

        private boolean matches(ActionContextRegistry.Context current) {
            return current != null && context.connectionId().equals(current.connectionId())
                    && context.playerUuid().equals(current.playerUuid())
                    && context.nonce().equals(current.nonce())
                    && context.generation() == current.generation();
        }
    }

    private record PendingCompensation(MinecraftServer server, CreationReservation reservation, UUID lockerId,
                                       String message, LockPrompt prompt, boolean dropOnly,
                                       Runnable onCompensated, PendingRetry retry) {
    }

    private static final class PendingRetry {
        private final LockPrompt prompt;
        private final PromptSessionKey sessionKey;
        private final CreationReservation reservation;
        private final LockPromptResultCode code;
        private final int cooldownSeconds;
        private final String message;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private PendingRetry(LockPrompt prompt, PromptSessionKey sessionKey, CreationReservation reservation,
                             LockPromptResultCode code, int cooldownSeconds, String message) {
            this.prompt = prompt;
            this.sessionKey = sessionKey;
            this.reservation = reservation;
            this.code = code;
            this.cooldownSeconds = cooldownSeconds;
            this.message = message;
        }

        private boolean matches(ServerPlayer player) {
            return player == prompt.player() && player.getUUID().equals(prompt.playerUuid())
                    && prompt.context().connectionId().equals(sessionKey.connectionId())
                    && prompt.context().playerUuid().equals(sessionKey.playerUuid());
        }

        private boolean cancel() {
            return cancelled.compareAndSet(false, true);
        }

        private boolean cancelled() { return cancelled.get(); }
        private LockPrompt prompt() { return prompt; }
        private PromptSessionKey sessionKey() { return sessionKey; }
        private CreationReservation reservation() { return reservation; }
        private LockPromptResultCode code() { return code; }
        private int cooldownSeconds() { return cooldownSeconds; }
        private String message() { return message; }
    }
}
