package com.baymc.whitelist.command;

import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.code.VerificationResult;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.identity.PlayerIdentityResolver;
import com.baymc.whitelist.storage.WhitelistLogEntry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 针对玩家侧 /whitelist 命令执行边界和运行期快照的测试
 */
class WhitelistCommandExecutionTest {
    @Test
    void rejectsConsoleSenderBeforeDatabaseAccess() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.LOGIN),
                true
        );
        WhitelistCommand command = new WhitelistCommand(runtime.plugin());
        CommandSender console = CommandTestSupport.sender("Console", Set.of());

        command.onCommand(console, CommandTestSupport.command(), "whitelist", new String[]{"BAYMC-ABCDEFGH"});

        verify(runtime.lang()).send(console, "common.only-player");
        verify(runtime.repository(), never()).isWhitelisted(org.mockito.ArgumentMatchers.anyString());
        org.junit.jupiter.api.Assertions.assertEquals(1, runtime.closeCount().get());
    }

    @Test
    void rejectsPlayerWithoutUsePermission() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.LOGIN),
                true
        );
        WhitelistCommand command = new WhitelistCommand(runtime.plugin());
        Player player = CommandTestSupport.player("Notch", CommandTestSupport.PLAYER_UUID, Set.of());

        command.onCommand(player, CommandTestSupport.command(), "whitelist", new String[]{"BAYMC-ABCDEFGH"});

        verify(runtime.lang()).send(player, "common.no-permission");
        verify(runtime.repository(), never()).isWhitelisted(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsInviteSubmissionOutsideLoginServer() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.PROTECTED),
                true
        );
        WhitelistCommand command = new WhitelistCommand(runtime.plugin());
        Player player = CommandTestSupport.player("Notch", CommandTestSupport.PLAYER_UUID, Set.of("baymcwhitelist.use"));

        command.onCommand(player, CommandTestSupport.command(), "whitelist", new String[]{"BAYMC-ABCDEFGH"});

        verify(runtime.lang()).send(player, "code.login-server-only");
        verify(runtime.repository(), never()).isWhitelisted(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsExtraInviteArgumentsWithUsage() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.LOGIN),
                true
        );
        WhitelistCommand command = new WhitelistCommand(runtime.plugin());
        Player player = CommandTestSupport.player("Notch", CommandTestSupport.PLAYER_UUID, Set.of("baymcwhitelist.use"));

        command.onCommand(player, CommandTestSupport.command(), "whitelist", new String[]{"one", "two"});

        verify(runtime.lang()).send(player, "usage.whitelist");
        verify(runtime.repository(), never()).isWhitelisted(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void selfStatusQueriesOnlyCurrentPlayerUuid() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.PROTECTED),
                true
        );
        when(runtime.repository().findByUuid(CommandTestSupport.PLAYER_UUID_TEXT)).thenReturn(Optional.empty());
        WhitelistCommand command = new WhitelistCommand(runtime.plugin());
        Player player = CommandTestSupport.player(
                "Notch",
                CommandTestSupport.PLAYER_UUID,
                Set.of("baymcwhitelist.status.self")
        );

        command.onCommand(player, CommandTestSupport.command(), "whitelist", new String[]{});

        verify(runtime.repository()).findByUuid(CommandTestSupport.PLAYER_UUID_TEXT);
        verify(runtime.lang()).send(eq(player), eq("player.status-not-whitelisted"), anyMap());
        org.junit.jupiter.api.Assertions.assertEquals(1, runtime.closeCount().get());
    }

    @Test
    void asyncSelfStatusUsesRuntimeSnapshotCapturedAtCommandStart() throws Exception {
        CommandTestSupport.RuntimeHarness firstRuntime = CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.PROTECTED),
                true
        );
        CommandTestSupport.RuntimeHarness secondRuntime = CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.PROTECTED),
                true
        );
        when(firstRuntime.plugin().runtimeState()).thenReturn(firstRuntime.state(), secondRuntime.state());
        when(firstRuntime.repository().findByUuid(CommandTestSupport.PLAYER_UUID_TEXT)).thenReturn(Optional.empty());
        AtomicReference<Runnable> asyncTask = new AtomicReference<>();
        doAnswer(invocation -> {
            asyncTask.set(invocation.getArgument(0, Runnable.class));
            return null;
        }).when(firstRuntime.scheduler()).runAsync(any(Runnable.class));

        WhitelistCommand command = new WhitelistCommand(firstRuntime.plugin());
        Player player = CommandTestSupport.player(
                "Notch",
                CommandTestSupport.PLAYER_UUID,
                Set.of("baymcwhitelist.status.self")
        );

        command.onCommand(player, CommandTestSupport.command(), "whitelist", new String[]{});
        asyncTask.get().run();

        verify(firstRuntime.plugin(), times(1)).runtimeState();
        verify(firstRuntime.repository()).findByUuid(CommandTestSupport.PLAYER_UUID_TEXT);
        verifyNoInteractions(secondRuntime.repository());
        verify(firstRuntime.lang()).send(eq(player), eq("player.status-not-whitelisted"), anyMap());
    }

    @Test
    void inviteVerificationUsesOfflineNameUuidWhenConfigured() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(
                        PluginConfig.ServerMode.LOGIN,
                        true,
                        PluginConfig.UuidSource.OFFLINE_NAME
                ),
                true
        );
        String offlineUuid = PlayerIdentityResolver.offlineNameUuid("Notch").toString();
        when(runtime.repository().isWhitelisted(offlineUuid)).thenReturn(false);
        when(runtime.inviteCodeService().verify("BAYMC-ABCDEFGH", offlineUuid))
                .thenReturn(VerificationResult.valid(
                        "BAYMC-ABCDEFGH",
                        LocalDate.parse("2026-06-19"),
                        ZonedDateTime.of(2026, 6, 25, 23, 59, 59, 0, ZoneId.of("Asia/Shanghai"))
                ));
        WhitelistCommand command = new WhitelistCommand(runtime.plugin());
        Player player = CommandTestSupport.player(
                "Notch",
                CommandTestSupport.PLAYER_UUID,
                Set.of("baymcwhitelist.use")
        );

        command.onCommand(player, CommandTestSupport.command(), "whitelist", new String[]{"BAYMC-ABCDEFGH"});

        verify(runtime.repository(), never()).isWhitelisted(CommandTestSupport.PLAYER_UUID_TEXT);
        verify(runtime.inviteCodeService()).verify("BAYMC-ABCDEFGH", offlineUuid);
        org.mockito.ArgumentCaptor<PlayerIdentity> identity = org.mockito.ArgumentCaptor.forClass(PlayerIdentity.class);
        verify(runtime.repository()).upsert(
                identity.capture(),
                eq("BAYMC-ABCDEFGH"),
                any(LocalDate.class),
                any(java.time.LocalDateTime.class)
        );
        org.junit.jupiter.api.Assertions.assertEquals(offlineUuid, identity.getValue().uuidText());
    }

    @Test
    void inviteVerificationWritesSuccessAuditLog() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.LOGIN),
                true
        );
        when(runtime.repository().isWhitelisted(CommandTestSupport.PLAYER_UUID_TEXT)).thenReturn(false);
        when(runtime.inviteCodeService().verify("BAYMC-ABCDEFGH", CommandTestSupport.PLAYER_UUID_TEXT))
                .thenReturn(VerificationResult.valid(
                        "BAYMC-ABCDEFGH",
                        LocalDate.parse("2026-06-19"),
                        ZonedDateTime.of(2026, 6, 25, 23, 59, 59, 0, ZoneId.of("Asia/Shanghai"))
                ));
        WhitelistCommand command = new WhitelistCommand(runtime.plugin());
        Player player = CommandTestSupport.player(
                "Notch",
                CommandTestSupport.PLAYER_UUID,
                Set.of("baymcwhitelist.use")
        );

        command.onCommand(player, CommandTestSupport.command(), "whitelist", new String[]{"BAYMC-ABCDEFGH"});

        org.mockito.ArgumentCaptor<WhitelistLogEntry> logEntry = org.mockito.ArgumentCaptor.forClass(WhitelistLogEntry.class);
        verify(runtime.repository()).log(logEntry.capture());
        org.junit.jupiter.api.Assertions.assertEquals("VERIFY_SUCCESS", logEntry.getValue().action());
        org.junit.jupiter.api.Assertions.assertEquals("BAYMC-ABCDEFGH", logEntry.getValue().code());
        org.junit.jupiter.api.Assertions.assertEquals("127.0.0.1", logEntry.getValue().ip());
        org.junit.jupiter.api.Assertions.assertEquals(1, runtime.closeCount().get());
    }

    @Test
    void invalidInviteFormatWritesAttemptLogAndKeepsPlayerFeedback() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.LOGIN),
                true
        );
        when(runtime.repository().isWhitelisted(CommandTestSupport.PLAYER_UUID_TEXT)).thenReturn(false);
        when(runtime.inviteCodeService().verify("bad-code", CommandTestSupport.PLAYER_UUID_TEXT))
                .thenReturn(VerificationResult.invalidFormat());
        WhitelistCommand command = new WhitelistCommand(runtime.plugin());
        Player player = CommandTestSupport.player(
                "Notch",
                CommandTestSupport.PLAYER_UUID,
                Set.of("baymcwhitelist.use")
        );

        command.onCommand(player, CommandTestSupport.command(), "whitelist", new String[]{"bad-code"});

        org.mockito.ArgumentCaptor<WhitelistLogEntry> logEntry = org.mockito.ArgumentCaptor.forClass(WhitelistLogEntry.class);
        verify(runtime.repository()).log(logEntry.capture());
        org.junit.jupiter.api.Assertions.assertEquals("VERIFY_INVALID_FORMAT", logEntry.getValue().action());
        org.junit.jupiter.api.Assertions.assertEquals("bad-code", logEntry.getValue().code());
        org.junit.jupiter.api.Assertions.assertEquals("invalid_format", logEntry.getValue().message());
        verify(runtime.lang()).send(eq(player), eq("code.invalid-format"), anyMap());
        verify(runtime.repository(), never()).upsert(
                any(PlayerIdentity.class),
                anyString(),
                any(LocalDate.class),
                any(java.time.LocalDateTime.class)
        );
    }
}
