package com.baymc.whitelist.command;

import com.baymc.whitelist.config.PluginConfig;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * 管理命令和玩家命令共用的边界判断工具
 *
 * <p>这里只放不依赖 Bukkit 运行时的纯判断, 这样权限, 参数数量和目标解析策略可以被单元测试直接覆盖
 */
final class CommandBoundaries {
    /**
     * 管理员命令当前支持的全部一级子命令, 顺序同时用于 Tab 补全展示
     */
    static final List<String> ADMIN_SUBCOMMANDS = List.of("add", "generate", "status", "remove", "reload", "info", "help");

    private CommandBoundaries() {
    }

    /**
     * 判断玩家 /whitelist 命令是否满足执行前置条件
     */
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

    /**
     * 校验命令参数数量是否与该子命令声明的数量完全一致
     */
    static boolean hasExactArgumentCount(String[] args, int expected) {
        return args.length == expected;
    }

    /**
     * 根据输入前缀和发送者权限返回可见的管理员子命令补全列表
     */
    static List<String> visibleAdminSubcommands(Predicate<String> hasPermission, String rawPrefix) {
        String prefix = rawPrefix.toLowerCase(Locale.ROOT);
        return ADMIN_SUBCOMMANDS.stream()
                .filter(subcommand -> subcommand.startsWith(prefix))
                .filter(subcommand -> hasPermission.test(permissionFor(subcommand)))
                .toList();
    }

    /**
     * 将管理员子命令映射到代码中显式检查的权限节点
     */
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
     * 判断移除命令的目标输入应如何解析为白名单 UUID
     */
    static RemoveTargetDecision removeTargetDecision(
            boolean inputIsUuid,
            boolean validPlayerName,
            boolean targetOnline
    ) {
        if (inputIsUuid) {
            return RemoveTargetDecision.UUID_INPUT;
        }
        if (!validPlayerName) {
            return RemoveTargetDecision.INVALID_IDENTIFIER;
        }
        if (targetOnline) {
            return RemoveTargetDecision.ONLINE_UUID_NAME;
        }
        return RemoveTargetDecision.UUID_MODE_OFFLINE_NAME_LOOKUP;
    }

    static GenerateTargetDecision generateTargetDecision(
            boolean inputIsUuid,
            boolean validPlayerName,
            boolean targetOnline
    ) {
        if (targetOnline) {
            return GenerateTargetDecision.ONLINE_PLAYER;
        }
        if (inputIsUuid) {
            return GenerateTargetDecision.UUID_LOOKUP;
        }
        if (validPlayerName) {
            return GenerateTargetDecision.NAME_LOOKUP;
        }
        return GenerateTargetDecision.INVALID_IDENTIFIER;
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

    /**
     * 管理员移除命令对目标标识的解析决策
     */
    enum RemoveTargetDecision {
        /** 输入本身就是标准 UUID */
        UUID_INPUT,
        /** 目标玩家在线, 可以从在线实体解析 UUID */
        ONLINE_UUID_NAME,
        /** 离线正版玩家名需要通过 Mojang 档案解析 UUID */
        UUID_MODE_OFFLINE_NAME_LOOKUP,
        /** 输入既不是 UUID, 也不是合法玩家名 */
        INVALID_IDENTIFIER
    }

    enum GenerateTargetDecision {
        ONLINE_PLAYER,
        UUID_LOOKUP,
        NAME_LOOKUP,
        INVALID_IDENTIFIER
    }
}
