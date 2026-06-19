package com.baymc.whitelist.command;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.command.whitelist.WhitelistAttemptLogger;
import com.baymc.whitelist.command.whitelist.WhitelistPlayerView;
import com.baymc.whitelist.command.whitelist.WhitelistSecurityFeedback;
import com.baymc.whitelist.command.whitelist.WhitelistStatusAction;
import com.baymc.whitelist.command.whitelist.WhitelistVerifyAction;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.identity.PlayerIdentityResolver;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 处理 /whitelist 邀请码验证和自助状态查询命令入口
 *
 * <p>本类只保留 Bukkit 命令入口, 玩家身份边界判断, 数据库可用性检查和动作分发;
 * 状态查询, 邀请码验证, 限流反馈和审计日志分别交给 command.whitelist 包中的组件处理
 */
public final class WhitelistCommand implements TabExecutor {
    private final BayMcWhiteListPlugin plugin;
    private final CommandMessages messages;
    private final WhitelistStatusAction statusAction;
    private final WhitelistVerifyAction verifyAction;

    /**
     * 创建玩家命令入口及其专属动作组件
     */
    public WhitelistCommand(BayMcWhiteListPlugin plugin) {
        this.plugin = plugin;
        this.messages = new CommandMessages();
        WhitelistPlayerView playerView = new WhitelistPlayerView();
        WhitelistAttemptLogger attemptLogger = new WhitelistAttemptLogger(plugin.getLogger());
        WhitelistSecurityFeedback securityFeedback = new WhitelistSecurityFeedback(playerView, attemptLogger);
        this.statusAction = new WhitelistStatusAction(playerView);
        this.verifyAction = new WhitelistVerifyAction(attemptLogger, securityFeedback);
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        BayMcWhiteListPlugin.RuntimeState runtime = plugin.runtimeState();
        CommandExecution execution = CommandExecution.FINISHED;
        try {
            Player player = sender instanceof Player playerSender ? playerSender : null;
            CommandBoundaries.WhitelistDecision decision = CommandBoundaries.whitelistDecision(
                    player != null,
                    player != null && player.hasPermission("baymcwhitelist.use"),
                    player != null && player.hasPermission("baymcwhitelist.status.self"),
                    runtime.config().server().mode(),
                    args.length
            );
            sendBoundaryFeedback(runtime, sender, decision);
            if (decision != CommandBoundaries.WhitelistDecision.STATUS
                    && decision != CommandBoundaries.WhitelistDecision.VERIFY) {
                return true;
            }
            if (player == null) {
                return true;
            }

            /*
             * 玩家侧命令只信任服务端当前玩家实体提供的名称和 UUID;
             * 具体使用正版 UUID, 离线名 UUID 还是服务端 UUID 由配置快照决定
             */
            PlayerIdentity identity = PlayerIdentityResolver.fromPlayer(
                    player,
                    runtime.config().player().uuidSource()
            );
            if (!runtime.databaseReady()) {
                runtime.lang().send(player, "database.not-ready");
                return true;
            }

            CommandContext context = new CommandContext(plugin, runtime, player, messages);
            if (decision == CommandBoundaries.WhitelistDecision.STATUS) {
                execution = statusAction.execute(context, player, identity);
                return true;
            }

            execution = verifyAction.execute(context, player, identity, args[0], addressOf(player));
            return true;
        } finally {
            if (execution != CommandExecution.ASYNC_RUNNING) {
                runtime.close();
            }
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        return List.of();
    }

    /**
     * 根据命令边界判断结果发送对应的语言文件反馈
     */
    private void sendBoundaryFeedback(
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            CommandBoundaries.WhitelistDecision decision
    ) {
        switch (decision) {
            case ONLY_PLAYER -> runtime.lang().send(sender, "common.only-player");
            case NO_PERMISSION -> runtime.lang().send(sender, "common.no-permission");
            case LOGIN_SERVER_ONLY -> runtime.lang().send(sender, "code.login-server-only");
            case USAGE -> runtime.lang().send(sender, "usage.whitelist");
            case STATUS, VERIFY -> {
            }
        }
    }

    private static String addressOf(Player player) {
        InetSocketAddress address = player.getAddress();
        return address == null || address.getAddress() == null ? null : address.getAddress().getHostAddress();
    }
}
