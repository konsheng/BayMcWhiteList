package com.baymc.whitelist.storage;

import com.baymc.whitelist.identity.PlayerIdentity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 通过当前数据库后端读写白名单状态和审计日志
 *
 * <p>仓库层只接收已经解析好的玩家 UUID, 不负责按玩家名推断身份
 * 管理员命令和登录监听器必须在进入这里前完成 UUID 来源选择或 Mojang 档案解析
 *
 * <p>所有外部文本在写入审计日志前会按 StorageLimits 截断
 * 避免超长邀请码, 玩家名或错误消息导致日志写入失败
 */
public final class WhitelistRepository {
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
            statement.setString(4, issueDate.toString());
            statement.setString(5, dateTime(usedAt));
            statement.setString(6, serverName);
            statement.setString(7, dateTime(usedAt));
            statement.executeUpdate();
        }
    }

    public boolean insertManual(PlayerIdentity identity, LocalDate issueDate, LocalDateTime usedAt) throws SQLException {
        String sql = sql("insert_manual_player");

        try (Connection connection = database.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identity.uuidText());
            statement.setString(2, identity.name());
            statement.setString(3, issueDate.toString());
            statement.setString(4, dateTime(usedAt));
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
            statement.setString(1, dateTime(lastSeenAt));
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
            statement.setString(8, dateTime(entry.createdAt()));
            statement.executeUpdate();
        }
    }

    private static WhitelistRecord readRecord(ResultSet resultSet) throws SQLException {
        return new WhitelistRecord(
                resultSet.getString("player_uuid"),
                resultSet.getString("player_name"),
                resultSet.getString("code"),
                readDate(resultSet, "issue_date"),
                readDateTime(resultSet, "used_at"),
                resultSet.getString("source_server"),
                readDateTime(resultSet, "last_seen_at")
        );
    }

    private String sql(String name) {
        return database.repositorySql().render(name, sqlPlaceholders);
    }

    private static LocalDate readDate(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value.trim().substring(0, 10));
    }

    private static LocalDateTime readDateTime(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(value.trim().replace(' ', 'T'));
    }

    private static String dateTime(LocalDateTime value) {
        return value.toString().replace('T', ' ');
    }

    static String truncate(String input, int maxLength) {
        if (input == null || input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength);
    }
}
