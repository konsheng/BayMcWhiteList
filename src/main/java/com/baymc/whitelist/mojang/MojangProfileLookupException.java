package com.baymc.whitelist.mojang;

/**
 * Mojang 档案查询过程中出现网络, 状态码或响应解析错误
 */
public final class MojangProfileLookupException extends Exception {
    /**
     * 使用错误说明创建异常
     *
     * @param message 错误说明
     */
    public MojangProfileLookupException(String message) {
        super(message);
    }

    /**
     * 使用错误说明和底层原因创建异常
     *
     * @param message 错误说明
     * @param cause 底层异常
     */
    public MojangProfileLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
