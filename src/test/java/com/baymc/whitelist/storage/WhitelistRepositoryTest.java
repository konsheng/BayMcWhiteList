package com.baymc.whitelist.storage;

import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.identity.PlayerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 针对仓库层写入边界的单元测试
 */
class WhitelistRepositoryTest {
    /**
     * 空值和未超长文本不应被截断逻辑改变
     */
    @Test
    void truncateLeavesNullAndShortValuesUnchanged() {
        assertNull(WhitelistRepository.truncate(null, StorageLimits.MESSAGE));
        assertEquals("short", WhitelistRepository.truncate("short", StorageLimits.MESSAGE));
    }

    /**
     * 超长审计字段应被截断到数据库字段上限
     */
    @Test
    void truncateCutsLongAuditFieldsToDatabaseLimits() {
        String longPlayerName = "P".repeat(StorageLimits.PLAYER_NAME + 20);
        String longAction = "A".repeat(StorageLimits.ACTION + 20);
        String longCode = "X".repeat(StorageLimits.CODE + 20);
        String longServerName = "S".repeat(StorageLimits.SERVER_NAME + 20);
        String longIp = "I".repeat(StorageLimits.IP + 20);
        String longMessage = "M".repeat(StorageLimits.MESSAGE + 20);

        assertEquals(StorageLimits.PLAYER_NAME, WhitelistRepository.truncate(longPlayerName, StorageLimits.PLAYER_NAME).length());
        assertEquals(StorageLimits.ACTION, WhitelistRepository.truncate(longAction, StorageLimits.ACTION).length());
        assertEquals(StorageLimits.CODE, WhitelistRepository.truncate(longCode, StorageLimits.CODE).length());
        assertEquals(StorageLimits.SERVER_NAME, WhitelistRepository.truncate(longServerName, StorageLimits.SERVER_NAME).length());
        assertEquals(StorageLimits.IP, WhitelistRepository.truncate(longIp, StorageLimits.IP).length());
        assertEquals(StorageLimits.MESSAGE, WhitelistRepository.truncate(longMessage, StorageLimits.MESSAGE).length());
    }

    /**
     * UUID 查询应保留普通索引可用的等值查询, 不在列上包 LOWER()
     */
    @Test
    void findByUuidQueryUsesIndexedColumn() {
        String sql = SqlTemplates.load("sql/mysql/repository.sql").render("find_by_uuid", tablePlaceholders());

        assertTrue(sql.contains("WHERE player_uuid = ?"));
        assertFalse(sql.contains("LOWER("));
    }

    /**
     * 手动添加白名单没有真实邀请码, 因此建表 SQL 应允许 code 为空
     */
    @Test
    void schemaAllowsManualWhitelistRecordsWithoutInviteCode() {
        String sql = SqlTemplates.load("sql/mysql/schema.sql").render("create_whitelist_players", schemaPlaceholders());

        assertTrue(sql.contains("code VARCHAR(" + StorageLimits.CODE + ")"));
        assertFalse(sql.contains("code VARCHAR(" + StorageLimits.CODE + ") NOT NULL"));
    }

    @Test
    void schemaUsesPlayerUuidAsWhitelistPrimaryKey() {
        String sql = SqlTemplates.load("sql/mysql/schema.sql").render("create_whitelist_players", schemaPlaceholders());

        assertTrue(sql.contains("player_uuid VARCHAR(" + StorageLimits.PLAYER_UUID + ") NOT NULL"));
        assertTrue(sql.contains("PRIMARY KEY (player_uuid)"));
        assertFalse(sql.contains("id BIGINT PRIMARY KEY AUTO_INCREMENT"));
    }

    @Test
    void logSchemaAndInsertUsePlayerUuidField() {
        String schemaSql = SqlTemplates.load("sql/mysql/schema.sql").render("create_whitelist_logs", schemaPlaceholders());
        String insertSql = SqlTemplates.load("sql/mysql/repository.sql").render("insert_log", tablePlaceholders());

        assertTrue(schemaSql.contains("player_uuid VARCHAR(" + StorageLimits.PLAYER_UUID + ") NOT NULL"));
        assertTrue(schemaSql.contains("INDEX idx_player_uuid (player_uuid)"));
        assertTrue(insertSql.contains("(player_uuid, player_name, action, code, server_name, ip, message, created_at)"));
        assertFalse(schemaSql.contains("player_key"));
        assertFalse(insertSql.contains("player_key"));
    }

