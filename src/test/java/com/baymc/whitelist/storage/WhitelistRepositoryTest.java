package com.baymc.whitelist.storage;

import com.baymc.whitelist.config.PluginConfig;
import org.junit.jupiter.api.Test;

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
    void truncateCutsLongAuditFieldsToDatabaseLimit() {
        String longCode = "X".repeat(StorageLimits.CODE + 20);
        String longMessage = "M".repeat(StorageLimits.MESSAGE + 20);

        assertEquals(StorageLimits.CODE, WhitelistRepository.truncate(longCode, StorageLimits.CODE).length());
        assertEquals(StorageLimits.MESSAGE, WhitelistRepository.truncate(longMessage, StorageLimits.MESSAGE).length());
    }

    /**
     * 玩家名回查应保留普通索引可用的等值查询, 不在列上包 LOWER()
     */
    @Test
    void findByNameQueryDoesNotWrapIndexedColumn() {
        String sql = SqlTemplates.load("sql/repository.sql").render("find_by_name", java.util.Map.of(
                "players_table", "`players`",
                "logs_table", "`logs`"
        ));

        assertTrue(sql.contains("WHERE player_name = ?"));
        assertFalse(sql.contains("LOWER("));
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

    private static PluginConfig.MysqlSettings mysqlSettings() {
        return new PluginConfig.MysqlSettings(
                "127.0.0.1",
                3306,
                "baymc",
                "root",
                "password",
                "baymcwhitelist_",
                false,
                10,
                2,
                10000L,
                600000L,
                1800000L
        );
    }
}
