package com.baymc.whitelist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 针对插件运行期切换策略的纯判断测试
 */
class BayMcWhiteListPluginTest {
    /**
     * 新数据库连接失败且旧数据库仍可用时, reload 应保留旧运行期
     */
    @Test
    void preservesCurrentRuntimeWhenReloadDatabaseFails() {
        assertTrue(BayMcWhiteListPlugin.shouldPreserveCurrentRuntime(false, true));
    }

    /**
     * 初次启动或旧数据库本来不可用时, 可以切换到新的未就绪运行期
     */
    @Test
    void doesNotPreserveUnavailableRuntime() {
        assertFalse(BayMcWhiteListPlugin.shouldPreserveCurrentRuntime(false, false));
        assertFalse(BayMcWhiteListPlugin.shouldPreserveCurrentRuntime(true, true));
    }
}
