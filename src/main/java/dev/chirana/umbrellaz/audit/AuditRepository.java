package dev.chirana.umbrellaz.audit;

import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AuditRepository {
    private static final String INSERT_SQL = "INSERT INTO audit_events("
            + "event_id, runtime_id, correlation_id, occurred_at_ms, recorded_at_ms, source, action, outcome, "
            + "actor_uuid, actor_type, actor_display_name, target_type, target_id, reason_code, payload_version, payload_json"
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_COLUMNS = "event_id, runtime_id, correlation_id, occurred_at_ms, recorded_at_ms, "
            + "source, action, outcome, actor_uuid, actor_type, actor_display_name, target_type, target_id, "
            + "reason_code, payload_version, payload_json";

    private final SQLiteDatabase database;

    public AuditRepository(SQLiteDatabase database) {
        this.database = Objects.requireNonNull(database, "Database must not be null");
    }

    public void append(AuditEvent event) {
        appendBatch(List.of(Objects.requireNonNull(event, "Audit event must not be null")));
    }

    public void appendBatch(List<AuditEvent> events) {
        Objects.requireNonNull(events, "Events must not be null");
        if (events.isEmpty()) {
            return;
        }
        database.withConnection(connection -> {
            try {
                connection.setAutoCommit(false);
                appendBatch(connection, events);
                connection.commit();
                return null;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Unable to append audit events", exception);
            }
        });
    }

    /**
     * Appends one event to the caller's transaction. This method deliberately does not commit.
     */
    public void append(Connection connection, AuditEvent event) {
        appendBatch(connection, List.of(Objects.requireNonNull(event, "Audit event must not be null")));
    }

    /**
     * Appends events to the caller's transaction. This method deliberately does not commit or rollback.
     */
    public void appendBatch(Connection connection, List<AuditEvent> events) {
        Objects.requireNonNull(connection, "Connection must not be null");
        Objects.requireNonNull(events, "Events must not be null");
        try {
            if (connection.getAutoCommit()) {
                throw new IllegalArgumentException("Caller-owned audit transactions must disable auto-commit");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to inspect audit transaction mode", exception);
        }
        if (events.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            for (AuditEvent event : events) {
                bind(statement, Objects.requireNonNull(event, "Audit event must not be null"));
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to append audit events", exception);
        }
    }

    public Optional<AuditEvent> findByEventId(UUID eventId) {
        return findStoredByEventId(eventId).map(AuditStoredEvent::event);
    }

    public Optional<AuditStoredEvent> findStoredByEventId(UUID eventId) {
        Objects.requireNonNull(eventId, "Event id must not be null");
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT sequence, " + SELECT_COLUMNS + " FROM audit_events WHERE event_id = ?")) {
                statement.setString(1, eventId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(mapStored(resultSet)) : Optional.empty();
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to find audit event", exception);
            }
        });
    }

    List<AuditEvent> findAll() {
        return findAllStored().stream().map(AuditStoredEvent::event).toList();
    }

    List<AuditStoredEvent> findAllStored() {
        return database.withConnection(connection -> {
            List<AuditStoredEvent> events = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT sequence, " + SELECT_COLUMNS + " FROM audit_events ORDER BY sequence ASC");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    events.add(mapStored(resultSet));
                }
                return List.copyOf(events);
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to list audit events", exception);
            }
        });
    }

    public AuditPage query(AuditQuery query) {
        Objects.requireNonNull(query, "Audit query must not be null");
        return database.withConnection(connection -> {
            StringBuilder sql = new StringBuilder("SELECT sequence, ").append(SELECT_COLUMNS)
                    .append(" FROM audit_events WHERE 1 = 1");
            List<Object> parameters = new ArrayList<>();
            if (query.cursor() != null) {
                sql.append(" AND sequence < ?");
                parameters.add(query.cursor());
            }
            if (query.action() != null) {
                sql.append(" AND action = ?");
                parameters.add(query.action());
            }
            if (query.actorUuid() != null) {
                sql.append(" AND actor_uuid = ?");
                parameters.add(query.actorUuid().toString());
            }
            if (query.occurredSinceMs() != null) {
                sql.append(" AND occurred_at_ms >= ?");
                parameters.add(query.occurredSinceMs());
            }
            sql.append(" ORDER BY sequence DESC LIMIT ?");
            parameters.add(query.limit() + 1);
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int index = 0; index < parameters.size(); index++) {
                    statement.setObject(index + 1, parameters.get(index));
                }
                List<AuditStoredEvent> events = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        events.add(mapStored(resultSet));
                    }
                }
                Long nextCursor = null;
                if (events.size() > query.limit()) {
                    events.remove(query.limit().intValue());
                    nextCursor = events.getLast().sequence();
                }
                return new AuditPage(events, nextCursor);
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to query audit events", exception);
            }
        });
    }

    public int deleteOccurredBefore(long occurredBeforeMs, int limit) {
        if (occurredBeforeMs < 0) {
            throw new IllegalArgumentException("Retention timestamp must not be negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("Retention batch limit must be positive");
        }
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM audit_events WHERE sequence IN ("
                            + "SELECT sequence FROM audit_events WHERE occurred_at_ms < ? "
                            + "ORDER BY occurred_at_ms ASC, sequence ASC LIMIT ?" + ")")) {
                statement.setLong(1, occurredBeforeMs);
                statement.setInt(2, limit);
                return statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to delete retained audit events", exception);
            }
        });
    }

    public int deleteBefore(long occurredBeforeMs, int limit) {
        return deleteOccurredBefore(occurredBeforeMs, limit);
    }

    private void bind(PreparedStatement statement, AuditEvent event) throws SQLException {
        statement.setString(1, event.eventId().toString());
        statement.setString(2, event.runtimeId().toString());
        statement.setString(3, event.correlationId().toString());
        statement.setLong(4, event.occurredAtMs());
        statement.setLong(5, event.recordedAtMs());
        statement.setString(6, event.source().code());
        statement.setString(7, event.action());
        statement.setString(8, event.outcome().code());
        if (event.actor().uuid() == null) {
            statement.setNull(9, java.sql.Types.VARCHAR);
        } else {
            statement.setString(9, event.actor().uuid().toString());
        }
        statement.setString(10, event.actor().type().code());
        if (event.actor().displayName() == null) {
            statement.setNull(11, java.sql.Types.VARCHAR);
        } else {
            statement.setString(11, event.actor().displayName());
        }
        if (event.target() == null) {
            statement.setNull(12, java.sql.Types.VARCHAR);
            statement.setNull(13, java.sql.Types.VARCHAR);
        } else {
            statement.setString(12, event.target().type());
            statement.setString(13, event.target().id());
        }
        if (event.reasonCode() == null) {
            statement.setNull(14, java.sql.Types.VARCHAR);
        } else {
            statement.setString(14, event.reasonCode());
        }
        statement.setInt(15, event.payloadVersion());
        statement.setString(16, event.payload().json());
    }

    private AuditStoredEvent mapStored(ResultSet resultSet) throws SQLException {
        String actorUuid = resultSet.getString("actor_uuid");
        String targetType = resultSet.getString("target_type");
        String targetId = resultSet.getString("target_id");
        Target target = targetType == null && targetId == null ? null : new Target(targetType, targetId);
        AuditEvent event = new AuditEvent(
                UUID.fromString(resultSet.getString("event_id")),
                UUID.fromString(resultSet.getString("runtime_id")),
                UUID.fromString(resultSet.getString("correlation_id")),
                resultSet.getLong("occurred_at_ms"),
                resultSet.getLong("recorded_at_ms"),
                new Source(resultSet.getString("source")),
                resultSet.getString("action"),
                new Outcome(resultSet.getString("outcome")),
                new Actor(ActorType.fromCode(resultSet.getString("actor_type")),
                        actorUuid == null ? null : UUID.fromString(actorUuid),
                        resultSet.getString("actor_display_name")),
                target,
                resultSet.getString("reason_code"),
                resultSet.getInt("payload_version"),
                AuditPayload.fromStoredJson(resultSet.getString("action"), resultSet.getString("payload_json")));
        return new AuditStoredEvent(resultSet.getLong("sequence"), event, event.payload().isLegacyOpaque());
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            original.addSuppressed(rollbackException);
        }
    }

}
