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
import net.minecraft.util.Formatting;

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
        source.sendFeedback(() -> header("Central de comandos"), false);
        source.sendFeedback(() -> helpLine("/uz whitelist", "Gerencia a whitelist do servidor"), false);
        source.sendFeedback(() -> helpLine("/uz wl", "Atalho para /uz whitelist"), false);
        source.sendFeedback(() -> helpLine("/uz tp", "Teleporta jogadores · administradores"), false);
        source.sendFeedback(() -> helpLine("/uz hp  /uz xp", "Consulta e altera HP ou XP · administradores"), false);
        source.sendFeedback(() -> helpLine("/uz kill", "Mata jogadores · administradores"), false);
        source.sendFeedback(() -> Text.empty()
                .append(Text.literal("  Dica  ").formatted(Formatting.DARK_GRAY, Formatting.BOLD))
                .append(Text.literal("Use ").formatted(Formatting.GRAY))
                .append(Text.literal("/uz whitelist help").formatted(Formatting.YELLOW))
                .append(Text.literal(" para ver todas as operações.").formatted(Formatting.GRAY)), false);
        return 1;
    }

    private int whitelistHelp(ServerCommandSource source) {
        source.sendFeedback(() -> header("Whitelist"), false);
        source.sendFeedback(() -> helpLine("/uz wl list", "Lista os jogadores autorizados"), false);
        source.sendFeedback(() -> helpLine("/uz wl add <jogador>", "Adiciona à whitelist e libera o acesso"), false);
        source.sendFeedback(() -> helpLine("/uz wl remove <jogador>", "Remove da whitelist e aplica o bloqueio"), false);
        source.sendFeedback(() -> helpLine("/uz wl rm <jogador>", "Atalho para remove"), false);
        source.sendFeedback(() -> helpLine("/uz wl check <jogador>", "Verifica se o jogador está autorizado"), false);
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
                source.sendFeedback(() -> Text.empty()
                        .append(Text.literal("◆ WHITELIST").formatted(Formatting.GOLD, Formatting.BOLD))
                        .append(Text.literal("  " + entries.size() + (entries.size() == 1 ? " jogador" : " jogadores"))
                                .formatted(Formatting.YELLOW)), false);
                if (entries.isEmpty()) {
                    source.sendFeedback(() -> warning("Nenhum jogador autorizado ainda."), false);
                    return;
                }
                entries.forEach(entry -> source.sendFeedback(() -> Text.empty()
                        .append(Text.literal("  • ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal(entry.playerUuid().toString()).formatted(Formatting.AQUA)), false));
            })).exceptionally(throwable -> report(source, "Não foi possível listar a whitelist"));
        })).exceptionally(throwable -> report(source, "Não foi possível autorizar o comando"));
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
                    source.sendError(Text.empty()
                            .append(errorPrefix())
                            .append(Text.literal("Jogador desconhecido: ").formatted(Formatting.RED))
                            .append(playerName(username)));
                    return;
                }
                UUID uuid = result.get();
                authService.reconcileAuthorization(uuid).thenAccept(authenticated -> source.getServer().execute(() -> {
                    if (authenticated) {
                        source.sendFeedback(() -> Text.empty()
                                .append(successPrefix())
                                .append(playerName(username))
                                .append(Text.literal(" foi adicionado à whitelist e está liberado.")
                                        .formatted(Formatting.GREEN)), true);
                    } else {
                        source.sendError(Text.empty()
                                .append(warningPrefix())
                                .append(Text.literal("Whitelist atualizada, mas ").formatted(Formatting.YELLOW))
                                .append(playerName(username))
                                .append(Text.literal(" continua bloqueado.").formatted(Formatting.YELLOW)));
                    }
                })).exceptionally(throwable -> report(source, "Não foi possível autorizar o jogador"));
            })).exceptionally(throwable -> report(source, "Não foi possível adicionar o jogador"));
        })).exceptionally(throwable -> report(source, "Não foi possível autorizar o comando"));
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
                    source.sendError(Text.empty()
                            .append(errorPrefix())
                            .append(Text.literal("Jogador desconhecido: ").formatted(Formatting.RED))
                            .append(playerName(username)));
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
                            source.sendFeedback(() -> Text.empty()
                                    .append(successPrefix())
                                    .append(playerName(username))
                                    .append(Text.literal(" foi removido da whitelist e está bloqueado.")
                                            .formatted(Formatting.GREEN)), true);
                        })).exceptionally(throwable -> report(source, "Não foi possível remover o jogador"));
            })).exceptionally(throwable -> report(source, "Não foi possível localizar o jogador"));
        })).exceptionally(throwable -> report(source, "Não foi possível autorizar o comando"));
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
                source.sendFeedback(() -> Text.empty()
                        .append(Text.literal(present ? "✓ " : "✕ ")
                                .formatted(present ? Formatting.GREEN : Formatting.RED, Formatting.BOLD))
                        .append(playerName(username))
                        .append(Text.literal(present ? " está na whitelist." : " não está na whitelist.")
                                .formatted(present ? Formatting.GREEN : Formatting.RED)), false);
            })).exceptionally(throwable -> report(source, "Não foi possível verificar o jogador"));
        })).exceptionally(throwable -> report(source, "Não foi possível autorizar o comando"));
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
        source.sendError(error("Você não tem autorização Umbrellaz para gerenciar a whitelist."));
    }

    private Void report(ServerCommandSource source, String message) {
        source.getServer().execute(() -> source.sendError(error(message + ".")));
        return null;
    }

    private Text header(String section) {
        return Text.empty()
                .append(Text.literal("◆ UMBRELLAZ").formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal("  " + section).formatted(Formatting.YELLOW));
    }

    private Text helpLine(String command, String description) {
        return Text.empty()
                .append(Text.literal("  " + command).formatted(Formatting.YELLOW))
                .append(Text.literal("  —  " + description).formatted(Formatting.GRAY));
    }

    private Text playerName(String name) {
        return Text.literal(name).formatted(Formatting.AQUA, Formatting.BOLD);
    }

    private Text successPrefix() {
        return Text.literal("✓ ").formatted(Formatting.GREEN, Formatting.BOLD);
    }

    private Text warningPrefix() {
        return Text.literal("⚠ ").formatted(Formatting.YELLOW, Formatting.BOLD);
    }

    private Text errorPrefix() {
        return Text.literal("✕ ").formatted(Formatting.RED, Formatting.BOLD);
    }

    private Text warning(String message) {
        return Text.empty()
                .append(warningPrefix())
                .append(Text.literal(message).formatted(Formatting.YELLOW));
    }

    private Text error(String message) {
        return Text.empty()
                .append(errorPrefix())
                .append(Text.literal(message).formatted(Formatting.RED));
    }
}
