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
import java.util.Locale;
import java.util.Optional;

public final class WhitelistRepository {
    private final DatabaseManager database;
    private final String serverName;

    public WhitelistRepository(DatabaseManager database, String serverName) {
        this.database = database;
        this.serverName = serverName;
    }

    public boolean isWhitelisted(String playerKey) throws SQLException {
        String sql = "SELECT 1 FROM " + database.playersTable() + " WHERE player_key = ? LIMIT 1";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    public Optional<WhitelistRecord> findByKey(String playerKey) throws SQLException {
        String sql = "SELECT * FROM " + database.playersTable() + " WHERE player_key = ? LIMIT 1";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public Optional<WhitelistRecord> findByName(String playerName) throws SQLException {
        String sql = "SELECT * FROM " + database.playersTable()
                + " WHERE LOWER(player_name) = ? ORDER BY used_at DESC LIMIT 1";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName.toLowerCase(Locale.ROOT));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public void upsert(PlayerIdentity identity, String code, LocalDate issueDate, LocalDateTime usedAt) throws SQLException {
        String sql = """
                INSERT INTO %s
                  (player_key, player_uuid, player_name, code, issue_date, used_at, source_server, last_seen_at)
                VALUES
                  (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  player_uuid = VALUES(player_uuid),
                  player_name = VALUES(player_name),
                  code = VALUES(code),
                  issue_date = VALUES(issue_date),
                  used_at = VALUES(used_at),
                  source_server = VALUES(source_server),
                  last_seen_at = VALUES(last_seen_at)
                """.formatted(database.playersTable());

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identity.key());
            statement.setString(2, identity.uuid() == null ? null : identity.uuid().toString());
            statement.setString(3, identity.name());
            statement.setString(4, code);
            statement.setDate(5, Date.valueOf(issueDate));
            statement.setTimestamp(6, Timestamp.valueOf(usedAt));
            statement.setString(7, serverName);
            statement.setTimestamp(8, Timestamp.valueOf(usedAt));
            statement.executeUpdate();
        }
    }

    public boolean removeByKey(String playerKey) throws SQLException {
        String sql = "DELETE FROM " + database.playersTable() + " WHERE player_key = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerKey);
            return statement.executeUpdate() > 0;
        }
    }

    public void updateLastSeen(String playerKey, LocalDateTime lastSeenAt) throws SQLException {
        String sql = "UPDATE " + database.playersTable() + " SET last_seen_at = ? WHERE player_key = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(lastSeenAt));
            statement.setString(2, playerKey);
            statement.executeUpdate();
        }
    }

    public void log(WhitelistLogEntry entry) throws SQLException {
        String sql = """
                INSERT INTO %s
                  (player_key, player_name, action, code, server_name, ip, message, created_at)
                VALUES
                  (?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(database.logsTable());

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entry.playerKey());
            statement.setString(2, entry.playerName());
            statement.setString(3, entry.action());
            statement.setString(4, entry.code());
            statement.setString(5, entry.serverName());
            statement.setString(6, entry.ip());
            statement.setString(7, truncate(entry.message(), 255));
            statement.setTimestamp(8, Timestamp.valueOf(entry.createdAt()));
            statement.executeUpdate();
        }
    }

    private static WhitelistRecord readRecord(ResultSet resultSet) throws SQLException {
        Timestamp usedAt = resultSet.getTimestamp("used_at");
        Timestamp lastSeenAt = resultSet.getTimestamp("last_seen_at");
        Date issueDate = resultSet.getDate("issue_date");
        return new WhitelistRecord(
                resultSet.getString("player_key"),
                resultSet.getString("player_uuid"),
                resultSet.getString("player_name"),
                resultSet.getString("code"),
                issueDate == null ? null : issueDate.toLocalDate(),
                usedAt == null ? null : usedAt.toLocalDateTime(),
                resultSet.getString("source_server"),
                lastSeenAt == null ? null : lastSeenAt.toLocalDateTime()
        );
    }

    private static String truncate(String input, int maxLength) {
        if (input == null || input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength);
    }
}
