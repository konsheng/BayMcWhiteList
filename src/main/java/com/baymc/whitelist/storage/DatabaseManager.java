package com.baymc.whitelist.storage;

import com.baymc.whitelist.config.PluginConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 持有 HikariCP 数据源, 并按当前存储后端初始化数据库表结构
 *
 * <p>该类是 MySQL 与 SQLite 的方言分界点: 上层仓库只关心模板名称和参数绑定,
 * 具体 JDBC URL, 连接池参数, 建表模板和 SQL 引号规则都在这里集中选择。
 *
 * <p>reload 时旧连接池可能仍被异步命令快照持有, 因此通过 Lease 延迟关闭旧数据源,
 * 避免同一次命令在执行中途被新配置或新数据库连接打断。
 */
public final class DatabaseManager implements AutoCloseable {
    private final PluginConfig.StorageSettings settings;
    private final Path dataDirectory;
    private final StorageDialect dialect;
    private final SqlTemplates schemaSql;
    private final SqlTemplates repositorySql;
    private HikariDataSource dataSource;
    private boolean ready;
    private boolean closeRequested;
    private int activeLeases;

    /**
     * 使用 MySQL 配置创建数据库管理器, 兼容只关心 MySQL 的单元测试
     *
     * @param settings 已校验的 MySQL 配置
     */
    public DatabaseManager(PluginConfig.MysqlSettings settings) {
        this(new PluginConfig.StorageSettings(
                PluginConfig.StorageType.MYSQL,
                settings,
                new PluginConfig.SqliteSettings("whitelist.db")
        ));
    }

    /**
     * 使用当前工作目录作为本地数据库目录创建数据库管理器
     *
     * @param settings 已校验的存储后端配置
     */
    public DatabaseManager(PluginConfig.StorageSettings settings) {
        this(settings, Path.of("."));
    }

    /**
     * 保存已校验的存储配置, 供后续启动连接池和选择 SQL 方言使用
     *
     * @param settings 已校验的存储后端配置
     * @param dataDirectory 插件数据目录, SQLite 文件必须被解析在该目录内部
     */
    public DatabaseManager(PluginConfig.StorageSettings settings, Path dataDirectory) {
        this.settings = settings;
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.dialect = StorageDialect.from(settings.type());
        this.schemaSql = SqlTemplates.load(dialect.schemaResource);
        this.repositorySql = SqlTemplates.load(dialect.repositoryResource);
    }

    /**
     * 重新创建连接池, 并确保当前存储后端所需数据表存在
     *
     * <p>任何连接池或建表失败都会关闭刚创建的数据源, 调用方随后会把数据库状态标记为不可用
     *
     * @throws SQLException 当连接池启动或表结构初始化失败时抛出
     */
    public synchronized void start() throws SQLException {
        forceClose();
        closeRequested = false;
        activeLeases = 0;

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("BayMcWhiteList-" + dialect.name());
        hikari.setJdbcUrl(jdbcUrl());
        if (settings.type() == PluginConfig.StorageType.MYSQL) {
            applyMysqlSettings(hikari);
        } else {
            prepareSqliteFile();
            applySqliteSettings(hikari);
        }

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
     *
     * @return 数据源是否已经启动且未关闭
     */
    public synchronized boolean isReady() {
        return ready && dataSource != null && !dataSource.isClosed();
    }

    /**
     * 判断连接池是否已经关闭, 供插件清理 retired 运行期引用
     *
     * @return 数据源是否已经关闭或尚未创建
     */
    public synchronized boolean isClosed() {
        return dataSource == null || dataSource.isClosed();
    }

    /**
     * 借出数据库连接; 如果启动未完成则快速失败
     *
     * @return 来自当前连接池的 JDBC 连接
     * @throws SQLException 当数据库尚未就绪或连接池借出失败时抛出
     */
    public synchronized Connection getConnection() throws SQLException {
        HikariDataSource currentDataSource = dataSource;
        if (!ready || currentDataSource == null || currentDataSource.isClosed()) {
            throw new SQLException("Database is not ready");
        }
        return currentDataSource.getConnection();
    }

    /**
     * 返回带当前方言引号的白名单玩家表名
     *
     * @return 可直接插入 SQL 模板的玩家表标识符
     */
    public String playersTable() {
        return quote(tablePrefix() + "whitelist_players");
    }

    /**
     * 返回带当前方言引号的审计日志表名
     *
     * @return 可直接插入 SQL 模板的日志表标识符
     */
    public String logsTable() {
        return quote(tablePrefix() + "whitelist_logs");
    }

    /**
     * 返回当前后端对应的仓库 SQL 模板
     *
     * @return 当前 SQL 方言对应的仓库模板集合
     */
    SqlTemplates repositorySql() {
        return repositorySql;
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
     *
     * <p>快照释放前, 即使管理员执行 reload, 旧数据源也会等当前操作结束后再关闭
     *
     * @return 需要在快照结束时关闭的租约
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

        /**
         * {@inheritDoc}
         */
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
     * 在数据表不存在时创建插件所需的数据表和索引
     *
     * <p>MySQL 的索引随建表语句创建, SQLite 则使用独立模板创建索引
     */
    private void initializeSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            initializeDialect(connection, statement);
            Map<String, String> placeholders = schemaPlaceholders();
            for (String templateName : dialect.schemaTemplates) {
                statement.executeUpdate(schemaSql.render(templateName, placeholders));
            }
        }
    }

