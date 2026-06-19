package com.baymc.whitelist.command;

import com.baymc.whitelist.code.GeneratedCode;
import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.identity.PlayerIdentityResolver;
import com.baymc.whitelist.mojang.MojangProfile;
import com.baymc.whitelist.storage.WhitelistRecord;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 针对管理员命令执行路径的参数, 权限和 UUID 来源行为测试
 */
class BayMcWhiteListCommandExecutionTest {
    @ParameterizedTest
    @MethodSource("usageCases")
    void adminCommandsEnforceExactArgumentCounts(String permission, String[] args, String usageKey) {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.LOGIN),
                true
        );
        BayMcWhiteListCommand command = new BayMcWhiteListCommand(runtime.plugin());
        CommandSender sender = CommandTestSupport.sender("Admin", Set.of(permission));

        command.onCommand(sender, CommandTestSupport.command(), "baymcwhitelist", args);

        verify(runtime.lang()).send(sender, usageKey);
    }

    static Stream<Arguments> usageCases() {
        return Stream.of(
                Arguments.of("baymcwhitelist.add", new String[]{"add"}, "usage.add"),
                Arguments.of("baymcwhitelist.add", new String[]{"add", "Notch", "extra"}, "usage.add"),
                Arguments.of("baymcwhitelist.generate", new String[]{"generate"}, "usage.generate"),
                Arguments.of("baymcwhitelist.generate", new String[]{"generate", "Notch", "extra"}, "usage.generate"),
                Arguments.of("baymcwhitelist.status", new String[]{"status"}, "usage.status"),
                Arguments.of("baymcwhitelist.status", new String[]{"status", "Notch", "extra"}, "usage.status"),
                Arguments.of("baymcwhitelist.remove", new String[]{"remove"}, "usage.remove"),
                Arguments.of("baymcwhitelist.remove", new String[]{"remove", "Notch", "extra"}, "usage.remove"),
                Arguments.of("baymcwhitelist.reload", new String[]{"reload", "extra"}, "usage.admin"),
                Arguments.of("baymcwhitelist.info", new String[]{"info", "extra"}, "usage.info"),
                Arguments.of("baymcwhitelist.help", new String[]{"help", "extra"}, "usage.help")
        );
    }

    @Test
    void tabCompletionOnlyShowsPermittedSubcommands() {
        BayMcWhiteListCommand command = new BayMcWhiteListCommand(CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.LOGIN),
                true
        ).plugin());
        CommandSender sender = CommandTestSupport.sender(
                "Admin",
                Set.of("baymcwhitelist.add", "baymcwhitelist.help")
        );

        List<String> completions = command.onTabComplete(sender, CommandTestSupport.command(), "wl", new String[]{""});

        assertEquals(List.of("add", "help"), completions);
    }

    @Test
    void tabCompletionHidesOnlinePlayerNamesWithoutSubcommandPermission() {
        BayMcWhiteListCommand command = new BayMcWhiteListCommand(CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.LOGIN),
                true
        ).plugin());
        CommandSender sender = CommandTestSupport.sender("Admin", Set.of());

        List<String> completions = command.onTabComplete(sender, CommandTestSupport.command(), "wl",
                new String[]{"remove", "No"});

        assertEquals(List.of(), completions);
    }

    @Test
    void tabCompletionShowsOnlinePlayerNamesOnlyForPermittedTargetCommands() {
        BayMcWhiteListCommand command = new BayMcWhiteListCommand(CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.LOGIN),
                true
        ).plugin());
        CommandSender sender = CommandTestSupport.sender("Admin", Set.of("baymcwhitelist.generate"));
        Player notch = CommandTestSupport.player("Notch", CommandTestSupport.PLAYER_UUID, Set.of());
        Player steve = CommandTestSupport.player("Steve", java.util.UUID.randomUUID(), Set.of());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(notch, steve));

            List<String> completions = command.onTabComplete(sender, CommandTestSupport.command(), "wl",
                    new String[]{"generate", "No"});

            assertEquals(List.of("Notch"), completions);
        }
    }

    @Test
    void addNameResolvesMojangProfileAndWritesUuid() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.LOGIN),
                true
        );
        when(runtime.mojangProfileService().lookupByName("Notch"))
                .thenReturn(Optional.of(profile()));
        when(runtime.repository().isWhitelisted(CommandTestSupport.PLAYER_UUID_TEXT)).thenReturn(false);
        when(runtime.repository().insertManual(any(PlayerIdentity.class), any(LocalDate.class), any(LocalDateTime.class)))
                .thenReturn(true);
        BayMcWhiteListCommand command = new BayMcWhiteListCommand(runtime.plugin());
        CommandSender sender = CommandTestSupport.sender("Admin", Set.of("baymcwhitelist.add"));

        command.onCommand(sender, CommandTestSupport.command(), "wl", new String[]{"add", "Notch"});

        verify(runtime.repository()).isWhitelisted(CommandTestSupport.PLAYER_UUID_TEXT);
        ArgumentCaptor<PlayerIdentity> identity = ArgumentCaptor.forClass(PlayerIdentity.class);
        verify(runtime.repository()).insertManual(identity.capture(), any(LocalDate.class), any(LocalDateTime.class));
        assertEquals(CommandTestSupport.PLAYER_UUID, identity.getValue().uuid());
        assertEquals("Notch", identity.getValue().name());
    }

    @Test
    void generateNameResolvesMojangProfileAndSignsUuid() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.LOGIN),
                true
        );
        when(runtime.mojangProfileService().lookupByName("Notch"))
                .thenReturn(Optional.of(profile()));
        when(runtime.inviteCodeService().generate(CommandTestSupport.PLAYER_UUID_TEXT))
                .thenReturn(new GeneratedCode(
                        "BAYMC-ABCDEFGH",
                        LocalDate.parse("2026-06-19"),
                        ZonedDateTime.of(2026, 6, 25, 23, 59, 59, 0, ZoneId.of("Asia/Shanghai"))
                ));
        BayMcWhiteListCommand command = new BayMcWhiteListCommand(runtime.plugin());
        CommandSender sender = CommandTestSupport.sender("Admin", Set.of("baymcwhitelist.generate"));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Notch")).thenReturn(null);

            command.onCommand(sender, CommandTestSupport.command(), "wl", new String[]{"generate", "Notch"});
        }

        verify(runtime.inviteCodeService()).generate(CommandTestSupport.PLAYER_UUID_TEXT);
        verify(runtime.lang()).send(eq(sender), eq("admin.generate-success"), anyMap());
    }

    @Test
    void statusNameResolvesMojangProfileBeforeDatabaseLookup() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.LOGIN),
                true
        );
        when(runtime.mojangProfileService().lookupByName("Notch"))
                .thenReturn(Optional.of(profile()));
        when(runtime.repository().findByUuid(CommandTestSupport.PLAYER_UUID_TEXT)).thenReturn(Optional.empty());
        BayMcWhiteListCommand command = new BayMcWhiteListCommand(runtime.plugin());
        CommandSender sender = CommandTestSupport.sender("Admin", Set.of("baymcwhitelist.status"));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Notch")).thenReturn(null);

            command.onCommand(sender, CommandTestSupport.command(), "wl", new String[]{"status", "Notch"});
        }

        verify(runtime.mojangProfileService()).lookupByName("Notch");
        verify(runtime.repository()).findByUuid(CommandTestSupport.PLAYER_UUID_TEXT);
        verify(runtime.lang()).send(eq(sender), eq("admin.status-not-whitelisted"), anyMap());
    }

    @Test
    void removeNameResolvesMojangProfileAndDeletesUuid() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(PluginConfig.ServerMode.LOGIN, false),
                true
        );
        when(runtime.mojangProfileService().lookupByName("Notch"))
                .thenReturn(Optional.of(profile()));
        when(runtime.repository().findByUuid(CommandTestSupport.PLAYER_UUID_TEXT))
                .thenReturn(Optional.of(record()));
        when(runtime.repository().removeByUuid(CommandTestSupport.PLAYER_UUID_TEXT)).thenReturn(true);
        BayMcWhiteListCommand command = new BayMcWhiteListCommand(runtime.plugin());
        CommandSender sender = CommandTestSupport.sender("Admin", Set.of("baymcwhitelist.remove"));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Notch")).thenReturn(null);

            command.onCommand(sender, CommandTestSupport.command(), "wl", new String[]{"remove", "Notch"});
        }

        verify(runtime.mojangProfileService()).lookupByName("Notch");
        verify(runtime.repository()).findByUuid(CommandTestSupport.PLAYER_UUID_TEXT);
        verify(runtime.repository()).removeByUuid(CommandTestSupport.PLAYER_UUID_TEXT);
        verify(runtime.lang()).send(eq(sender), eq("admin.remove-success"), anyMap());
    }

    @Test
    void addOfflineNameComputesUuidWithoutMojangLookup() throws Exception {
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
        when(runtime.repository().insertManual(any(PlayerIdentity.class), any(LocalDate.class), any(LocalDateTime.class)))
                .thenReturn(true);
        BayMcWhiteListCommand command = new BayMcWhiteListCommand(runtime.plugin());
        CommandSender sender = CommandTestSupport.sender("Admin", Set.of("baymcwhitelist.add"));

        command.onCommand(sender, CommandTestSupport.command(), "wl", new String[]{"add", "Notch"});

        verifyNoInteractions(runtime.mojangProfileService());
        ArgumentCaptor<PlayerIdentity> identity = ArgumentCaptor.forClass(PlayerIdentity.class);
        verify(runtime.repository()).insertManual(identity.capture(), any(LocalDate.class), any(LocalDateTime.class));
        assertEquals(offlineUuid, identity.getValue().uuidText());
        assertEquals("Notch", identity.getValue().name());
    }

    @Test
    void generateOfflineNameSignsComputedUuid() {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(
                        PluginConfig.ServerMode.LOGIN,
                        true,
                        PluginConfig.UuidSource.OFFLINE_NAME
                ),
                true
        );
        String offlineUuid = PlayerIdentityResolver.offlineNameUuid("Notch").toString();
        when(runtime.inviteCodeService().generate(offlineUuid))
                .thenReturn(new GeneratedCode(
                        "BAYMC-ABCDEFGH",
                        LocalDate.parse("2026-06-19"),
                        ZonedDateTime.of(2026, 6, 25, 23, 59, 59, 0, ZoneId.of("Asia/Shanghai"))
                ));
        BayMcWhiteListCommand command = new BayMcWhiteListCommand(runtime.plugin());
        CommandSender sender = CommandTestSupport.sender("Admin", Set.of("baymcwhitelist.generate"));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Notch")).thenReturn(null);

            command.onCommand(sender, CommandTestSupport.command(), "wl", new String[]{"generate", "Notch"});
        }

        verifyNoInteractions(runtime.mojangProfileService());
        verify(runtime.inviteCodeService()).generate(offlineUuid);
        verify(runtime.lang()).send(eq(sender), eq("admin.generate-identity-resolved"), anyMap());
    }

    @Test
    void serverSourceRejectsOfflinePlayerName() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(
                        PluginConfig.ServerMode.LOGIN,
                        true,
                        PluginConfig.UuidSource.SERVER
                ),
                true
        );
        BayMcWhiteListCommand command = new BayMcWhiteListCommand(runtime.plugin());
        CommandSender sender = CommandTestSupport.sender("Admin", Set.of("baymcwhitelist.remove"));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Notch")).thenReturn(null);

            command.onCommand(sender, CommandTestSupport.command(), "wl", new String[]{"remove", "Notch"});
        }

        verify(runtime.lang()).send(eq(sender), eq("admin.server-source-offline-name-unsupported"), anyMap());
        verify(runtime.repository(), never()).findByUuid(anyString());
        verifyNoInteractions(runtime.mojangProfileService());
    }

    @Test
    void serverSourceRemoveAcceptsUuidInput() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(
                        PluginConfig.ServerMode.LOGIN,
                        false,
                        PluginConfig.UuidSource.SERVER
                ),
                true
        );
        when(runtime.repository().findByUuid(CommandTestSupport.PLAYER_UUID_TEXT))
                .thenReturn(Optional.of(record()));
        when(runtime.repository().removeByUuid(CommandTestSupport.PLAYER_UUID_TEXT)).thenReturn(true);
        BayMcWhiteListCommand command = new BayMcWhiteListCommand(runtime.plugin());
        CommandSender sender = CommandTestSupport.sender("Admin", Set.of("baymcwhitelist.remove"));

        command.onCommand(sender, CommandTestSupport.command(), "wl",
                new String[]{"remove", CommandTestSupport.PLAYER_UUID_TEXT});

        verify(runtime.repository()).findByUuid(CommandTestSupport.PLAYER_UUID_TEXT);
        verify(runtime.repository()).removeByUuid(CommandTestSupport.PLAYER_UUID_TEXT);
        verifyNoInteractions(runtime.mojangProfileService());
        verify(runtime.lang()).send(eq(sender), eq("admin.remove-success"), anyMap());
    }

    @Test
    void offlineNameStatusComputesUuidWithoutMojangLookup() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(
                        PluginConfig.ServerMode.LOGIN,
                        true,
                        PluginConfig.UuidSource.OFFLINE_NAME
                ),
                true
        );
        String offlineUuid = PlayerIdentityResolver.offlineNameUuid("Notch").toString();
        when(runtime.repository().findByUuid(offlineUuid)).thenReturn(Optional.empty());
        BayMcWhiteListCommand command = new BayMcWhiteListCommand(runtime.plugin());
        CommandSender sender = CommandTestSupport.sender("Admin", Set.of("baymcwhitelist.status"));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Notch")).thenReturn(null);

            command.onCommand(sender, CommandTestSupport.command(), "wl", new String[]{"status", "Notch"});
        }

        verify(runtime.repository()).findByUuid(offlineUuid);
        verifyNoInteractions(runtime.mojangProfileService());
        verify(runtime.lang()).send(eq(sender), eq("admin.status-not-whitelisted"), anyMap());
    }

    @Test
    void offlineNameRemoveComputesUuidWithoutMojangLookup() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(
                        PluginConfig.ServerMode.LOGIN,
                        false,
                        PluginConfig.UuidSource.OFFLINE_NAME
                ),
                true
        );
        String offlineUuid = PlayerIdentityResolver.offlineNameUuid("Notch").toString();
        when(runtime.repository().findByUuid(offlineUuid))
                .thenReturn(Optional.of(record(offlineUuid)));
        when(runtime.repository().removeByUuid(offlineUuid)).thenReturn(true);
        BayMcWhiteListCommand command = new BayMcWhiteListCommand(runtime.plugin());
        CommandSender sender = CommandTestSupport.sender("Admin", Set.of("baymcwhitelist.remove"));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Notch")).thenReturn(null);

            command.onCommand(sender, CommandTestSupport.command(), "wl", new String[]{"remove", "Notch"});
        }

        verify(runtime.repository()).findByUuid(offlineUuid);
        verify(runtime.repository()).removeByUuid(offlineUuid);
        verifyNoInteractions(runtime.mojangProfileService());
        verify(runtime.lang()).send(eq(sender), eq("admin.remove-success"), anyMap());
    }

    @Test
    void serverSourceOnlineNameUsesServerUuid() {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(
                        PluginConfig.ServerMode.LOGIN,
                        true,
                        PluginConfig.UuidSource.SERVER
                ),
                true
        );
        when(runtime.inviteCodeService().generate(CommandTestSupport.PLAYER_UUID_TEXT))
                .thenReturn(new GeneratedCode(
                        "BAYMC-ABCDEFGH",
                        LocalDate.parse("2026-06-19"),
                        ZonedDateTime.of(2026, 6, 25, 23, 59, 59, 0, ZoneId.of("Asia/Shanghai"))
                ));
        BayMcWhiteListCommand command = new BayMcWhiteListCommand(runtime.plugin());
        CommandSender sender = CommandTestSupport.sender("Admin", Set.of("baymcwhitelist.generate"));
        Player player = CommandTestSupport.player("Notch", CommandTestSupport.PLAYER_UUID, Set.of());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Notch")).thenReturn(player);

            command.onCommand(sender, CommandTestSupport.command(), "wl", new String[]{"generate", "Notch"});
        }

        verify(runtime.inviteCodeService()).generate(CommandTestSupport.PLAYER_UUID_TEXT);
        verifyNoInteractions(runtime.mojangProfileService());
    }

    private static MojangProfile profile() {
        return new MojangProfile(CommandTestSupport.PLAYER_UUID, "Notch");
    }

    private static WhitelistRecord record() {
        return record(CommandTestSupport.PLAYER_UUID_TEXT);
    }

    private static WhitelistRecord record(String playerUuid) {
        return new WhitelistRecord(
                playerUuid,
                "Notch",
                "BAYMC-ABCDEFGH",
                LocalDate.parse("2026-06-19"),
                LocalDateTime.parse("2026-06-19T12:00:00"),
                "login",
                null
        );
    }
}
