package com.baymc.whitelist.command.whitelist;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.command.CommandContext;
import com.baymc.whitelist.command.CommandExecution;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.storage.WhitelistRecord;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Optional;
import java.util.logging.Level;

/**
 * 处理 /whitelist 无参数时的玩家自助状态查询
 *
 * <p>该动作只按当前玩家 UUID 查询数据库, 不修改白名单状态,
 * 也不参与邀请码限流统计
 */
public final class WhitelistStatusAction {
    private final WhitelistPlayerView view;

    public WhitelistStatusAction(WhitelistPlayerView view) {
        this.view = view;
    }

    /**
     * 异步查询当前玩家白名单记录, 并在玩家调度器上发送结果
     */
    public CommandExecution execute(
            CommandContext context,
            Player player,
            PlayerIdentity identity
    ) {
        return context.runAsyncClosing(() -> sendSelfStatus(context, player, identity));
    }

    private void sendSelfStatus(
            CommandContext context,
            Player player,
            PlayerIdentity identity
    ) {
        BayMcWhiteListPlugin.RuntimeState runtime = context.runtime();
        try {
            Optional<WhitelistRecord> record = runtime.repository().findByUuid(identity.uuidText());
            runtime.scheduler().runForPlayer(player, () -> {
                if (record.isPresent()) {
                    runtime.lang().send(
                            player,
                            "player.status-whitelisted",
                            view.selfStatusPlaceholders(runtime, identity, record.orElseThrow())
                    );
                    return;
                }
                runtime.lang().send(
                        player,
                        "player.status-not-whitelisted",
                        view.selfStatusPlaceholders(runtime, identity, null)
                );
            });
        } catch (SQLException exception) {
            context.logger().log(Level.SEVERE, "Failed to query whitelist self status for " + identity.name() + ".", exception);
            runtime.scheduler().runForPlayer(player, () -> runtime.lang().send(player, "database.operation-failed"));
        }
    }
}
