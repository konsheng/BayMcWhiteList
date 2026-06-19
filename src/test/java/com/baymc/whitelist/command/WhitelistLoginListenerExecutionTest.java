package com.baymc.whitelist.command;

import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.identity.PlayerIdentityResolver;
import com.baymc.whitelist.listener.WhitelistLoginListener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 针对受保护服务器预登录白名单检查的 UUID 来源行为测试
 */
class WhitelistLoginListenerExecutionTest {
    @Test
    void protectedLoginUsesOfflineNameUuidWhenConfigured() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(
                        PluginConfig.ServerMode.PROTECTED,
                        true,
                        PluginConfig.UuidSource.OFFLINE_NAME
                ),
                true
        );
        String offlineUuid = PlayerIdentityResolver.offlineNameUuid("Notch").toString();
        when(runtime.repository().isWhitelisted(offlineUuid)).thenReturn(true);
        WhitelistLoginListener listener = new WhitelistLoginListener(runtime.plugin());
        AsyncPlayerPreLoginEvent event = preLoginEvent();

        listener.onPreLogin(event);

        verify(runtime.repository()).isWhitelisted(offlineUuid);
        verify(runtime.repository(), never()).isWhitelisted(CommandTestSupport.PLAYER_UUID_TEXT);
        verify(runtime.repository()).updateLastSeen(eqString(offlineUuid), any(LocalDateTime.class));
    }

    @Test
    void protectedLoginUsesServerUuidWhenConfigured() throws Exception {
        CommandTestSupport.RuntimeHarness runtime = CommandTestSupport.runtime(
                CommandTestSupport.config(
                        PluginConfig.ServerMode.PROTECTED,
                        true,
                        PluginConfig.UuidSource.SERVER
                ),
                true
        );
        when(runtime.repository().isWhitelisted(CommandTestSupport.PLAYER_UUID_TEXT)).thenReturn(true);
        WhitelistLoginListener listener = new WhitelistLoginListener(runtime.plugin());
        AsyncPlayerPreLoginEvent event = preLoginEvent();

        listener.onPreLogin(event);

        verify(runtime.repository()).isWhitelisted(CommandTestSupport.PLAYER_UUID_TEXT);
        verify(runtime.repository()).updateLastSeen(eqString(CommandTestSupport.PLAYER_UUID_TEXT), any(LocalDateTime.class));
    }

    private static AsyncPlayerPreLoginEvent preLoginEvent() throws Exception {
        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        when(event.getUniqueId()).thenReturn(CommandTestSupport.PLAYER_UUID);
        when(event.getName()).thenReturn("Notch");
        when(event.getAddress()).thenReturn(InetAddress.getByName("127.0.0.1"));
        return event;
    }

    private static String eqString(String value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
