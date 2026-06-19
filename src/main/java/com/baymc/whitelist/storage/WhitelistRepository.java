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
 * <p>仓库层只接收已经解析好的玩家 UUID, 不负责按玩家名推断身份。
 * 管理员命令和登录监听器必须在进入这里前完成 UUID 来源选择或 Mojang 档案解析。
 *
 * <p>所有外部文本在写入审计日志前会按 StorageLimits 截断,
 * 避免超长邀请码, 玩家名或错误消息导致日志写入失败。
 */
public final class WhitelistRepository {
    private final DatabaseManager database;
    private final String serverName;
    private final Map<String, String> sqlPlaceholders;

    /**
     * 创建绑定到指定数据库管理器和服务器名的仓库
     *
     * @param database 当前运行期数据库管理器
     * @param serverName 写入白名单来源和审计日志的服务器名
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
     * 判断指定 UUID 是否已有白名单记录
     *
     * @param playerUuid 标准 UUID 文本
     * @return 如果数据库中存在该 UUID 的白名单记录则返回 true
     * @throws SQLException 当数据库查询失败时抛出
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
     *
     * <p>UUID 是白名单唯一键, 名称只作为展示字段和管理员确认信息
     *
     * @param playerUuid 标准 UUID 文本
     * @return 查找到的白名单记录, 不存在时为空
     * @throws SQLException 当数据库查询失败时抛出
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
     *
     * @param identity 已解析的玩家身份
     * @param code 标准化邀请码
     * @param issueDate 邀请码签发日期
     * @param usedAt 验证成功时间
     * @throws SQLException 当数据库写入失败时抛出
     */
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

    /**
     * 管理员手动添加白名单记录, 已存在时返回 false
     *
     * @param identity 已解析的玩家身份
     * @param issueDate 手动添加记录的日期
     * @param usedAt 手动添加记录的时间
     * @return 插入成功返回 true, 已存在返回 false
     * @throws SQLException 当数据库写入失败时抛出
     */
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

    /**
     * 按 UUID 删除白名单记录
     *
     * @param playerUuid 标准 UUID 文本
     * @return 删除到记录时返回 true, 不存在时返回 false
     * @throws SQLException 当数据库删除失败时抛出
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
     *
     * @param playerUuid 标准 UUID 文本
     * @param lastSeenAt 本次放行时间
     * @throws SQLException 当数据库更新失败时抛出
     */
    public void updateLastSeen(String playerUuid, LocalDateTime lastSeenAt) throws SQLException {
        String sql = sql("update_last_seen");
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, dateTime(lastSeenAt));
            statement.setString(2, playerUuid);
            statement.executeUpdate();
        }
    }

    /**
     * 写入审计日志, 并在入库前按字段上限截断外部输入
     *
     * @param entry 审计日志条目
     * @throws SQLException 当数据库写入失败时抛出
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
            statement.setString(8, dateTime(entry.createdAt()));
            statement.executeUpdate();
        }
    }

    /**
     * 从查询结果构建不可变白名单记录
     */
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

    /**
     * 渲染仓库 SQL 模板
     */
    private String sql(String name) {
        return database.repositorySql().render(name, sqlPlaceholders);
    }

    /**
     * 以文本形式读取日期, 避免不同 JDBC 驱动对 DATE 的内部存储格式差异
     */
    private static LocalDate readDate(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value.trim().substring(0, 10));
    }

    /**
     * 以文本形式读取时间戳, 同时兼容 MySQL 空格分隔和 Java ISO T 分隔格式
     */
    private static LocalDateTime readDateTime(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(value.trim().replace(' ', 'T'));
    }

    /**
     * 以 MySQL DATETIME 和 SQLite TEXT 都能直接保存的格式写入时间戳
     */
    private static String dateTime(LocalDateTime value) {
        return value.toString().replace('T', ' ');
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