    /**
     * 手动添加 SQL 应显式写入空邀请码和空最后进入时间
     */
    @Test
    void manualInsertStoresNullCodeAndLastSeen() {
        String sql = SqlTemplates.load("sql/mysql/repository.sql").render("insert_manual_player", tablePlaceholders());

        assertTrue(sql.contains("INSERT IGNORE INTO `players`"));
        assertTrue(sql.contains("?, ?, NULL, ?, ?, ?, NULL"));
    }

    @Test
    void sqliteSchemaUsesPortableTableAndIndexSyntax() {
        SqlTemplates templates = SqlTemplates.load("sql/sqlite/schema.sql");

        String playersSql = templates.render("create_whitelist_players", sqliteSchemaPlaceholders());
        String logsSql = templates.render("create_whitelist_logs", sqliteSchemaPlaceholders());
        String playerNameIndexSql = templates.render("create_whitelist_players_name_index", sqliteSchemaPlaceholders());

        assertTrue(playersSql.contains("player_uuid TEXT NOT NULL"));
        assertTrue(logsSql.contains("id INTEGER PRIMARY KEY AUTOINCREMENT"));
        assertTrue(playerNameIndexSql.contains("CREATE INDEX IF NOT EXISTS \"idx_whitelist_players_player_name\""));
        assertFalse(playersSql.contains("ENGINE=InnoDB"));
    }

    @Test
    void sqliteRepositoryUsesConflictSyntax() {
        SqlTemplates templates = SqlTemplates.load("sql/sqlite/repository.sql");

        String upsertSql = templates.render("upsert_player", sqliteTablePlaceholders());
        String manualInsertSql = templates.render("insert_manual_player", sqliteTablePlaceholders());

        assertTrue(upsertSql.contains("ON CONFLICT(player_uuid) DO UPDATE SET"));
        assertTrue(upsertSql.contains("excluded.player_name"));
        assertTrue(manualInsertSql.contains("INSERT OR IGNORE INTO \"whitelist_players\""));
    }

    @Test
    void sqliteJdbcUrlUsesConfiguredFileInsideDataDirectory(@TempDir Path tempDir) {
        DatabaseManager database = new DatabaseManager(sqliteStorageSettings("whitelist.sqlite3"), tempDir);

        String jdbcUrl = database.jdbcUrl();

        assertTrue(jdbcUrl.startsWith("jdbc:sqlite:"));
        assertTrue(jdbcUrl.endsWith("whitelist.sqlite3"));
        assertTrue(jdbcUrl.contains(tempDir.toAbsolutePath().normalize().toString().replace('\\', '/')));
    }

    @Test
    void sqliteRepositoryRoundTripUsesLocalDatabase(@TempDir Path tempDir) throws Exception {
        DatabaseManager database = new DatabaseManager(sqliteStorageSettings("roundtrip.db"), tempDir);
        try {
            database.start();
            WhitelistRepository repository = new WhitelistRepository(database, "login");
            UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
            PlayerIdentity identity = new PlayerIdentity(uuid, "Notch");
            LocalDate issueDate = LocalDate.parse("2026-06-19");
            LocalDateTime usedAt = LocalDateTime.parse("2026-06-19T12:00:00");

            assertFalse(repository.isWhitelisted(identity.uuidText()));
            assertTrue(repository.insertManual(identity, issueDate, usedAt));
            assertFalse(repository.insertManual(identity, issueDate, usedAt));
            assertTrue(repository.isWhitelisted(identity.uuidText()));

            Optional<WhitelistRecord> manualRecord = repository.findByUuid(identity.uuidText());
            assertTrue(manualRecord.isPresent());
            assertNull(manualRecord.get().code());
            assertEquals(issueDate, manualRecord.get().issueDate());

            repository.upsert(identity, "BAYMC-ABCDEFGH", issueDate, usedAt);
            LocalDateTime lastSeenAt = LocalDateTime.parse("2026-06-19T13:30:00");
            repository.updateLastSeen(identity.uuidText(), lastSeenAt);

            WhitelistRecord updatedRecord = repository.findByUuid(identity.uuidText()).orElseThrow();
            assertEquals("BAYMC-ABCDEFGH", updatedRecord.code());
            assertEquals(lastSeenAt, updatedRecord.lastSeenAt());

            repository.log(new WhitelistLogEntry(
                    identity.uuidText(),
                    identity.name(),
                    "VERIFY_SUCCESS",
                    "BAYMC-ABCDEFGH",
                    "login",
                    "127.0.0.1",
                    "ok",
                    usedAt
            ));
            assertEquals(1, logCount(database));
        } finally {
            database.close();
        }
    }

