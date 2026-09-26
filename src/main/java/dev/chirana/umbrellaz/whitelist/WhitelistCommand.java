package dev.chirana.umbrellaz.whitelist;

import dev.chirana.umbrellaz.auth.AuthService;
import dev.chirana.umbrellaz.auth.AuthEvents;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.config.UmbrellazConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.network.ServerPlayerEntity;

public final class WhitelistCommand {
    private final WhitelistService whitelistService;
    private final AuthorizationService authorizationService;
    private final AuthService authService;
    private final UmbrellazConfig config;

    public WhitelistCommand(WhitelistService whitelistService, AuthorizationService authorizationService, AuthService authService, UmbrellazConfig config) {
        this.whitelistService = whitelistService;
        this.authorizationService = authorizationService;
        this.authService = authService;
        this.config = config;
    }

    public void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(rootCommand("umbrellaz").then(whitelistCommand("whitelist")));
        dispatcher.register(rootCommand("uz")
                .then(whitelistCommand("whitelist"))
                .then(whitelistCommand("wl")));
    }

    private LiteralArgumentBuilder<ServerCommandSource> rootCommand(String name) {
        return CommandManager.literal(name)
                .executes(context -> help(context.getSource()))
                .then(CommandManager.literal("help").executes(context -> help(context.getSource())));
    }

    private LiteralArgumentBuilder<ServerCommandSource> whitelistCommand(String name) {
        return CommandManager.literal(name)
                .executes(context -> whitelistHelp(context.getSource()))
                .then(CommandManager.literal("help").executes(context -> whitelistHelp(context.getSource())))
                .then(CommandManager.literal("list").executes(context -> list(context.getSource())))
                .then(CommandManager.literal("add")
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .executes(context -> add(context.getSource(), StringArgumentType.getString(context, "player")))))
                .then(removeCommand("remove"))
                .then(removeCommand("rm"))
                .then(CommandManager.literal("check")
                        .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(context -> check(context.getSource(), StringArgumentType.getString(context, "player")))));
    }

    private int help(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("Umbrellaz - comandos disponíveis:"), false);
        source.sendFeedback(() -> Text.literal("/uz whitelist: gerencia a whitelist do servidor"), false);
        source.sendFeedback(() -> Text.literal("/uz wl: atalho para /uz whitelist"), false);
        source.sendFeedback(() -> Text.literal("Use /uz whitelist help para ver todas as operações."), false);
        return 1;
    }

    private int whitelistHelp(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("Whitelist Umbrellaz - comandos:"), false);
        source.sendFeedback(() -> Text.literal("/uz wl list: lista os jogadores autorizados"), false);
        source.sendFeedback(() -> Text.literal("/uz wl add <jogador>: adiciona um jogador à whitelist e o libera"), false);
        source.sendFeedback(() -> Text.literal("/uz wl remove <jogador>: remove um jogador e aplica o bloqueio"), false);
        source.sendFeedback(() -> Text.literal("/uz wl rm <jogador>: atalho para remove"), false);
        source.sendFeedback(() -> Text.literal("/uz wl check <jogador>: verifica se um jogador está autorizado"), false);
        return 1;
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> removeCommand(String name) {
        return CommandManager.literal(name)
                .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(context -> remove(context.getSource(), StringArgumentType.getString(context, "player"))));
    }

    private int list(ServerCommandSource source) {
        authorized(source).thenAccept(allowed -> source.getServer().execute(() -> {
            if (!allowed) {
                deny(source);
                return;
            }
            whitelistService.list().thenAccept(entries -> source.getServer().execute(() -> {
                source.sendFeedback(() -> Text.literal("Whitelisted players: " + entries.size()), false);
                entries.forEach(entry -> source.sendFeedback(() -> Text.literal(entry.playerUuid().toString()), false));
            })).exceptionally(throwable -> report(source, "Unable to list whitelist"));
        })).exceptionally(throwable -> report(source, "Unable to authorize command"));
        return 1;
    }

    private int add(ServerCommandSource source, String username) {
        authorized(source).thenAccept(allowed -> source.getServer().execute(() -> {
            if (!allowed) {
                deny(source);
                return;
            }
            whitelistService.addByUsername(username, source.getName()).thenAccept(result -> source.getServer().execute(() -> {
                if (result.isEmpty()) {
                    source.sendError(Text.literal("No known player named " + username));
                    return;
                }
                UUID uuid = result.get();
                authService.reconcileAuthorization(uuid).thenAccept(authenticated -> source.getServer().execute(() -> {
                    if (authenticated) {
                        source.sendFeedback(() -> Text.literal("Added " + username + " to the whitelist"), true);
                    } else {
                        source.sendError(Text.literal("Whitelist updated, but authorization remains blocked"));
                    }
                })).exceptionally(throwable -> report(source, "Unable to authorize player"));
            })).exceptionally(throwable -> report(source, "Unable to add player"));
        })).exceptionally(throwable -> report(source, "Unable to authorize command"));
        return 1;
    }

    private int remove(ServerCommandSource source, String username) {
        authorized(source).thenAccept(allowed -> source.getServer().execute(() -> {
            if (!allowed) {
                deny(source);
                return;
            }
            whitelistService.findPlayerUuid(username).thenAccept(result -> source.getServer().execute(() -> {
                if (result.isEmpty()) {
                    source.sendError(Text.literal("No known player named " + username));
                    return;
                }
                UUID uuid = result.get();
                whitelistService.remove(uuid).thenCompose(ignored -> authService.reconcileAuthorization(uuid))
                        .thenAccept(authenticated -> source.getServer().execute(() -> {
                            if (!authenticated) {
                                var player = source.getServer().getPlayerManager().getPlayer(uuid);
                                if (player != null) {
                                    player.closeHandledScreen();
                                    AuthEvents.teleportToRestrictedSpawn(source.getServer(), player, config);
                                }
                            }
                            source.sendFeedback(() -> Text.literal("Removed " + username + " from the whitelist"), true);
                        })).exceptionally(throwable -> report(source, "Unable to remove player"));
            })).exceptionally(throwable -> report(source, "Unable to find player"));
        })).exceptionally(throwable -> report(source, "Unable to authorize command"));
        return 1;
    }

    private int check(ServerCommandSource source, String username) {
        authorized(source).thenAccept(allowed -> source.getServer().execute(() -> {
            if (!allowed) {
                deny(source);
                return;
            }
            whitelistService.findPlayerUuid(username).thenAccept(result -> source.getServer().execute(() -> {
                boolean present = result.map(whitelistService::isWhitelisted).orElse(false);
                source.sendFeedback(() -> Text.literal(username + (present ? " is whitelisted" : " is not whitelisted")), false);
            })).exceptionally(throwable -> report(source, "Unable to check player"));
        })).exceptionally(throwable -> report(source, "Unable to authorize command"));
        return 1;
    }

    private CompletableFuture<Boolean> authorized(ServerCommandSource source) {
        if (source.getEntity() == null) {
            return CompletableFuture.completedFuture(true);
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.completedFuture(authorizationService.isAdministrator(player.getUuid()));
    }

    private void deny(ServerCommandSource source) {
        source.sendError(Text.literal("Você não tem autorização Umbrellaz para gerenciar a whitelist"));
    }

    private Void report(ServerCommandSource source, String message) {
        source.getServer().execute(() -> source.sendError(Text.literal(message)));
        return null;
    }
}
