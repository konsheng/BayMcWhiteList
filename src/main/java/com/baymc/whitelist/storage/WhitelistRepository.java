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
 * 管理白名单状态和审计日志的 SQL 仓库
 */
public final class WhitelistRepository {
    private static final SqlTemplates REPOSITORY_SQL = SqlTemplates.load("sql/repository.sql");

    private final DatabaseManager database;
    private final String serverName;
    private final Map<String, String> sqlPlaceholders;

    /**
     * 将仓库调用绑定到当前数据库管理器和服务器名
     */
    public WhitelistRepository(DatabaseManager database, String serverName) {
        this.database = database;
        this.serverName = serverName;
        this.sqlPlaceholders = Map.of(
                "players_table", database.playersTable(),
                "logs_table", database.logsTable()
        );
    }

    /**
     * 检查标准玩家标识是否存在于白名单表中
     */
    public boolean isWhitelisted(String playerKey) throws SQLException {
        String sql = sql("is_whitelisted");
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
        String sql = sql("find_by_key");
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
        String sql = sql("find_by_name");
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readRecord(resultSet)) : Optional.empty();
            }
        }
    }

    /**
     * 验证成功后插入或刷新白名单记录
     */
    public void upsert(PlayerIdentity identity, String code, LocalDate issueDate, LocalDateTime usedAt) throws SQLException {
        String sql = sql("upsert_player");

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
     * 管理员手动添加白名单记录, 不写入邀请码和最后进入时间
     *
     * @return 插入成功返回 true; 如果记录已存在返回 false
     */
    public boolean insertManual(PlayerIdentity identity, LocalDate issueDate, LocalDateTime usedAt) throws SQLException {
        String sql = sql("insert_manual_player");

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identity.key());
            statement.setString(2, identity.uuid() == null ? null : identity.uuid().toString());
            statement.setString(3, identity.name());
            statement.setDate(4, Date.valueOf(issueDate));
            statement.setTimestamp(5, Timestamp.valueOf(usedAt));
            statement.setString(6, serverName);
            return statement.executeUpdate() > 0;
        }
    }

    /**
     * 根据标准玩家标识移除白名单记录
     */
    public boolean removeByKey(String playerKey) throws SQLException {
        String sql = sql("remove_by_key");
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
        String sql = sql("update_last_seen");
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
        String sql = sql("insert_log");

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, truncate(entry.playerKey(), StorageLimits.PLAYER_KEY));
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
     * 渲染仓库 SQL 模板
     */
    private String sql(String name) {
        return REPOSITORY_SQL.render(name, sqlPlaceholders);
    }

    /**
     * 保证写入审计日志的可变文本字段不超过表结构限制
     */
    static String truncate(String input, int maxLength) {
        if (input == null || input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength);
    }
}
