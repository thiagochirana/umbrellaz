package dev.chirana.umbrellaz.lock;

import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class LockRepository {
    private final SQLiteDatabase database;

    public LockRepository(SQLiteDatabase database) {
        this.database = database;
    }

    public void recordPlacement(PlacementProvenance placement) {
        database.withConnection(connection -> {
            boolean committed = false;
            try {
                connection.setAutoCommit(false);
                Optional<PlacementProvenance> existing = findPlacement(connection, placement.target());
                if (existing.isPresent() && existing.get().generationId().equals(placement.generationId())) {
                    if (!existing.get().placedBy().equals(placement.placedBy())
                            || existing.get().blockType() != placement.blockType()) {
                        throw new IllegalArgumentException("Placement generation is immutable");
                    }
                } else {
                    if (existing.isPresent()) {
                        deleteLogicalLockers(connection, existing.get());
                        deletePlacement(connection, existing.get());
                    }
                    insertPlacement(connection, placement);
                }
                connection.commit();
                committed = true;
                return null;
            } catch (SQLException exception) {
                rollbackBeforeCommit(connection, committed, exception);
                throw failure("record placement", exception);
            } catch (RuntimeException exception) {
                rollbackBeforeCommit(connection, committed, exception);
                throw exception;
            }
        });
    }

    public Optional<PlacementProvenance> findPlacement(LockTarget target) {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT generation_id, placed_by, block_type, placed_at FROM lock_placements "
                            + "WHERE world_id = ? AND dimension_id = ? AND x = ? AND y = ? AND z = ?")) {
                bindTarget(statement, target, 1);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(readPlacement(resultSet, target)) : Optional.empty();
                }
            } catch (SQLException exception) {
                throw failure("find placement", exception);
            }
        });
    }

    public Optional<PlacementProvenance> findPlacement(LockTarget target, UUID generationId) {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT generation_id, placed_by, block_type, placed_at FROM lock_placements "
                            + "WHERE world_id = ? AND dimension_id = ? AND x = ? AND y = ? AND z = ? AND generation_id = ?")) {
                bindTarget(statement, target, 1);
                statement.setString(6, generationId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(readPlacement(resultSet, target)) : Optional.empty();
                }
            } catch (SQLException exception) {
                throw failure("find placement", exception);
            }
        });
    }

    public List<PlacementProvenance> findAllPlacements() {
        return database.withConnection(connection -> {
            List<PlacementProvenance> placements = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT world_id, dimension_id, x, y, z, generation_id, placed_by, block_type, placed_at "
                            + "FROM lock_placements ORDER BY world_id, dimension_id, x, y, z");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    LockTarget target = new LockTarget(new WorldIdentity(resultSet.getString("world_id")),
                            new DimensionIdentity(resultSet.getString("dimension_id")),
                            new BlockPosition(resultSet.getInt("x"), resultSet.getInt("y"),
                                    resultSet.getInt("z")));
                    placements.add(readPlacement(resultSet, target));
                }
            } catch (SQLException exception) {
                throw failure("list placement provenance", exception);
            }
            return placements;
        });
    }

    public void removePlacement(LockTarget target, UUID generationId) {
        invalidatePlacement(target, generationId);
    }

    public void invalidatePlacement(LockTarget target, UUID generationId) {
        invalidatePlacementIfCurrent(target, generationId);
    }

    public boolean invalidatePlacementIfCurrent(LockTarget target, UUID generationId) {
        return database.withConnection(connection -> {
            boolean committed = false;
            try {
                connection.setAutoCommit(false);
                Optional<PlacementProvenance> placement = findPlacement(connection, target, generationId);
                boolean invalidated = placement.isPresent();
                if (placement.isPresent()) {
                    deleteLogicalLockers(connection, placement.get());
                    deletePlacement(connection, placement.get());
                }
                connection.commit();
                committed = true;
                return invalidated;
            } catch (SQLException exception) {
                rollbackBeforeCommit(connection, committed, exception);
                throw failure("invalidate placement", exception);
            } catch (RuntimeException exception) {
                rollbackBeforeCommit(connection, committed, exception);
                throw exception;
            }
        });
    }

    public boolean restoreInvalidatedState(Collection<PlacementProvenance> placements,
                                           LockerMetadata locker) {
        if (placements.isEmpty()) {
            throw new IllegalArgumentException("placements must not be empty");
        }
        return database.withConnection(connection -> {
            boolean committed = false;
            try {
                connection.setAutoCommit(false);
                List<PlacementProvenance> expected = List.copyOf(placements);
                if (locker != null && locker.members().stream().anyMatch(member -> expected.stream().noneMatch(
                        placement -> placement.target().equals(member.target())
                                && placement.generationId().equals(member.generationId())
                                && placement.placedBy().equals(member.placedBy())
                                && placement.blockType() == member.blockType()))) {
                    connection.rollback();
                    return false;
                }
                for (PlacementProvenance placement : expected) {
                    Optional<PlacementProvenance> current = findPlacement(connection, placement.target());
                    if (current.isPresent() && !sameGeneration(current.get(), placement)) {
                        connection.rollback();
                        return false;
                    }
                    if (current.isEmpty()) {
                        insertPlacement(connection, placement);
                    }
                }
                if (locker != null && !lockerExists(connection, locker.lockerId())) {
                    validateMembers(connection, locker);
                    insertLocker(connection, locker);
                    insertMembers(connection, locker);
                }
                connection.commit();
                committed = true;
                return true;
            } catch (SQLException exception) {
                rollbackBeforeCommit(connection, committed, exception);
                throw failure("restore invalidated lock state", exception);
            } catch (RuntimeException exception) {
                rollbackBeforeCommit(connection, committed, exception);
                throw exception;
            }
        });
    }

    public void createLocker(LockerMetadata locker) {
        database.withConnection(connection -> {
            boolean committed = false;
            try {
                connection.setAutoCommit(false);
                validateMembers(connection, locker);
                insertLocker(connection, locker);
                insertMembers(connection, locker);
                connection.commit();
                committed = true;
                return null;
            } catch (SQLException exception) {
                rollbackBeforeCommit(connection, committed, exception);
                throw failure("create locker", exception);
            } catch (RuntimeException exception) {
                rollbackBeforeCommit(connection, committed, exception);
                throw exception;
            }
        });
    }

    public void removeLocker(UUID lockerId) {
        removeLockerIfMembers(lockerId, List.of());
    }

    public boolean removeLockerIfMembers(UUID lockerId, Collection<PlacementProvenance> expectedMembers) {
        return database.withConnection(connection -> {
            boolean committed = false;
            try {
                connection.setAutoCommit(false);
                if (!expectedMembers.isEmpty()) {
                    for (PlacementProvenance member : expectedMembers) {
                        if (!hasLockerMember(connection, lockerId, member)) {
                            connection.commit();
                            committed = true;
                            return false;
                        }
                    }
                }
                try (PreparedStatement members = connection.prepareStatement(
                        "DELETE FROM locker_members WHERE locker_id = ?");
                     PreparedStatement locker = connection.prepareStatement(
                             "DELETE FROM lockers WHERE locker_id = ?")) {
                    members.setString(1, lockerId.toString());
                    members.executeUpdate();
                    locker.setString(1, lockerId.toString());
                    locker.executeUpdate();
                }
                connection.commit();
                committed = true;
                return true;
            } catch (SQLException exception) {
                rollbackBeforeCommit(connection, committed, exception);
                throw failure("remove locker", exception);
            } catch (RuntimeException exception) {
                rollbackBeforeCommit(connection, committed, exception);
                throw exception;
            }
        });
    }

    private boolean hasLockerMember(Connection connection, UUID lockerId,
                                    PlacementProvenance member) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM locker_members WHERE locker_id = ? AND world_id = ? AND dimension_id = ? "
                        + "AND x = ? AND y = ? AND z = ? AND generation_id = ?")) {
            statement.setString(1, lockerId.toString());
            bindTarget(statement, member.target(), 2);
            statement.setString(7, member.generationId().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    public Optional<LockerMetadata> findLocker(LockTarget target) {
        return findAllLockers().stream().filter(locker -> locker.members().stream()
                .anyMatch(member -> member.target().equals(target))).findFirst();
    }

    public List<LockerMetadata> findAllLockers() {
        return database.withConnection(connection -> {
            Map<UUID, LockerRow> rows = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(lockerQuery()
                    + " ORDER BY l.created_at, m.world_id, m.dimension_id, m.x, m.y, m.z");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID lockerId = UUID.fromString(resultSet.getString("locker_id"));
                    LockerRow row = rows.computeIfAbsent(lockerId,
                            ignored -> new LockerRow(readMetadataWithoutMembers(resultSet)));
                    row.members.add(readMember(resultSet, lockerId));
                }
            } catch (SQLException exception) {
                throw failure("list lockers", exception);
            }
            List<LockerMetadata> lockers = new ArrayList<>();
            for (LockerRow row : rows.values()) {
                lockers.add(row.metadata.withMembers(row.members));
            }
            return lockers;
        });
    }

    private Optional<PlacementProvenance> findPlacement(Connection connection, LockTarget target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT generation_id, placed_by, block_type, placed_at FROM lock_placements "
                        + "WHERE world_id = ? AND dimension_id = ? AND x = ? AND y = ? AND z = ?")) {
            bindTarget(statement, target, 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readPlacement(resultSet, target)) : Optional.empty();
            }
        }
    }

    private Optional<PlacementProvenance> findPlacement(Connection connection, LockTarget target,
                                                         UUID generationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT generation_id, placed_by, block_type, placed_at FROM lock_placements "
                        + "WHERE world_id = ? AND dimension_id = ? AND x = ? AND y = ? AND z = ? AND generation_id = ?")) {
            bindTarget(statement, target, 1);
            statement.setString(6, generationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readPlacement(resultSet, target)) : Optional.empty();
            }
        }
    }

    private void insertPlacement(Connection connection, PlacementProvenance placement) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lock_placements(world_id, dimension_id, x, y, z, generation_id, placed_by, block_type, placed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            bindTarget(statement, placement.target(), 1);
            statement.setString(6, placement.generationId().toString());
            statement.setString(7, placement.placedBy().toString());
            statement.setString(8, placement.blockType().name());
            statement.setString(9, placement.placedAt().toString());
            statement.executeUpdate();
        }
    }

    private boolean lockerExists(Connection connection, UUID lockerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM lockers WHERE locker_id = ?")) {
            statement.setString(1, lockerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean sameGeneration(PlacementProvenance first, PlacementProvenance second) {
        return first.generationId().equals(second.generationId())
                && first.placedBy().equals(second.placedBy())
                && first.blockType() == second.blockType();
    }

    private void deletePlacement(Connection connection, PlacementProvenance placement) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM lock_placements WHERE world_id = ? AND dimension_id = ? AND x = ? AND y = ? AND z = ?")) {
            bindTarget(statement, placement.target(), 1);
            statement.executeUpdate();
        }
    }

    private void deleteLogicalLockers(Connection connection, PlacementProvenance placement) throws SQLException {
        List<UUID> lockerIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT locker_id FROM locker_members WHERE world_id = ? AND dimension_id = ? "
                        + "AND x = ? AND y = ? AND z = ? AND generation_id = ?")) {
            bindTarget(statement, placement.target(), 1);
            statement.setString(6, placement.generationId().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    lockerIds.add(UUID.fromString(resultSet.getString("locker_id")));
                }
            }
        }
        try (PreparedStatement members = connection.prepareStatement("DELETE FROM locker_members WHERE locker_id = ?");
             PreparedStatement lockers = connection.prepareStatement("DELETE FROM lockers WHERE locker_id = ?")) {
            for (UUID lockerId : lockerIds) {
                members.setString(1, lockerId.toString());
                members.executeUpdate();
                lockers.setString(1, lockerId.toString());
                lockers.executeUpdate();
            }
        }
    }

    private void validateMembers(Connection connection, LockerMetadata locker) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT placed_by, block_type FROM lock_placements WHERE world_id = ? AND dimension_id = ? "
                        + "AND x = ? AND y = ? AND z = ? AND generation_id = ?")) {
            for (LockerMember member : locker.members()) {
                bindTarget(statement, member.target(), 1);
                statement.setString(6, member.generationId().toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalArgumentException("No matching placement provenance for locker member");
                    }
                    UUID placedBy = UUID.fromString(resultSet.getString("placed_by"));
                    LockBlockType blockType = LockBlockType.valueOf(resultSet.getString("block_type"));
                    if (!locker.ownerUuid().equals(member.placedBy()) || !placedBy.equals(member.placedBy())
                            || blockType != member.blockType()) {
                        throw new IllegalArgumentException("Locker member provenance does not match its owner or block type");
                    }
                }
            }
        }
    }

    private void insertLocker(Connection connection, LockerMetadata locker) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lockers(locker_id, owner_uuid, password_salt, password_hash, password_algorithm, "
                        + "password_work_factor, created_at, updated_at, generation) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, locker.lockerId().toString());
            statement.setString(2, locker.ownerUuid().toString());
            statement.setBytes(3, locker.password().salt());
            statement.setBytes(4, locker.password().hash());
            statement.setString(5, locker.password().algorithm());
            statement.setInt(6, locker.password().workFactor());
            statement.setString(7, locker.createdAt().toString());
            statement.setString(8, locker.updatedAt().toString());
            statement.setLong(9, locker.generation());
            statement.executeUpdate();
        }
    }

    private void insertMembers(Connection connection, LockerMetadata locker) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO locker_members(locker_id, world_id, dimension_id, x, y, z, generation_id, "
                        + "expected_block_type, installer_uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (LockerMember member : locker.members()) {
                statement.setString(1, locker.lockerId().toString());
                bindTarget(statement, member.target(), 2);
                statement.setString(7, member.generationId().toString());
                statement.setString(8, member.blockType().name());
                statement.setString(9, member.placedBy().toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private String lockerQuery() {
        return "SELECT l.locker_id, l.owner_uuid, l.password_salt, l.password_hash, l.password_algorithm, "
                + "l.password_work_factor, l.created_at, l.updated_at, l.generation, m.world_id, m.dimension_id, "
                + "m.x, m.y, m.z, m.generation_id, m.expected_block_type, m.installer_uuid "
                + "FROM lockers l JOIN locker_members m ON m.locker_id = l.locker_id";
    }

    private LockerMetadata readMetadataWithoutMembers(ResultSet resultSet) {
        try {
            return new LockerMetadata(UUID.fromString(resultSet.getString("locker_id")),
                    UUID.fromString(resultSet.getString("owner_uuid")),
                    new PasswordHash(resultSet.getBytes("password_salt"), resultSet.getBytes("password_hash"),
                            resultSet.getString("password_algorithm"), resultSet.getInt("password_work_factor")),
                    Instant.parse(resultSet.getString("created_at")),
                    Instant.parse(resultSet.getString("updated_at")),
                    resultSet.getLong("generation"), Set.of(readMember(resultSet,
                            UUID.fromString(resultSet.getString("locker_id")))));
        } catch (SQLException exception) {
            throw failure("map locker", exception);
        }
    }

    private LockerMember readMember(ResultSet resultSet, UUID lockerId) throws SQLException {
        return new LockerMember(lockerId,
                new LockTarget(new WorldIdentity(resultSet.getString("world_id")),
                        new DimensionIdentity(resultSet.getString("dimension_id")),
                        new BlockPosition(resultSet.getInt("x"), resultSet.getInt("y"), resultSet.getInt("z"))),
                UUID.fromString(resultSet.getString("generation_id")),
                UUID.fromString(resultSet.getString("installer_uuid")),
                LockBlockType.valueOf(resultSet.getString("expected_block_type")));
    }

    private PlacementProvenance readPlacement(ResultSet resultSet, LockTarget target) throws SQLException {
        return new PlacementProvenance(target, UUID.fromString(resultSet.getString("generation_id")),
                UUID.fromString(resultSet.getString("placed_by")),
                LockBlockType.valueOf(resultSet.getString("block_type")),
                Instant.parse(resultSet.getString("placed_at")));
    }

    private void bindTarget(PreparedStatement statement, LockTarget target, int offset) throws SQLException {
        statement.setString(offset, target.world().value());
        statement.setString(offset + 1, target.dimension().value());
        statement.setInt(offset + 2, target.position().x());
        statement.setInt(offset + 3, target.position().y());
        statement.setInt(offset + 4, target.position().z());
    }

    private void rollbackBeforeCommit(Connection connection, boolean committed, Throwable original) {
        if (committed) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            original.addSuppressed(rollbackException);
        }
    }

    private IllegalStateException failure(String operation, SQLException exception) {
        return new IllegalStateException("Unable to " + operation, exception);
    }

    private static final class LockerRow {
        private final LockerMetadata metadata;
        private final Set<LockerMember> members = new LinkedHashSet<>();

        private LockerRow(LockerMetadata metadata) {
            this.metadata = metadata;
        }
    }
}
