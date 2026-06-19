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
 * <p>该类是 MySQL 与 SQLite 的方言分界点: 上层仓库只关心模板名称和参数绑定
 * 具体 JDBC URL, 连接池参数, 建表模板和 SQL 引号规则都在这里集中选择
 *
 * <p>reload 时旧连接池可能仍被异步命令快照持有, 因此通过 Lease 延迟关闭旧数据源
 * 避免同一次命令在执行中途被新配置或新数据库连接打断
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

    public DatabaseManager(PluginConfig.MysqlSettings settings) {
        this(new PluginConfig.StorageSettings(
                PluginConfig.StorageType.MYSQL,
                settings,
                new PluginConfig.SqliteSettings("whitelist.db")
        ));
    }

    public DatabaseManager(PluginConfig.StorageSettings settings) {
        this(settings, Path.of("."));
    }

    public DatabaseManager(PluginConfig.StorageSettings settings, Path dataDirectory) {
        this.settings = settings;
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.dialect = StorageDialect.from(settings.type());
        this.schemaSql = SqlTemplates.load(dialect.schemaResource);
        this.repositorySql = SqlTemplates.load(dialect.repositoryResource);
    }

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

    public synchronized boolean isReady() {
        return ready && dataSource != null && !dataSource.isClosed();
    }

    public synchronized boolean isClosed() {
        return dataSource == null || dataSource.isClosed();
    }

    public synchronized Connection getConnection() throws SQLException {
        HikariDataSource currentDataSource = dataSource;
        if (!ready || currentDataSource == null || currentDataSource.isClosed()) {
            throw new SQLException("Database is not ready");
        }
        return currentDataSource.getConnection();
    }

    public String playersTable() {
        return quote(tablePrefix() + "whitelist_players");
    }

    public String logsTable() {
        return quote(tablePrefix() + "whitelist_logs");
    }

    SqlTemplates repositorySql() {
        return repositorySql;
    }

    @Override
    public synchronized void close() {
        closeRequested = true;
        forceClose();
    }

    public synchronized boolean retire() {
        closeRequested = true;
        if (activeLeases == 0) {
            forceClose();
            return false;
        }
        return true;
    }

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

    String jdbcUrl() {
        return switch (settings.type()) {
            case MYSQL -> mysqlJdbcUrl();
            case SQLITE -> "jdbc:sqlite:" + sqliteFile().toString().replace('\\', '/');
        };
    }

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

    private void applySqliteSettings(HikariConfig hikari) {
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setMaximumPoolSize(1);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(10000L);
        hikari.setIdleTimeout(0L);
        hikari.setMaxLifetime(0L);
        hikari.setConnectionInitSql("PRAGMA busy_timeout=5000");
    }

    private void prepareSqliteFile() throws SQLException {
        try {
            Files.createDirectories(sqliteFile().getParent());
        } catch (IOException exception) {
            throw new SQLException("Unable to create SQLite database directory", exception);
        }
    }

    private void initializeDialect(Connection connection, Statement statement) throws SQLException {
        if (settings.type() != PluginConfig.StorageType.SQLITE) {
            return;
        }
        statement.executeUpdate("PRAGMA busy_timeout=5000");
        statement.executeUpdate("PRAGMA foreign_keys=ON");
        statement.executeQuery("PRAGMA journal_mode=WAL").close();
        connection.setAutoCommit(true);
    }

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

    private String quote(String identifier) {
        return dialect.quote + identifier + dialect.quote;
    }

    /**
     * 当前支持的数据库 SQL 方言和模板资源
     */
    private enum StorageDialect {
        MYSQL(
                PluginConfig.StorageType.MYSQL,
                "sql/mysql/schema.sql",
                "sql/mysql/repository.sql",
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