    /**
     * 只有仍被运行期快照持有的数据库管理器才需要插件继续追踪
     */
    @Test
    void retireOnlyRequestsTrackingWhenLeased() {
        DatabaseManager unusedDatabase = new DatabaseManager(mysqlSettings());
        assertFalse(unusedDatabase.retire());

        DatabaseManager leasedDatabase = new DatabaseManager(mysqlSettings());
        try (DatabaseManager.Lease ignored = leasedDatabase.lease()) {
            assertTrue(leasedDatabase.retire());
        }
        assertTrue(leasedDatabase.isClosed());
    }

    @Test
    void jdbcUrlHonorsPublicKeyRetrievalSetting() {
        assertTrue(new DatabaseManager(mysqlSettings(false)).jdbcUrl().contains("allowPublicKeyRetrieval=false"));
        assertTrue(new DatabaseManager(mysqlSettings(true)).jdbcUrl().contains("allowPublicKeyRetrieval=true"));
    }

    private static int logCount(DatabaseManager database) throws Exception {
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + database.logsTable())) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static PluginConfig.StorageSettings sqliteStorageSettings(String file) {
        return new PluginConfig.StorageSettings(
                PluginConfig.StorageType.SQLITE,
                mysqlSettings(),
                new PluginConfig.SqliteSettings(file)
        );
    }

    private static PluginConfig.MysqlSettings mysqlSettings() {
        return mysqlSettings(false);
    }

    private static PluginConfig.MysqlSettings mysqlSettings(boolean allowPublicKeyRetrieval) {
        return new PluginConfig.MysqlSettings(
                "127.0.0.1",
                3306,
                "baymc",
                "root",
                "password",
                "baymcwhitelist_",
                false,
                allowPublicKeyRetrieval,
                10,
                2,
                10000L,
                600000L,
                1800000L
        );
    }

    private static Map<String, String> tablePlaceholders() {
        return Map.of(
                "players_table", "`players`",
                "logs_table", "`logs`"
        );
    }

    private static Map<String, String> sqliteTablePlaceholders() {
        return Map.of(
                "players_table", "\"whitelist_players\"",
                "logs_table", "\"whitelist_logs\""
        );
    }

    private static Map<String, String> schemaPlaceholders() {
        return Map.of(
                "players_table", "`players`",
                "logs_table", "`logs`",
                "player_uuid_length", String.valueOf(StorageLimits.PLAYER_UUID),
                "player_name_length", String.valueOf(StorageLimits.PLAYER_NAME),
                "code_length", String.valueOf(StorageLimits.CODE),
                "server_name_length", String.valueOf(StorageLimits.SERVER_NAME),
                "action_length", String.valueOf(StorageLimits.ACTION),
                "ip_length", String.valueOf(StorageLimits.IP),
                "message_length", String.valueOf(StorageLimits.MESSAGE)
        );
    }

    private static Map<String, String> sqliteSchemaPlaceholders() {
        return Map.of(
                "players_table", "\"whitelist_players\"",
                "logs_table", "\"whitelist_logs\"",
                "players_name_index", "\"idx_whitelist_players_player_name\"",
                "logs_player_uuid_index", "\"idx_whitelist_logs_player_uuid\"",
                "logs_created_at_index", "\"idx_whitelist_logs_created_at\""
        );
    }
}
