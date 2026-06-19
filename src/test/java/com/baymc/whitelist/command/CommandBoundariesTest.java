package com.baymc.whitelist.command;

import com.baymc.whitelist.config.PluginConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 针对命令边界纯判断的单元测试
 */
class CommandBoundariesTest {
    /**
     * 控制台或命令方块不能执行玩家邀请码验证命令
     */
    @Test
    void whitelistRejectsConsoleSender() {
        CommandBoundaries.WhitelistDecision decision = CommandBoundaries.whitelistDecision(
                false,
                true,
                true,
                PluginConfig.ServerMode.LOGIN,
                1
        );

        assertEquals(CommandBoundaries.WhitelistDecision.ONLY_PLAYER, decision);
    }

    /**
     * 玩家缺少 use 权限时应在邀请码校验前被拒绝
     */
    @Test
    void whitelistRejectsMissingUsePermission() {
        CommandBoundaries.WhitelistDecision decision = CommandBoundaries.whitelistDecision(
                true,
                false,
                true,
                PluginConfig.ServerMode.LOGIN,
                1
        );

        assertEquals(CommandBoundaries.WhitelistDecision.NO_PERMISSION, decision);
    }

    /**
     * 玩家缺少自助状态查询权限时应被拒绝
     */
    @Test
    void whitelistStatusRejectsMissingSelfStatusPermission() {
        CommandBoundaries.WhitelistDecision decision = CommandBoundaries.whitelistDecision(
                true,
                true,
                false,
                PluginConfig.ServerMode.LOGIN,
                0
        );

        assertEquals(CommandBoundaries.WhitelistDecision.NO_PERMISSION, decision);
    }

    /**
     * 无参数 /whitelist 用于查询自己的白名单状态
     */
    @Test
    void whitelistWithoutArgumentsQueriesSelfStatus() {
        CommandBoundaries.WhitelistDecision decision = CommandBoundaries.whitelistDecision(
                true,
                false,
                true,
                PluginConfig.ServerMode.LOGIN,
                0
        );

        assertEquals(CommandBoundaries.WhitelistDecision.STATUS, decision);
    }

    /**
     * 自助状态查询不改变白名单状态, 因此受保护服务器也允许执行
     */
    @Test
    void whitelistSelfStatusAllowsProtectedServerMode() {
        CommandBoundaries.WhitelistDecision decision = CommandBoundaries.whitelistDecision(
                true,
                false,
                true,
                PluginConfig.ServerMode.PROTECTED,
                0
        );

        assertEquals(CommandBoundaries.WhitelistDecision.STATUS, decision);
    }

    /**
     * 受保护服务器不能接受 /whitelist 邀请码验证请求
     */
    @Test
    void whitelistRejectsProtectedServerMode() {
        CommandBoundaries.WhitelistDecision decision = CommandBoundaries.whitelistDecision(
                true,
                true,
                true,
                PluginConfig.ServerMode.PROTECTED,
                1
        );

        assertEquals(CommandBoundaries.WhitelistDecision.LOGIN_SERVER_ONLY, decision);
    }

    /**
     * 玩家验证命令携带一个邀请码参数时进入验证流程
     */
    @Test
    void whitelistWithOneArgumentVerifiesInviteCode() {
        assertEquals(
                CommandBoundaries.WhitelistDecision.VERIFY,
                CommandBoundaries.whitelistDecision(true, true, true, PluginConfig.ServerMode.LOGIN, 1)
        );
    }

    /**
     * 多余参数无法判定为状态查询或邀请码验证时返回用法提示
     */
    @Test
    void whitelistRejectsExtraArguments() {
        assertEquals(
                CommandBoundaries.WhitelistDecision.USAGE,
                CommandBoundaries.whitelistDecision(true, true, true, PluginConfig.ServerMode.LOGIN, 2)
        );
        assertEquals(
                CommandBoundaries.WhitelistDecision.USAGE,
                CommandBoundaries.whitelistDecision(true, true, true, PluginConfig.ServerMode.PROTECTED, 2)
        );
    }

    /**
     * 管理员目标类命令必须且只能携带一个玩家名或 UUID 参数
     */
    @Test
    void adminTargetCommandsRequireOneTargetArgument() {
        assertFalse(CommandBoundaries.hasExactArgumentCount(new String[]{"generate"}, 2));
        assertTrue(CommandBoundaries.hasExactArgumentCount(new String[]{"generate", "Steve"}, 2));
        assertFalse(CommandBoundaries.hasExactArgumentCount(new String[]{"generate", "Steve", "extra"}, 2));
    }

