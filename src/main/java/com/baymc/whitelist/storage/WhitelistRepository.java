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
 * 通过 MySQL 读写白名单状态和审计日志
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

    /**
     * 判断指定 UUID 是否已有白名单记录
     */
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

    /**
     * 按白名单主键 UUID 查询完整记录
     */
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

    /**
     * 邀请码验证成功后写入或刷新玩家白名单记录
     */
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

    /**
     * 管理员手动添加白名单记录, 已存在时返回 false
     */
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

    /**
     * 按 UUID 删除白名单记录
     */
    public boolean removeByUuid(String playerUuid) throws SQLException {
        String sql = sql("remove_by_uuid");
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid);
            return statement.executeUpdate() > 0;
        }
    }

    /**
     * 受保护服务器放行玩家后刷新最后出现时间
     */
    public void updateLastSeen(String playerUuid, LocalDateTime lastSeenAt) throws SQLException {
        String sql = sql("update_last_seen");
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(lastSeenAt));
            statement.setString(2, playerUuid);
            statement.executeUpdate();
        }
    }

    /**
     * 写入审计日志, 并在入库前按字段上限截断外部输入
     */
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

    /**
     * 从查询结果构建不可变白名单记录
     */
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

    /**
     * 渲染仓库 SQL 模板
     */
    private String sql(String name) {
        return REPOSITORY_SQL.render(name, sqlPlaceholders);
    }

    /**
     * 将审计字段限制到数据库列长度, 空值保持为空
     */
    static String truncate(String input, int maxLength) {
        if (input == null || input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength);
    }
}