    /**
     * 构建建表模板所需的安全占位符
     */
    private Map<String, String> schemaPlaceholders() {
        return Map.ofEntries(
                Map.entry("players_table", playersTable()),
                Map.entry("logs_table", logsTable()),
                Map.entry("players_name_index", quote("idx_whitelist_players_player_name")),
                Map.entry("logs_player_uuid_index", quote("idx_whitelist_logs_player_uuid")),
                Map.entry("logs_created_at_index", quote("idx_whitelist_logs_created_at")),
                Map.entry("player_uuid_length", String.valueOf(StorageLimits.PLAYER_UUID)),
                Map.entry("player_name_length", String.valueOf(StorageLimits.PLAYER_NAME)),
                Map.entry("code_length", String.valueOf(StorageLimits.CODE)),
                Map.entry("server_name_length", String.valueOf(StorageLimits.SERVER_NAME)),
                Map.entry("action_length", String.valueOf(StorageLimits.ACTION)),
                Map.entry("ip_length", String.valueOf(StorageLimits.IP)),
                Map.entry("message_length", String.valueOf(StorageLimits.MESSAGE))
        );
    }

    /**
     * 根据配置值和固定安全默认值构建 JDBC 连接地址
     */
    String jdbcUrl() {
        return switch (settings.type()) {
            case MYSQL -> mysqlJdbcUrl();
            case SQLITE -> "jdbc:sqlite:" + sqliteFile().toString().replace('\\', '/');
        };
    }

    /**
     * 根据 MySQL 配置拼接带固定安全参数的连接地址
     */
    private String mysqlJdbcUrl() {
        PluginConfig.MysqlSettings mysql = settings.mysql();
        return "jdbc:mysql://"
                + mysql.host()
                + ":"
                + mysql.port()
                + "/"
                + mysql.database()
                + "?useSSL="
                + mysql.useSsl()
                + "&allowPublicKeyRetrieval="
                + mysql.allowPublicKeyRetrieval()
                + "&useUnicode=true"
                + "&characterEncoding=utf8"
                + "&serverTimezone=UTC";
    }

