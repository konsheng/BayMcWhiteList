package com.baymc.whitelist.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
