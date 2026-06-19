package com.baymc.whitelist.storage;

import com.baymc.whitelist.identity.PlayerIdentity;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Manages whitelist state and audit logs in MySQL.
 */
public final class WhitelistRepository {
    private static final SqlTemplates REPOSITORY_SQL = SqlTemplates.load("sql/repository.sql");

    private final DatabaseManager database;
    private final String serverName;
    private final Map<String, String> sqlPlaceholders;

    public WhitelistRepository(DatabaseManager database, String serverName) {
        this.database = database;
        this.serverName = serverName;
        this.sqlPlaceholders = Map.of(
                "players_table", database.playersTable(),
                "logs_table", database.logsTable()
        );
    }

    public boolean isWhitelisted(String playerUuid) throws SQLException {
        String sql = sql("is_whitelisted");
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    public Optional<WhitelistRecord> findByUuid(String playerUuid) throws SQLException {
        String sql = sql("find_by_uuid");
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public void upsert(PlayerIdentity identity, String code, LocalDate issueDate, LocalDateTime usedAt) throws SQLException {
        String sql = sql("upsert_player");

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identity.uuidText());
            statement.setString(2, identity.name());
            statement.setString(3, code);
            statement.setDate(4, Date.valueOf(issueDate));
            statement.setTimestamp(5, Timestamp.valueOf(usedAt));
            statement.setString(6, serverName);
            statement.setTimestamp(7, Timestamp.valueOf(usedAt));
            statement.executeUpdate();
        }
    }

    public boolean insertManual(PlayerIdentity identity, LocalDate issueDate, LocalDateTime usedAt) throws SQLException {
        String sql = sql("insert_manual_player");

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identity.uuidText());
            statement.setString(2, identity.name());
            statement.setDate(3, Date.valueOf(issueDate));
            statement.setTimestamp(4, Timestamp.valueOf(usedAt));
            statement.setString(5, serverName);
            return statement.executeUpdate() > 0;
        }
    }

    public boolean removeByUuid(String playerUuid) throws SQLException {
        String sql = sql("remove_by_uuid");
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid);
            return statement.executeUpdate() > 0;
        }
    }

    public void updateLastSeen(String playerUuid, LocalDateTime lastSeenAt) throws SQLException {
        String sql = sql("update_last_seen");
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(lastSeenAt));
            statement.setString(2, playerUuid);
            statement.executeUpdate();
        }
    }

    public void log(WhitelistLogEntry entry) throws SQLException {
        String sql = sql("insert_log");

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, truncate(entry.playerUuid(), StorageLimits.PLAYER_UUID));
            statement.setString(2, truncate(entry.playerName(), StorageLimits.PLAYER_NAME));
            statement.setString(3, truncate(entry.action(), StorageLimits.ACTION));
            statement.setString(4, truncate(entry.code(), StorageLimits.CODE));
            statement.setString(5, truncate(entry.serverName(), StorageLimits.SERVER_NAME));
            statement.setString(6, truncate(entry.ip(), StorageLimits.IP));
            statement.setString(7, truncate(entry.message(), StorageLimits.MESSAGE));
            statement.setTimestamp(8, Timestamp.valueOf(entry.createdAt()));
            statement.executeUpdate();
        }
    }

    private static WhitelistRecord readRecord(ResultSet resultSet) throws SQLException {
        Timestamp usedAt = resultSet.getTimestamp("used_at");
        Timestamp lastSeenAt = resultSet.getTimestamp("last_seen_at");
        Date issueDate = resultSet.getDate("issue_date");
        return new WhitelistRecord(
                resultSet.getString("player_uuid"),
                resultSet.getString("player_name"),
                resultSet.getString("code"),
                issueDate == null ? null : issueDate.toLocalDate(),
                usedAt == null ? null : usedAt.toLocalDateTime(),
                resultSet.getString("source_server"),
                lastSeenAt == null ? null : lastSeenAt.toLocalDateTime()
        );
    }

    private String sql(String name) {
        return REPOSITORY_SQL.render(name, sqlPlaceholders);
    }

    static String truncate(String input, int maxLength) {
        if (input == null || input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength);
    }
}