    /**
     * 管理命令补全只展示发送者拥有权限的子命令
     */
    @Test
    void adminTabCompletionOnlyShowsPermittedSubcommands() {
        Set<String> permissions = Set.of(
                "baymcwhitelist.add",
                "baymcwhitelist.generate",
                "baymcwhitelist.info",
                "baymcwhitelist.help"
        );

        List<String> visible = CommandBoundaries.visibleAdminSubcommands(permissions::contains, "");

        assertEquals(List.of("add", "generate", "info", "help"), visible);
    }

    /**
     * add 子命令使用独立权限节点
     */
    @Test
    void addSubcommandUsesIndependentPermission() {
        assertEquals("baymcwhitelist.add", CommandBoundaries.permissionFor("add"));

        List<String> visible = CommandBoundaries.visibleAdminSubcommands(
                "baymcwhitelist.add"::equals,
                ""
        );

        assertEquals(List.of("add"), visible);
    }

    /**
     * help 子命令使用独立权限节点, 不依赖总管理权限包
     */
    @Test
    void helpSubcommandUsesIndependentPermission() {
        assertEquals("baymcwhitelist.help", CommandBoundaries.permissionFor("help"));

        List<String> visible = CommandBoundaries.visibleAdminSubcommands(
                "baymcwhitelist.help"::equals,
                ""
        );

        assertEquals(List.of("help"), visible);
    }

    /**
     * 没有任何管理权限时不应泄露可用子命令补全
     */
    @Test
    void adminTabCompletionHidesAllSubcommandsWithoutPermissions() {
        List<String> visible = CommandBoundaries.visibleAdminSubcommands(permission -> false, "");

        assertEquals(List.of(), visible);
    }

    /**
     * 离线正版玩家名应进入 Mojang 档案查询流程
     */
    @Test
    void uuidModeRemoveLooksUpOfflinePlayerName() {
        CommandBoundaries.RemoveTargetDecision decision = CommandBoundaries.removeTargetDecision(
                false,
                true,
                false
        );

        assertEquals(CommandBoundaries.RemoveTargetDecision.UUID_MODE_OFFLINE_NAME_LOOKUP, decision);
    }

    /**
     * 移除命令接受直接 UUID 输入和在线玩家名
     */
    @Test
    void uuidModeRemoveAcceptsUuidInputAndOnlineNames() {
        assertEquals(
                CommandBoundaries.RemoveTargetDecision.UUID_INPUT,
                CommandBoundaries.removeTargetDecision(true, false, false)
        );
        assertEquals(
                CommandBoundaries.RemoveTargetDecision.ONLINE_UUID_NAME,
                CommandBoundaries.removeTargetDecision(false, true, true)
        );
    }

    /**
     * 非 UUID 且非合法玩家名的输入应被拒绝
     */
    @Test
    void removeRejectsInvalidIdentifier() {
        CommandBoundaries.RemoveTargetDecision decision = CommandBoundaries.removeTargetDecision(
                false,
                false,
                false
        );

        assertEquals(CommandBoundaries.RemoveTargetDecision.INVALID_IDENTIFIER, decision);
    }

    @Test
    void generatePrefersOnlinePlayerBeforeMojangLookup() {
        CommandBoundaries.GenerateTargetDecision decision = CommandBoundaries.generateTargetDecision(
                false,
                true,
                true
        );

        assertEquals(CommandBoundaries.GenerateTargetDecision.ONLINE_PLAYER, decision);
    }

    @Test
    void generateUsesMojangLookupForOfflineTargets() {
        assertEquals(
                CommandBoundaries.GenerateTargetDecision.UUID_LOOKUP,
                CommandBoundaries.generateTargetDecision(true, false, false)
        );
        assertEquals(
                CommandBoundaries.GenerateTargetDecision.NAME_LOOKUP,
                CommandBoundaries.generateTargetDecision(false, true, false)
        );
    }

    @Test
    void generateRejectsInvalidOfflineIdentifier() {
        CommandBoundaries.GenerateTargetDecision decision = CommandBoundaries.generateTargetDecision(
                false,
                false,
                false
        );

        assertEquals(CommandBoundaries.GenerateTargetDecision.INVALID_IDENTIFIER, decision);
    }
}
