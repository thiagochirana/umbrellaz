package dev.chirana.umbrellaz.audit;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.authorization.CommandAuthorization;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AuditCommand {
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([smhd])");
    private static final String QUERY_FAILURE = "Não foi possível consultar a auditoria.";
    private final AuditService auditService;
    private final AuthorizationService authorizationService;

    public AuditCommand(AuditService auditService, AuthorizationService authorizationService) {
        this.auditService = auditService;
        this.authorizationService = authorizationService;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("umbrellaz").then(auditCommand()));
        dispatcher.register(Commands.literal("uz").then(auditCommand()));
    }

    private LiteralArgumentBuilder<CommandSourceStack> auditCommand() {
        return Commands.literal("audit")
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(listCommand())
                .then(Commands.literal("show")
                        .then(Commands.argument("event-id", StringArgumentType.word())
                                .executes(context -> show(context.getSource(),
                                        StringArgumentType.getString(context, "event-id")))))
                .then(Commands.literal("status").executes(context -> status(context.getSource())));
    }

    private LiteralArgumentBuilder<CommandSourceStack> listCommand() {
        return Commands.literal("list")
                .executes(context -> list(context.getSource(), AuditQuery.defaults()))
                .then(Commands.argument("cursor", LongArgumentType.longArg(1))
                        .executes(context -> list(context.getSource(), new AuditQuery(AuditQuery.DEFAULT_LIMIT,
                                LongArgumentType.getLong(context, "cursor"), null, null, null))))
                .then(filterCommand("action"))
                .then(filterCommand("actor"))
                .then(filterCommand("since"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> filterCommand(String filter) {
        return Commands.literal(filter)
                .then(Commands.argument("value", StringArgumentType.word())
                        .executes(context -> listFiltered(context.getSource(), filter,
                                StringArgumentType.getString(context, "value"), null))
                        .then(Commands.argument("cursor", LongArgumentType.longArg(1))
                                .executes(context -> listFiltered(context.getSource(), filter,
                                        StringArgumentType.getString(context, "value"),
                                        LongArgumentType.getLong(context, "cursor")))));
    }

    private int listFiltered(CommandSourceStack source, String filter, String value, Long cursor) {
        try {
            AuditQuery query = switch (filter) {
                case "action" -> new AuditQuery(AuditQuery.DEFAULT_LIMIT, cursor, value, null, null);
                case "actor" -> new AuditQuery(AuditQuery.DEFAULT_LIMIT, cursor, null, UUID.fromString(value), null);
                case "since" -> new AuditQuery(AuditQuery.DEFAULT_LIMIT, cursor, null, null,
                        Math.max(0, System.currentTimeMillis() - parseDuration(value).toMillis()));
                default -> throw new IllegalArgumentException("Unknown audit filter");
            };
            return list(source, query);
        } catch (RuntimeException exception) {
            source.sendFailure(error("Filtro de auditoria inválido."));
            return 0;
        }
    }

    private int list(CommandSourceStack source, AuditQuery query) {
        if (!authorized(source)) {
            deny(source);
            return 0;
        }
        auditService.query(query).thenAccept(page -> source.getServer().execute(() -> {
            source.sendSuccess(() -> header("Auditoria"), false);
            if (page.events().isEmpty()) {
                source.sendSuccess(() -> Component.literal("Nenhum evento encontrado.")
                        .withStyle(ChatFormatting.GRAY), false);
                return;
            }
            page.events().forEach(stored -> source.sendSuccess(() -> summary(stored), false));
            if (page.nextCursor() != null) {
                source.sendSuccess(() -> Component.literal("Próxima página: /uz audit list " + page.nextCursor())
                        .withStyle(ChatFormatting.YELLOW), false);
            }
        })).exceptionally(throwable -> reportFailure(source, throwable));
        return 1;
    }

    private int show(CommandSourceStack source, String eventId) {
        if (!authorized(source)) {
            deny(source);
            return 0;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(eventId);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(error("ID de evento inválido."));
            return 0;
        }
        auditService.show(uuid).thenAccept(stored -> source.getServer().execute(() -> {
            source.sendSuccess(() -> header("Evento de auditoria"), false);
            source.sendSuccess(() -> summary(stored), false);
            source.sendSuccess(() -> Component.literal(stored.legacyOpaquePayload()
                    ? "Payload: [legado opaco]"
                    : "Payload: estruturado e validado")
                    .withStyle(ChatFormatting.GRAY), false);
        })).exceptionally(throwable -> reportFailure(source, throwable));
        return 1;
    }

    private int status(CommandSourceStack source) {
        if (!authorized(source)) {
            deny(source);
            return 0;
        }
        AuditWriterStatus status = auditService.status();
        source.sendSuccess(() -> header("Auditoria · status"), false);
        source.sendSuccess(() -> Component.literal("Saúde: " + (status.healthy() ? "saudável" : "indisponível")
                + " · fila: " + status.queued()), false);
        source.sendSuccess(() -> Component.literal("Aceitos: " + status.accepted() + " · descartados: "
                + status.dropped() + " · falhos: " + status.failed()), false);
        return 1;
    }

    private int help(CommandSourceStack source) {
        source.sendSuccess(() -> header("Auditoria"), false);
        source.sendSuccess(() -> Component.literal("/uz audit list [cursor]"), false);
        source.sendSuccess(() -> Component.literal("/uz audit list action <código> [cursor]"), false);
        source.sendSuccess(() -> Component.literal("/uz audit list actor <uuid> [cursor]"), false);
        source.sendSuccess(() -> Component.literal("/uz audit list since <duração> [cursor]"), false);
        source.sendSuccess(() -> Component.literal("/uz audit show <id>"), false);
        source.sendSuccess(() -> Component.literal("/uz audit status"), false);
        return 1;
    }

    private Component summary(AuditStoredEvent stored) {
        AuditEvent event = stored.event();
        return Component.literal("#" + stored.sequence() + " " + event.eventId() + " · " + event.action()
                + " · " + event.outcome().code() + (stored.legacyOpaquePayload() ? " · payload legado" : ""));
    }

    private Duration parseDuration(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing duration");
        }
        long seconds = 0;
        Matcher matcher = DURATION_PART.matcher(value.toLowerCase(java.util.Locale.ROOT));
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) {
                throw new IllegalArgumentException("Invalid duration");
            }
            long amount = Long.parseLong(matcher.group(1));
            seconds = Math.addExact(seconds, Math.multiplyExact(amount, switch (matcher.group(2)) {
                case "s" -> 1;
                case "m" -> 60;
                case "h" -> 3_600;
                case "d" -> 86_400;
                default -> throw new IllegalArgumentException("Invalid duration");
            }));
            end = matcher.end();
        }
        if (end != value.length() || seconds < 1) {
            throw new IllegalArgumentException("Invalid duration");
        }
        return Duration.ofSeconds(seconds);
    }

    private boolean authorized(CommandSourceStack source) {
        return CommandAuthorization.isAdministrator(source, authorizationService);
    }

    private void deny(CommandSourceStack source) {
        source.sendFailure(error("Você não tem autorização Umbrellaz para consultar a auditoria."));
    }

    private Void reportFailure(CommandSourceStack source, Throwable throwable) {
        LoggerHolder.LOGGER.error("Audit command query failed", unwrap(throwable));
        try {
            source.getServer().execute(() -> source.sendFailure(error(QUERY_FAILURE)));
        } catch (Throwable ignored) {
            LoggerHolder.LOGGER.error("Unable to dispatch audit command failure to the server thread", ignored);
        }
        return null;
    }

    private Throwable unwrap(Throwable throwable) {
        return throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause() : throwable;
    }

    private Component header(String section) {
        return Component.literal("◆ UMBRELLAZ  " + section).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    private Component error(String message) {
        return Component.literal("✕ " + message).withStyle(ChatFormatting.RED);
    }

    private static final class LoggerHolder {
        private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(AuditCommand.class);
    }
}
