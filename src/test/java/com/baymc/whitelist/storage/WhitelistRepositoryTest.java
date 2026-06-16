package com.baymc.whitelist.storage;

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
}
