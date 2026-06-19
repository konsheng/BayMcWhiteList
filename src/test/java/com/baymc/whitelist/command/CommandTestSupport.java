package com.baymc.whitelist.command;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.code.InviteCodeService;
import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.lang.LangManager;
import com.baymc.whitelist.mojang.MojangProfileService;
import com.baymc.whitelist.scheduler.PlatformScheduler;
import com.baymc.whitelist.security.VerifyRateLimiter;
import com.baymc.whitelist.storage.WhitelistRepository;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 命令执行测试共用的配置, 运行期和 Bukkit 对象构造工具
 */
final class CommandTestSupport {
    static final UUID PLAYER_UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    static final String PLAYER_UUID_TEXT = PLAYER_UUID.toString();

    private CommandTestSupport() {
    }

    static PluginConfig config(PluginConfig.ServerMode mode) {
        return config(mode, true);
    }

    static PluginConfig config(PluginConfig.ServerMode mode, boolean kickOnlinePlayer) {
        return config(mode, kickOnlinePlayer, PluginConfig.UuidSource.MOJANG);
    }

    static PluginConfig config(
            PluginConfig.ServerMode mode,
            boolean kickOnlinePlayer,
            PluginConfig.UuidSource uuidSource
    ) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("server.mode", mode.name().toLowerCase());
        yaml.set("code.secret", "unit-test-secret-with-enough-length");
        yaml.set("remove.kick-online-player", kickOnlinePlayer);
        yaml.set("player.uuid-source", uuidSource.name().toLowerCase().replace('_', '-'));
        return PluginConfig.load(yaml);
    }

    static RuntimeHarness runtime(PluginConfig config, boolean databaseReady) {
        LangManager lang = mock(LangManager.class);
        PlatformScheduler scheduler = mock(PlatformScheduler.class);
        WhitelistRepository repository = mock(WhitelistRepository.class);
        InviteCodeService inviteCodeService = mock(InviteCodeService.class);
        MojangProfileService mojangProfileService = mock(MojangProfileService.class);
        VerifyRateLimiter rateLimiter = new VerifyRateLimiter(
                config.security().verifyRateLimit(),
                Clock.fixed(Instant.parse("2026-06-19T00:00:00Z"), ZoneId.of("UTC"))
        );
        AtomicInteger closeCount = new AtomicInteger();

        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(scheduler).runAsync(any(Runnable.class));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(scheduler).runGlobal(any(Runnable.class));
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(scheduler).runForSender(any(CommandSender.class), any(Runnable.class));
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(scheduler).runForPlayer(any(Player.class), any(Runnable.class));

        when(lang.plain("state.none")).thenReturn("none");
        when(lang.plain("state.uuid-source-mojang")).thenReturn("Mojang UUID");
        when(lang.plain("state.uuid-source-offline-name")).thenReturn("Offline Name UUID");
        when(lang.plain("state.uuid-source-server")).thenReturn("Server UUID");
        when(lang.plain("state.mode-login")).thenReturn("login");
        when(lang.plain("state.mode-protected")).thenReturn("protected");
        when(lang.plain("state.database-ready")).thenReturn("ready");
        when(lang.plain("state.database-unavailable")).thenReturn("unavailable");
        when(lang.plain("security.scope-player")).thenReturn("player");
        when(lang.plain("security.scope-ip")).thenReturn("ip");

        BayMcWhiteListPlugin.RuntimeState state = new BayMcWhiteListPlugin.RuntimeState(
                config,
                lang,
                scheduler,
                repository,
                inviteCodeService,
                mojangProfileService,
                rateLimiter,
                null,
                closeCount::incrementAndGet,
                databaseReady
        );
        BayMcWhiteListPlugin plugin = mock(BayMcWhiteListPlugin.class);
        when(plugin.runtimeState()).thenReturn(state);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BayMcWhiteListTest"));

        return new RuntimeHarness(plugin, state, lang, scheduler, repository, inviteCodeService,
                mojangProfileService, closeCount);
    }

    static Player player(String name, UUID uuid, Set<String> permissions) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 25565));
        when(player.hasPermission(any(String.class))).thenAnswer(invocation ->
                permissions.contains(invocation.getArgument(0, String.class)));
        return player;
    }

    static CommandSender sender(String name, Set<String> permissions) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn(name);
        when(sender.hasPermission(any(String.class))).thenAnswer(invocation ->
                permissions.contains(invocation.getArgument(0, String.class)));
        return sender;
    }

    static Command command() {
        return mock(Command.class);
    }

    /**
     * 一次命令测试中用到的运行期快照和可验证依赖
     */
    record RuntimeHarness(
            BayMcWhiteListPlugin plugin,
            BayMcWhiteListPlugin.RuntimeState state,
            LangManager lang,
            PlatformScheduler scheduler,
            WhitelistRepository repository,
            InviteCodeService inviteCodeService,
            MojangProfileService mojangProfileService,
            AtomicInteger closeCount
    ) {
    }
}
