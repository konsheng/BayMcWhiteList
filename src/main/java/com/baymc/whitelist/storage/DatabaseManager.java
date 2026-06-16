package com.baymc.whitelist.storage;

import com.baymc.whitelist.config.PluginConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 持有 HikariCP 数据源, 并负责初始化 MySQL 表结构
 */
public final class DatabaseManager implements AutoCloseable {
    private final PluginConfig.MysqlSettings settings;
    private HikariDataSource dataSource;
    private boolean ready;

    /**
     * 保存已校验的 MySQL 配置, 供后续启动连接池使用
     */
    public DatabaseManager(PluginConfig.MysqlSettings settings) {
        this.settings = settings;
    }

    /**
     * 重新创建连接池, 并确保所需数据表存在
     */
    public synchronized void start() throws SQLException {
        close();

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("BayMcWhiteList-Hikari");
        hikari.setJdbcUrl(jdbcUrl());
        hikari.setUsername(settings.username());
        hikari.setPassword(settings.password());
        hikari.setMaximumPoolSize(settings.maximumPoolSize());
        hikari.setMinimumIdle(settings.minimumIdle());
        hikari.setConnectionTimeout(settings.connectionTimeout());
        hikari.setIdleTimeout(settings.idleTimeout());
        hikari.setMaxLifetime(settings.maxLifetime());
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "250");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikari.addDataSourceProperty("useServerPrepStmts", "true");
        hikari.addDataSourceProperty("useLocalSessionState", "true");
        hikari.addDataSourceProperty("rewriteBatchedStatements", "true");

        try {
            dataSource = new HikariDataSource(hikari);
            initializeSchema();
            ready = true;
        } catch (RuntimeException | SQLException exception) {
            close();
            throw exception;
        }
    }

    /**
     * 判断调用方当前是否可以从连接池借出连接
     */
    public boolean isReady() {
        return ready && dataSource != null && !dataSource.isClosed();
    }

    /**
     * 借出数据库连接; 如果启动未完成则快速失败
     */
    public Connection getConnection() throws SQLException {
        if (!isReady()) {
            throw new SQLException("Database is not ready");
        }
        return dataSource.getConnection();
    }

    /**
     * 返回带引号的白名单玩家表名
     */
    public String playersTable() {
        // table-prefix 在加载配置时已按 [A-Za-z0-9_] 校验
        // 因此加引号后可以安全插入 SQL 标识符
        return quote(settings.tablePrefix() + "whitelist_players");
    }

    /**
     * 返回带引号的审计日志表名
     */
    public String logsTable() {
        return quote(settings.tablePrefix() + "whitelist_logs");
    }

    /**
     * 关闭当前连接池, 并标记数据库不可用
     */
    @Override
    public synchronized void close() {
        ready = false;
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    /**
     * 在数据表不存在时创建插件所需的两张表
     */
    private void initializeSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            String playersTable = playersTable();
            String logsTable = logsTable();

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      player_key VARCHAR(%d) NOT NULL UNIQUE,
                      player_uuid VARCHAR(%d),
                      player_name VARCHAR(%d) NOT NULL,
                      code VARCHAR(%d) NOT NULL,
                      issue_date DATE NOT NULL,
                      used_at DATETIME NOT NULL,
                      source_server VARCHAR(%d),
                      last_seen_at DATETIME,
                      INDEX idx_player_uuid (player_uuid),
                      INDEX idx_player_name (player_name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """.formatted(
                            playersTable,
                            StorageLimits.PLAYER_KEY,
                            StorageLimits.PLAYER_UUID,
                            StorageLimits.PLAYER_NAME,
                            StorageLimits.CODE,
                            StorageLimits.SERVER_NAME
                    ));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      player_key VARCHAR(%d) NOT NULL,
                      player_name VARCHAR(%d) NOT NULL,
                      action VARCHAR(%d) NOT NULL,
                      code VARCHAR(%d),
                      server_name VARCHAR(%d),
                      ip VARCHAR(%d),
                      message VARCHAR(%d),
                      created_at DATETIME NOT NULL,
                      INDEX idx_player_key (player_key),
                      INDEX idx_created_at (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """.formatted(
                            logsTable,
                            StorageLimits.PLAYER_KEY,
                            StorageLimits.PLAYER_NAME,
                            StorageLimits.ACTION,
                            StorageLimits.CODE,
                            StorageLimits.SERVER_NAME,
                            StorageLimits.IP,
                            StorageLimits.MESSAGE
                    ));

            statement.executeUpdate("ALTER TABLE " + playersTable
                    + " MODIFY COLUMN code VARCHAR(" + StorageLimits.CODE + ") NOT NULL");
            statement.executeUpdate("ALTER TABLE " + logsTable
                    + " MODIFY COLUMN code VARCHAR(" + StorageLimits.CODE + ")");
        }
    }

    /**
     * 根据配置值和固定安全默认值构建 JDBC 连接地址
     */
    private String jdbcUrl() {
        return "jdbc:mysql://"
                + settings.host()
                + ":"
                + settings.port()
                + "/"
                + settings.database()
                + "?useSSL="
                + settings.useSsl()
                + "&allowPublicKeyRetrieval=true"
                + "&useUnicode=true"
                + "&characterEncoding=utf8"
                + "&serverTimezone=UTC";
    }

    /**
     * 使用 MySQL 反引号包裹已校验的 SQL 标识符
     */
    private static String quote(String identifier) {
        return "`" + identifier + "`";
    }
}
