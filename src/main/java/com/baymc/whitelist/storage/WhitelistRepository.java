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

/**
 * 管理白名单状态和审计日志的 SQL 仓库
 */
public final class WhitelistRepository {
    private final DatabaseManager database;
    private final String serverName;

    /**
     * 将仓库调用绑定到当前数据库管理器和服务器名
     */
    public WhitelistRepository(DatabaseManager database, String serverName) {
        this.database = database;
        this.serverName = serverName;
    }

    /**
     * 检查标准玩家标识是否存在于白名单表中
     */
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

    /**
     * 根据标准玩家标识查询一条白名单记录
     */
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

    /**
     * 根据玩家名查询最新的一条匹配记录
     */
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

    /**
     * 验证成功后插入或刷新白名单记录
     */
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

    /**
     * 根据标准玩家标识移除白名单记录
     */
    public boolean removeByKey(String playerKey) throws SQLException {
        String sql = "DELETE FROM " + database.playersTable() + " WHERE player_key = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerKey);
            return statement.executeUpdate() > 0;
        }
    }

    /**
     * 在受保护服务器登录检查时更新最后出现时间
     */
    public void updateLastSeen(String playerKey, LocalDateTime lastSeenAt) throws SQLException {
        String sql = "UPDATE " + database.playersTable() + " SET last_seen_at = ? WHERE player_key = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(lastSeenAt));
            statement.setString(2, playerKey);
            statement.executeUpdate();
        }
    }

    /**
     * 向日志表追加一条审计记录
     */
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

    /**
     * 将当前 ResultSet 行映射为 WhitelistRecord
     */
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

    /**
     * 保证日志消息字段不超过表结构限制
     */
    private static String truncate(String input, int maxLength) {
        if (input == null || input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength);
    }
}
