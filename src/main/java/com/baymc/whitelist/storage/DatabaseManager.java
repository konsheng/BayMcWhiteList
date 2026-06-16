package com.baymc.whitelist.storage;

import com.baymc.whitelist.config.PluginConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseManager implements AutoCloseable {
    private final PluginConfig.MysqlSettings settings;
    private HikariDataSource dataSource;
    private boolean ready;

    public DatabaseManager(PluginConfig.MysqlSettings settings) {
        this.settings = settings;
    }

    public synchronized void start() throws SQLException {
        close();

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("BayMcWhiteList-Hikari");
        hikari.setJdbcUrl(jdbcUrl());
        hikari.setUsername(settings.username());
        hikari.setPassword(settings.password());
        hikari.setMaximumPoolSize(settings.maximumPoolSize());
        hikari.setMinimumIdle(Math.min(settings.minimumIdle(), settings.maximumPoolSize()));
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

    public boolean isReady() {
        return ready && dataSource != null && !dataSource.isClosed();
    }

    public Connection getConnection() throws SQLException {
        if (!isReady()) {
            throw new SQLException("Database is not ready");
        }
        return dataSource.getConnection();
    }

    public String playersTable() {
        // table-prefix is validated against [A-Za-z0-9_] while loading config,
        // so these identifiers are safe to interpolate after quoting.
        return quote(settings.tablePrefix() + "whitelist_players");
    }

    public String logsTable() {
        return quote(settings.tablePrefix() + "whitelist_logs");
    }

    @Override
    public synchronized void close() {
        ready = false;
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    private void initializeSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      player_key VARCHAR(64) NOT NULL UNIQUE,
                      player_uuid VARCHAR(36),
                      player_name VARCHAR(32) NOT NULL,
                      code VARCHAR(64) NOT NULL,
                      issue_date DATE NOT NULL,
                      used_at DATETIME NOT NULL,
                      source_server VARCHAR(64),
                      last_seen_at DATETIME,
                      INDEX idx_player_uuid (player_uuid),
                      INDEX idx_player_name (player_name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """.formatted(playersTable()));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      player_key VARCHAR(64) NOT NULL,
                      player_name VARCHAR(32) NOT NULL,
                      action VARCHAR(32) NOT NULL,
                      code VARCHAR(64),
                      server_name VARCHAR(64),
                      ip VARCHAR(64),
                      message VARCHAR(255),
                      created_at DATETIME NOT NULL,
                      INDEX idx_player_key (player_key),
                      INDEX idx_created_at (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """.formatted(logsTable()));
        }
    }

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

    private static String quote(String identifier) {
        return "`" + identifier + "`";
    }
}
