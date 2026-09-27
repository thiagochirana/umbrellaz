package dev.chirana.umbrellaz.player;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.authorization.CommandAuthorization;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class UserCommand {
    private final PlayerService playerService;
    private final AuthorizationService authorizationService;
    private final OnlinePlayerSuggestions playerSuggestions;

    public UserCommand(PlayerService playerService, AuthorizationService authorizationService) {
        this(playerService, authorizationService, new OnlinePlayerSuggestions());
    }

    public UserCommand(PlayerService playerService, AuthorizationService authorizationService,
                       OnlinePlayerSuggestions playerSuggestions) {
        this.playerService = playerService;
        this.authorizationService = authorizationService;
        this.playerSuggestions = playerSuggestions;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("umbrellaz").then(userCommand()));
        dispatcher.register(Commands.literal("uz").then(userCommand()));
    }

    private LiteralArgumentBuilder<CommandSourceStack> userCommand() {
        return Commands.literal("user")
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                 .then(Commands.argument("player", StringArgumentType.word())
                         .suggests(playerSuggestions)
                         .then(Commands.literal("alias")
                                .then(Commands.argument("alias", StringArgumentType.word())
                                        .executes(context -> setAlias(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "player"),
                                                StringArgumentType.getString(context, "alias"))))));
    }

    private int setAlias(CommandSourceStack source, String player, String alias) {
        if (!authorized(source)) {
            source.sendFailure(error("Você não tem autorização Umbrellaz para gerenciar aliases."));
            return 0;
        }
        playerService.setAlias(player, alias)
                .thenAccept(result -> source.getServer().execute(() -> sendResult(source, player, alias, result)))
                .exceptionally(throwable -> {
                    source.getServer().execute(() -> source.sendFailure(error("Não foi possível salvar o alias.")));
                    return null;
                });
        return 1;
    }

    private void sendResult(CommandSourceStack source, String player, String alias, AliasUpdate result) {
        switch (result.status()) {
            case UNKNOWN_PLAYER -> source.sendFailure(Component.empty()
                    .append(errorPrefix())
                    .append(Component.literal("Jogador desconhecido: ").withStyle(ChatFormatting.RED))
                    .append(playerName(player)));
            case CONFLICT -> source.sendFailure(Component.empty()
                    .append(errorPrefix())
                    .append(Component.literal("O alias ").withStyle(ChatFormatting.RED))
                    .append(playerName(alias))
                    .append(Component.literal(" já está em uso por outro jogador.").withStyle(ChatFormatting.RED)));
            case AMBIGUOUS_PLAYER -> source.sendFailure(error("O identificador do jogador é ambíguo; nenhuma alteração foi feita."));
            case USERNAME_CONFLICT -> source.sendFailure(error("Esse alias é o nome atual de outro jogador; nenhuma alteração foi feita."));
            case INVALID_ALIAS -> source.sendFailure(error("Alias inválido. Use apenas letras ASCII, números e sublinhado, com 1 a 32 caracteres."));
            case RESERVED_ALIAS -> source.sendFailure(error("O alias self é reservado e não pode ser atribuído."));
            case NOT_READY -> source.sendFailure(error("A resolução de aliases ainda não está pronta."));
            case UPDATED -> source.sendSuccess(() -> Component.empty()
                    .append(successPrefix())
                    .append(playerName(player))
                    .append(Component.literal(" agora usa o alias ").withStyle(ChatFormatting.GREEN))
                    .append(playerName(alias))
                    .append(Component.literal(".").withStyle(ChatFormatting.GREEN)), true);
        }
    }

    private int help(CommandSourceStack source) {
        source.sendSuccess(() -> header("Usuário · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz user <jogador> alias <alias>", "Define ou substitui o alias do jogador"), false);
        return 1;
    }

    private boolean authorized(CommandSourceStack source) {
        return CommandAuthorization.isAdministrator(source, authorizationService);
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

    private Component errorPrefix() {
        return Component.literal("✕ ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
    }

    private Component error(String message) {
        return Component.empty()
                .append(errorPrefix())
                .append(Component.literal(message).withStyle(ChatFormatting.RED));
    }
}
