package dev.chirana.umbrellaz.whitelist;

import dev.chirana.umbrellaz.auth.AuthService;
import dev.chirana.umbrellaz.auth.AuthEvents;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.authorization.CommandAuthorization;
import dev.chirana.umbrellaz.config.UmbrellazConfig;
import dev.chirana.umbrellaz.player.PlayerResolution;
import dev.chirana.umbrellaz.player.Player;
import dev.chirana.umbrellaz.player.OnlinePlayerSuggestions;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class WhitelistCommand {
    private static final String UNKNOWN_PLAYER_NAME = "Jogador sem nome";
    private static final DateTimeFormatter DETAILS_DATE_FORMAT = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final WhitelistService whitelistService;
    private final AuthorizationService authorizationService;
    private final AuthService authService;
    private final UmbrellazConfig config;
    private final OnlinePlayerSuggestions playerSuggestions;

    public WhitelistCommand(WhitelistService whitelistService, AuthorizationService authorizationService, AuthService authService, UmbrellazConfig config) {
        this(whitelistService, authorizationService, authService, config, new OnlinePlayerSuggestions());
    }

    public WhitelistCommand(WhitelistService whitelistService, AuthorizationService authorizationService,
                            AuthService authService, UmbrellazConfig config,
                            OnlinePlayerSuggestions playerSuggestions) {
        this.whitelistService = whitelistService;
        this.authorizationService = authorizationService;
        this.authService = authService;
        this.config = config;
        this.playerSuggestions = playerSuggestions;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(rootCommand("umbrellaz").then(whitelistCommand("whitelist")));
        dispatcher.register(rootCommand("uz")
                .then(whitelistCommand("whitelist"))
                .then(whitelistCommand("wl")));
    }

    private LiteralArgumentBuilder<CommandSourceStack> rootCommand(String name) {
        return Commands.literal(name)
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource())));
    }

    private LiteralArgumentBuilder<CommandSourceStack> whitelistCommand(String name) {
        return Commands.literal(name)
                .executes(context -> whitelistHelp(context.getSource()))
                .then(Commands.literal("help").executes(context -> whitelistHelp(context.getSource())))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource(), false))
                        .then(Commands.literal("details")
                                .executes(context -> list(context.getSource(), true))))
                .then(Commands.literal("users")
                        .then(Commands.literal("list")
                                .executes(context -> listUsers(context.getSource(), null))
                                .then(Commands.literal("non-auth")
                                        .executes(context -> listUsers(context.getSource(), UserListSection.NON_AUTH)))
                                .then(Commands.literal("auth")
                                        .executes(context -> listUsers(context.getSource(), UserListSection.AUTH)))))
                .then(Commands.literal("add")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(playerSuggestions)
                                .executes(context -> add(context.getSource(), StringArgumentType.getString(context, "player")))))
                .then(removeCommand("remove"))
                .then(removeCommand("rm"))
                .then(Commands.literal("check")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(playerSuggestions)
                                .executes(context -> check(context.getSource(), StringArgumentType.getString(context, "player")))));
    }

    private int help(CommandSourceStack source) {
        source.sendSuccess(() -> header("Central de comandos"), false);
        source.sendSuccess(() -> helpLine("/uz whitelist", "Gerencia a whitelist do servidor"), false);
        source.sendSuccess(() -> helpLine("/uz wl", "Atalho para /uz whitelist"), false);
        source.sendSuccess(() -> helpLine("/uz tp", "Teleporta jogadores · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz time", "Altera o tempo dos mundos · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz sky", "Altera o clima dos mundos · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz hp  /uz xp  /uz food", "Consulta e altera HP, XP ou fome · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz hungry", "Atalho para /uz food"), false);
        source.sendSuccess(() -> helpLine("/uz kill", "Mata jogadores · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz user <jogador> alias <alias>", "Define aliases · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz blocks", "Automação de árvores e minérios · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz reload", "Recarrega os caches do Umbrellaz · administradores"), false);
        source.sendSuccess(() -> Component.empty()
                .append(Component.literal("  Dica  ").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD))
                .append(Component.literal("Use ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/uz whitelist help").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" para ver todas as operações.").withStyle(ChatFormatting.GRAY)), false);
        return 1;
    }

    private int whitelistHelp(CommandSourceStack source) {
        source.sendSuccess(() -> header("Whitelist"), false);
        source.sendSuccess(() -> helpLine("/uz wl list", "Lista os jogadores autorizados"), false);
        source.sendSuccess(() -> helpLine("/uz wl list details", "Mostra UUID, data e quem adicionou"), false);
        source.sendSuccess(() -> helpLine("/uz wl users list", "Lista jogadores conhecidos por status da whitelist"), false);
        source.sendSuccess(() -> helpLine("/uz wl users list non-auth", "Lista jogadores não autorizados pela whitelist"), false);
        source.sendSuccess(() -> helpLine("/uz wl users list auth", "Lista jogadores autorizados pela whitelist"), false);
        source.sendSuccess(() -> helpLine("/uz wl add <jogador>", "Adiciona à whitelist e libera o acesso"), false);
        source.sendSuccess(() -> helpLine("/uz wl remove <jogador>", "Remove da whitelist e aplica o bloqueio"), false);
        source.sendSuccess(() -> helpLine("/uz wl rm <jogador>", "Atalho para remove"), false);
        source.sendSuccess(() -> helpLine("/uz wl check <jogador>", "Verifica se o jogador está autorizado"), false);
        return 1;
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> removeCommand(String name) {
        return Commands.literal(name)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(playerSuggestions)
                        .executes(context -> remove(context.getSource(), StringArgumentType.getString(context, "player"))));
    }

    private int list(CommandSourceStack source, boolean details) {
        authorized(source).thenAccept(allowed -> source.getServer().execute(() -> {
            if (!allowed) {
                deny(source);
                return;
            }
            whitelistService.list().thenAccept(entries -> source.getServer().execute(() -> {
                source.sendSuccess(() -> Component.empty()
                        .append(Component.literal(details ? "◆ WHITELIST · DETALHES" : "◆ WHITELIST")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                        .append(Component.literal("  " + entries.size() + (entries.size() == 1 ? " jogador" : " jogadores"))
                                .withStyle(ChatFormatting.YELLOW)), false);
                if (entries.isEmpty()) {
                    source.sendSuccess(() -> warning("Nenhum jogador autorizado ainda."), false);
                    return;
                }
                entries.forEach(entry -> {
                    if (details) {
                        sendDetails(source, entry);
                    } else {
                        source.sendSuccess(() -> Component.empty()
                                .append(Component.literal("  • ").withStyle(ChatFormatting.DARK_GRAY))
                                .append(displayName(entry)), false);
                    }
                });
            })).exceptionally(throwable -> report(source, "Não foi possível listar a whitelist"));
        })).exceptionally(throwable -> report(source, "Não foi possível autorizar o comando"));
        return 1;
    }

    private int listUsers(CommandSourceStack source, UserListSection requestedSection) {
        authorized(source).thenAccept(allowed -> source.getServer().execute(() -> {
            if (!allowed) {
                deny(source);
                return;
            }
            whitelistService.listUsers().thenAccept(users -> source.getServer().execute(() ->
                    sendUsers(source, users, requestedSection))).exceptionally(throwable ->
                    report(source, "Não foi possível listar os jogadores conhecidos"));
        })).exceptionally(throwable -> report(source, "Não foi possível autorizar o comando"));
        return 1;
    }

    private void sendUsers(CommandSourceStack source, java.util.List<WhitelistUser> users,
                           UserListSection requestedSection) {
        if (users.isEmpty()) {
            source.sendSuccess(() -> warning("Nenhum jogador conhecido."), false);
            return;
        }

        if (requestedSection != null) {
            sendUsersSection(source, users.stream()
                    .filter(user -> requestedSection.matches(user.whitelisted()))
                    .toList(), requestedSection);
            return;
        }

        java.util.List<WhitelistUser> nonAuth = users.stream()
                .filter(user -> !user.whitelisted())
                .toList();
        java.util.List<WhitelistUser> auth = users.stream()
                .filter(WhitelistUser::whitelisted)
                .toList();
        if (nonAuth.isEmpty() && auth.isEmpty()) {
            source.sendSuccess(() -> warning("Nenhum jogador conhecido."), false);
            return;
        }
        if (!nonAuth.isEmpty()) {
            sendUsersSection(source, nonAuth, UserListSection.NON_AUTH);
        }
        if (!auth.isEmpty()) {
            sendUsersSection(source, auth, UserListSection.AUTH);
        }
    }

    private void sendUsersSection(CommandSourceStack source, java.util.List<WhitelistUser> users,
                                  UserListSection section) {
        source.sendSuccess(() -> Component.empty()
                .append(Component.literal(section.heading).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal("  " + users.size() + (users.size() == 1 ? " jogador" : " jogadores"))
                        .withStyle(ChatFormatting.YELLOW)), false);
        if (users.isEmpty()) {
            source.sendSuccess(() -> warning("Nenhum jogador nesta seção."), false);
            return;
        }
        users.forEach(user -> source.sendSuccess(() -> Component.empty()
                .append(Component.literal("  • ").withStyle(ChatFormatting.DARK_GRAY))
                .append(displayUser(user)), false));
    }

    private Component displayUser(WhitelistUser user) {
        Player player = user.player();
        String username = player.username();
        String displayedUsername = username == null
                ? "<nome nulo>"
                : username.isBlank() ? "<nome em branco>" : username;
        var result = Component.empty().append(playerName(displayedUsername));
        if (player.alias() != null) {
            String displayedAlias = player.alias().isBlank() ? "<em branco>" : player.alias();
            result.append(Component.literal("  · alias: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(displayedAlias).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        return result;
    }

    private enum UserListSection {
        NON_AUTH("◆ JOGADORES · NÃO AUTORIZADOS PELA WHITELIST"),
        AUTH("◆ JOGADORES · AUTORIZADOS PELA WHITELIST");

        private final String heading;

        UserListSection(String heading) {
            this.heading = heading;
        }

        private boolean matches(boolean whitelisted) {
            return (this == AUTH) == whitelisted;
        }
    }

    private void sendDetails(CommandSourceStack source, WhitelistEntry entry) {
        source.sendSuccess(() -> Component.empty()
                .append(Component.literal("  ◆ ").withStyle(ChatFormatting.GOLD))
                .append(displayName(entry)), false);
        source.sendSuccess(() -> detailLine("UUID", entry.playerUuid().toString(), ChatFormatting.AQUA), false);
        source.sendSuccess(() -> detailLine(
                "Criado em",
                DETAILS_DATE_FORMAT.format(entry.createdAt()),
                ChatFormatting.WHITE), false);
        source.sendSuccess(() -> detailLine("Adicionado por", entry.createdBy(), ChatFormatting.YELLOW), false);
    }

    private Component detailLine(String label, String value, ChatFormatting valueColor) {
        return Component.empty()
                .append(Component.literal("    " + label + "  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(value).withStyle(valueColor));
    }

    private Component displayName(WhitelistEntry entry) {
        if (entry.username() == null || entry.username().isBlank()) {
            return Component.literal(UNKNOWN_PLAYER_NAME).withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC);
        }
        return playerName(entry.username());
    }

    private int add(CommandSourceStack source, String username) {
        authorized(source).thenAccept(allowed -> source.getServer().execute(() -> {
            if (!allowed) {
                deny(source);
                return;
            }
            whitelistService.addByUsername(username, source.getTextName()).thenAccept(result -> source.getServer().execute(() -> {
                if (result.status() != PlayerResolution.Status.FOUND) {
                    resolutionFailure(source, username, result.status());
                    return;
                }
                UUID uuid = result.player().uuid();
                authService.reconcileAuthorization(uuid).thenAccept(authenticated -> source.getServer().execute(() -> {
                    if (authenticated) {
                        source.sendSuccess(() -> Component.empty()
                                .append(successPrefix())
                                .append(playerName(username))
                                .append(Component.literal(" foi adicionado à whitelist e está liberado.")
                                        .withStyle(ChatFormatting.GREEN)), true);
                    } else {
                        source.sendFailure(Component.empty()
                                .append(warningPrefix())
                                .append(Component.literal("Whitelist atualizada, mas ").withStyle(ChatFormatting.YELLOW))
                                .append(playerName(username))
                                .append(Component.literal(" continua bloqueado.").withStyle(ChatFormatting.YELLOW)));
                    }
                })).exceptionally(throwable -> report(source, "Não foi possível autorizar o jogador"));
            })).exceptionally(throwable -> report(source, "Não foi possível adicionar o jogador"));
        })).exceptionally(throwable -> report(source, "Não foi possível autorizar o comando"));
        return 1;
    }

    private int remove(CommandSourceStack source, String username) {
        authorized(source).thenAccept(allowed -> source.getServer().execute(() -> {
            if (!allowed) {
                deny(source);
                return;
            }
            whitelistService.findPlayerUuid(username).thenAccept(result -> source.getServer().execute(() -> {
                if (result.status() != PlayerResolution.Status.FOUND) {
                    resolutionFailure(source, username, result.status());
                    return;
                }
                UUID uuid = result.player().uuid();
                whitelistService.remove(uuid).thenCompose(ignored -> authService.reconcileAuthorization(uuid))
                        .thenAccept(authenticated -> source.getServer().execute(() -> {
                            if (!authenticated) {
                                var player = source.getServer().getPlayerList().getPlayer(uuid);
                                if (player != null) {
                                    player.closeContainer();
                                    AuthEvents.teleportToRestrictedSpawn(source.getServer(), player, config);
                                }
                            }
                            source.sendSuccess(() -> Component.empty()
                                    .append(successPrefix())
                                    .append(playerName(username))
                                    .append(Component.literal(" foi removido da whitelist e está bloqueado.")
                                            .withStyle(ChatFormatting.GREEN)), true);
                        })).exceptionally(throwable -> report(source, "Não foi possível remover o jogador"));
            })).exceptionally(throwable -> report(source, "Não foi possível localizar o jogador"));
        })).exceptionally(throwable -> report(source, "Não foi possível autorizar o comando"));
        return 1;
    }

    private int check(CommandSourceStack source, String username) {
        authorized(source).thenAccept(allowed -> source.getServer().execute(() -> {
            if (!allowed) {
                deny(source);
                return;
            }
            whitelistService.findPlayerUuid(username).thenAccept(result -> source.getServer().execute(() -> {
                if (result.status() != PlayerResolution.Status.FOUND) {
                    resolutionFailure(source, username, result.status());
                    return;
                }
                boolean present = whitelistService.isWhitelisted(result.player().uuid());
                source.sendSuccess(() -> Component.empty()
                        .append(Component.literal(present ? "✓ " : "✕ ")
                                .withStyle(present ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD))
                        .append(playerName(username))
                        .append(Component.literal(present ? " está na whitelist." : " não está na whitelist.")
                                .withStyle(present ? ChatFormatting.GREEN : ChatFormatting.RED)), false);
            })).exceptionally(throwable -> report(source, "Não foi possível verificar o jogador"));
        })).exceptionally(throwable -> report(source, "Não foi possível autorizar o comando"));
        return 1;
    }

    private CompletableFuture<Boolean> authorized(CommandSourceStack source) {
        return CompletableFuture.completedFuture(CommandAuthorization.isAdministrator(source, authorizationService));
    }

    private void deny(CommandSourceStack source) {
        source.sendFailure(error("Você não tem autorização Umbrellaz para gerenciar a whitelist."));
    }

    private Void report(CommandSourceStack source, String message) {
        source.getServer().execute(() -> source.sendFailure(error(message + ".")));
        return null;
    }

    private void resolutionFailure(CommandSourceStack source, String identifier, PlayerResolution.Status status) {
        String message = switch (status) {
            case AMBIGUOUS -> "Identificador ambíguo: ";
            case NOT_READY -> "A resolução de aliases ainda não está pronta: ";
            case NOT_FOUND -> "Jogador desconhecido: ";
            case FOUND -> "";
        };
        source.sendFailure(Component.empty()
                .append(errorPrefix())
                .append(Component.literal(message).withStyle(ChatFormatting.RED))
                .append(playerName(identifier)));
    }

    private Component header(String section) {
        return Component.empty()
                .append(Component.literal("◆ UMBRELLAZ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal("  " + section).withStyle(ChatFormatting.YELLOW));
    }

    private Component helpLine(String command, String description) {
        return Component.empty()
                .append(Component.literal("  " + command).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("  —  " + description).withStyle(ChatFormatting.GRAY));
    }

    private Component playerName(String name) {
        return Component.literal(name).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
    }

    private Component successPrefix() {
        return Component.literal("✓ ").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
    }

    private Component warningPrefix() {
        return Component.literal("⚠ ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
    }

    private Component errorPrefix() {
        return Component.literal("✕ ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
    }

    private Component warning(String message) {
        return Component.empty()
                .append(warningPrefix())
                .append(Component.literal(message).withStyle(ChatFormatting.YELLOW));
    }

    private Component error(String message) {
        return Component.empty()
                .append(errorPrefix())
                .append(Component.literal(message).withStyle(ChatFormatting.RED));
    }
}
