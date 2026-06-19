package com.baymc.whitelist.command;

import com.baymc.whitelist.config.PluginConfig;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * 命令入口共用的边界判断工具
 *
 * <p>这里只放不依赖 Bukkit 运行时的纯判断, 这样权限和参数数量可以被单元测试直接覆盖
 */
final class CommandBoundaries {
    static final List<String> BAYMC_SUBCOMMANDS = List.of("add", "generate", "status", "remove", "reload", "info", "help");

    private CommandBoundaries() {
    }

    static WhitelistDecision whitelistDecision(
            boolean senderIsPlayer,
            boolean hasUsePermission,
            boolean hasSelfStatusPermission,
            PluginConfig.ServerMode serverMode,
            int argumentCount
    ) {
        if (!senderIsPlayer) {
            return WhitelistDecision.ONLY_PLAYER;
        }

        // /whitelist 无参数只查询玩家自己的状态, 不改变数据库状态, 因此允许在任意服务器模式下执行
        if (argumentCount == 0) {
            return hasSelfStatusPermission ? WhitelistDecision.STATUS : WhitelistDecision.NO_PERMISSION;
        }
        if (argumentCount != 1) {
            return WhitelistDecision.USAGE;
        }
        if (!hasUsePermission) {
            return WhitelistDecision.NO_PERMISSION;
        }

        // 只有提交邀请码会改变白名单状态, 仍限制在登录服执行
        if (serverMode != PluginConfig.ServerMode.LOGIN) {
            return WhitelistDecision.LOGIN_SERVER_ONLY;
        }
        return WhitelistDecision.VERIFY;
    }

    static boolean hasExactArgumentCount(String[] args, int expected) {
        return args.length == expected;
    }

    static List<String> visibleBayMcSubcommands(Predicate<String> hasPermission, String rawPrefix) {
        String prefix = rawPrefix.toLowerCase(Locale.ROOT);
        return BAYMC_SUBCOMMANDS.stream()
                .filter(subcommand -> subcommand.startsWith(prefix))
                .filter(subcommand -> hasPermission.test(permissionFor(subcommand)))
                .toList();
    }

    static String permissionFor(String subcommand) {
        return switch (subcommand.toLowerCase(Locale.ROOT)) {
            case "add" -> "baymcwhitelist.add";
            case "generate" -> "baymcwhitelist.generate";
            case "status" -> "baymcwhitelist.status";
            case "remove" -> "baymcwhitelist.remove";
            case "reload" -> "baymcwhitelist.reload";
            case "info" -> "baymcwhitelist.info";
            case "help" -> "baymcwhitelist.help";
            default -> "baymcwhitelist." + subcommand.toLowerCase(Locale.ROOT);
        };
    }

    /**
     * 玩家 /whitelist 命令在进入邀请码校验前可能得到的边界判断结果
     */
    enum WhitelistDecision {
        /** 所有前置条件通过, 可以继续校验邀请码 */
        VERIFY,
        /** 所有前置条件通过, 可以查询玩家自己的白名单状态 */
        STATUS,
        /** 命令来源不是玩家实体 */
        ONLY_PLAYER,
        /** 玩家缺少使用 /whitelist 的权限 */
        NO_PERMISSION,
        /** 当前服务器不是登录/验证服 */
        LOGIN_SERVER_ONLY,
        /** 参数数量不等于一个邀请码 */
        USAGE
    }
}
