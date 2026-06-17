package com.baymc.whitelist.mojang;

/**
 * Mojang 档案查询过程中出现网络, 状态码或响应解析错误
 */
public final class MojangProfileLookupException extends Exception {
    public MojangProfileLookupException(String message) {
        super(message);
    }

    public MojangProfileLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
