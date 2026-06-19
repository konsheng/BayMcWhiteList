package com.baymc.whitelist.command;

import com.baymc.whitelist.config.PluginConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandBoundariesTest {
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

    @Test
    void whitelistWithOneArgumentVerifiesInviteCode() {
        assertEquals(
                CommandBoundaries.WhitelistDecision.VERIFY,
                CommandBoundaries.whitelistDecision(true, true, true, PluginConfig.ServerMode.LOGIN, 1)
        );
    }

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

    @Test
    void adminTargetCommandsRequireOneTargetArgument() {
        assertFalse(CommandBoundaries.hasExactArgumentCount(new String[]{"generate"}, 2));
        assertTrue(CommandBoundaries.hasExactArgumentCount(new String[]{"generate", "Steve"}, 2));
        assertFalse(CommandBoundaries.hasExactArgumentCount(new String[]{"generate", "Steve", "extra"}, 2));
    }

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

    @Test
    void addSubcommandUsesIndependentPermission() {
        assertEquals("baymcwhitelist.add", CommandBoundaries.permissionFor("add"));

        List<String> visible = CommandBoundaries.visibleAdminSubcommands(
                "baymcwhitelist.add"::equals,
                ""
        );

        assertEquals(List.of("add"), visible);
    }

    @Test
    void helpSubcommandUsesIndependentPermission() {
        assertEquals("baymcwhitelist.help", CommandBoundaries.permissionFor("help"));

        List<String> visible = CommandBoundaries.visibleAdminSubcommands(
                "baymcwhitelist.help"::equals,
                ""
        );

        assertEquals(List.of("help"), visible);
    }

    @Test
    void adminTabCompletionHidesAllSubcommandsWithoutPermissions() {
        List<String> visible = CommandBoundaries.visibleAdminSubcommands(permission -> false, "");

        assertEquals(List.of(), visible);
    }
}