    /**
     * 应用 MySQL 连接池和驱动优化配置
     */
    private void applyMysqlSettings(HikariConfig hikari) {
        PluginConfig.MysqlSettings mysql = settings.mysql();
        hikari.setUsername(mysql.username());
        hikari.setPassword(mysql.password());
        hikari.setMaximumPoolSize(mysql.maximumPoolSize());
        hikari.setMinimumIdle(mysql.minimumIdle());
        hikari.setConnectionTimeout(mysql.connectionTimeout());
        hikari.setIdleTimeout(mysql.idleTimeout());
        hikari.setMaxLifetime(mysql.maxLifetime());
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "250");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikari.addDataSourceProperty("useServerPrepStmts", "true");
        hikari.addDataSourceProperty("useLocalSessionState", "true");
        hikari.addDataSourceProperty("rewriteBatchedStatements", "true");
    }

    /**
     * 应用 SQLite 单文件数据库连接池配置
     *
     * <p>SQLite 同一时间只有一个写事务, 因此池大小固定为 1, 避免多连接写入竞争放大锁等待
     */
    private void applySqliteSettings(HikariConfig hikari) {
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setMaximumPoolSize(1);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(10000L);
        hikari.setIdleTimeout(0L);
        hikari.setMaxLifetime(0L);
        hikari.setConnectionInitSql("PRAGMA busy_timeout=5000");
    }

    /**
     * 确保 SQLite 数据库文件所在目录存在
     */
    private void prepareSqliteFile() throws SQLException {
        try {
            Files.createDirectories(sqliteFile().getParent());
        } catch (IOException exception) {
            throw new SQLException("Unable to create SQLite database directory", exception);
        }
    }

    /**
     * 为 SQLite 初始化写前日志模式, 降低读写互相阻塞的概率
     */
    private void initializeDialect(Connection connection, Statement statement) throws SQLException {
        if (settings.type() != PluginConfig.StorageType.SQLITE) {
            return;
        }
        statement.executeUpdate("PRAGMA busy_timeout=5000");
        statement.executeUpdate("PRAGMA foreign_keys=ON");
        statement.executeQuery("PRAGMA journal_mode=WAL").close();
        connection.setAutoCommit(true);
    }

    /**
     * 解析 SQLite 文件在插件数据目录中的实际路径
     *
     * <p>配置加载阶段已经限制文件名格式, 这里再次用 normalize + startsWith 防御路径越界
     */
    private Path sqliteFile() {
        Path base = dataDirectory.toAbsolutePath().normalize();
        Path file = base.resolve(settings.sqlite().file()).normalize();
        if (!file.startsWith(base)) {
            throw new IllegalStateException("SQLite database file must stay inside the plugin data folder");
        }
        return file;
    }

    private String tablePrefix() {
        return settings.type() == PluginConfig.StorageType.MYSQL ? settings.mysql().tablePrefix() : "";
    }

    /**
     * 使用当前 SQL 方言的引用符包裹已校验的 SQL 标识符
     */
    private String quote(String identifier) {
        return dialect.quote + identifier + dialect.quote;
    }

    /**
     * 当前支持的数据库 SQL 方言和模板资源
     */
    private enum StorageDialect {
        MYSQL(
                PluginConfig.StorageType.MYSQL,
                "sql/schema.sql",
                "sql/repository.sql",
                List.of("create_whitelist_players", "create_whitelist_logs"),
                "`"
        ),
        SQLITE(
                PluginConfig.StorageType.SQLITE,
                "sql/sqlite/schema.sql",
                "sql/sqlite/repository.sql",
                List.of(
                        "create_whitelist_players",
                        "create_whitelist_players_name_index",
                        "create_whitelist_logs",
                        "create_whitelist_logs_player_uuid_index",
                        "create_whitelist_logs_created_at_index"
                ),
                "\""
        );

        private final PluginConfig.StorageType storageType;
        private final String schemaResource;
        private final String repositoryResource;
        private final List<String> schemaTemplates;
        private final String quote;

        StorageDialect(
                PluginConfig.StorageType storageType,
                String schemaResource,
                String repositoryResource,
                List<String> schemaTemplates,
                String quote
        ) {
            this.storageType = storageType;
            this.schemaResource = schemaResource;
            this.repositoryResource = repositoryResource;
            this.schemaTemplates = schemaTemplates;
            this.quote = quote;
        }

        private static StorageDialect from(PluginConfig.StorageType storageType) {
            for (StorageDialect dialect : values()) {
                if (dialect.storageType == storageType) {
                    return dialect;
                }
            }
            throw new IllegalArgumentException("Unsupported storage type: " + storageType);
        }
    }
}
