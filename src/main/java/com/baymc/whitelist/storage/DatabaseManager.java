package com.baymc.whitelist.storage;

import com.baymc.whitelist.config.PluginConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * 持有 HikariCP 数据源, 并负责初始化 MySQL 表结构
 */
public final class DatabaseManager implements AutoCloseable {
    private static final SqlTemplates SCHEMA_SQL = SqlTemplates.load("sql/schema.sql");

    private final PluginConfig.MysqlSettings settings;
    private HikariDataSource dataSource;
    private boolean ready;
    private boolean closeRequested;
    private int activeLeases;

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
        forceClose();
        closeRequested = false;
        activeLeases = 0;

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
            forceClose();
            throw exception;
        }
    }

    /**
     * 判断调用方当前是否可以从连接池借出连接
     */
    public synchronized boolean isReady() {
        return ready && dataSource != null && !dataSource.isClosed();
    }

    /**
     * 判断连接池是否已经关闭, 供插件清理 retired 运行期引用
     */
    public synchronized boolean isClosed() {
        return dataSource == null || dataSource.isClosed();
    }

    /**
     * 借出数据库连接; 如果启动未完成则快速失败
     */
    public synchronized Connection getConnection() throws SQLException {
        HikariDataSource currentDataSource = dataSource;
        if (!ready || currentDataSource == null || currentDataSource.isClosed()) {
            throw new SQLException("Database is not ready");
        }
        return currentDataSource.getConnection();
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
        closeRequested = true;
        forceClose();
    }

    /**
     * 标记为旧运行期连接池, 等已捕获快照释放后再关闭
     *
     * @return 如果仍有快照持有该连接池, 返回 true 表示需要调用方继续追踪
     */
    public synchronized boolean retire() {
        closeRequested = true;
        if (activeLeases == 0) {
            forceClose();
            return false;
        }
        return true;
    }

    /**
     * 为一次命令或监听器快照保留连接池生命周期
     */
    public synchronized Lease lease() {
        activeLeases++;
        return new Lease(this);
    }

    private synchronized void releaseLease() {
        if (activeLeases <= 0) {
            return;
        }
        activeLeases--;
        if (closeRequested && activeLeases == 0) {
            forceClose();
        }
    }

    private void forceClose() {
        ready = false;
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    /**
     * 一次运行期快照持有的连接池引用
     */
    public static final class Lease implements AutoCloseable {
        private final DatabaseManager database;
        private boolean closed;

        private Lease(DatabaseManager database) {
            this.database = database;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            database.releaseLease();
        }
    }

    /**
     * 在数据表不存在时创建插件所需的两张表
     */
    private void initializeSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            Map<String, String> placeholders = schemaPlaceholders();
            statement.executeUpdate(SCHEMA_SQL.render("create_whitelist_players", placeholders));
            statement.executeUpdate(SCHEMA_SQL.render("create_whitelist_logs", placeholders));
        }
    }

    /**
     * 构建建表模板所需的安全占位符
     */
    private Map<String, String> schemaPlaceholders() {
        return Map.of(
                "players_table", playersTable(),
                "logs_table", logsTable(),
                "player_key_length", String.valueOf(StorageLimits.PLAYER_KEY),
                "player_uuid_length", String.valueOf(StorageLimits.PLAYER_UUID),
                "player_name_length", String.valueOf(StorageLimits.PLAYER_NAME),
                "code_length", String.valueOf(StorageLimits.CODE),
                "server_name_length", String.valueOf(StorageLimits.SERVER_NAME),
                "action_length", String.valueOf(StorageLimits.ACTION),
                "ip_length", String.valueOf(StorageLimits.IP),
                "message_length", String.valueOf(StorageLimits.MESSAGE)
        );
    }

    /**
     * 根据配置值和固定安全默认值构建 JDBC 连接地址
     */
    String jdbcUrl() {
        return "jdbc:mysql://"
                + settings.host()
                + ":"
                + settings.port()
                + "/"
                + settings.database()
                + "?useSSL="
                + settings.useSsl()
                + "&allowPublicKeyRetrieval="
                + settings.allowPublicKeyRetrieval()
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
