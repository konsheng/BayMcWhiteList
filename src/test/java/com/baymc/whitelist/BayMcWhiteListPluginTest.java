package com.baymc.whitelist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 针对插件运行期切换策略的纯判断测试
 */
class BayMcWhiteListPluginTest {
    @Test
    void preservesCurrentRuntimeWhenReloadDatabaseFails() {
        assertTrue(BayMcWhiteListPlugin.shouldPreserveCurrentRuntime(false, true));
    }

    @Test
    void doesNotPreserveUnavailableRuntime() {
        assertFalse(BayMcWhiteListPlugin.shouldPreserveCurrentRuntime(false, false));
        assertFalse(BayMcWhiteListPlugin.shouldPreserveCurrentRuntime(true, true));
    }
}
