package com.baymc.whitelist.command;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.command.target.CommandTargetResolver;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.logging.Logger;

/**
 * 单次命令执行期间共享的上下文
 *
 * <p>这里集中放置命令流程都会用到的插件实例, 运行期快照, 发送者
 * 消息格式化器和目标解析器, 避免每个子命令重复传递一长串参数
 */
public final class CommandContext {
    private final BayMcWhiteListPlugin plugin;
    private final BayMcWhiteListPlugin.RuntimeState runtime;
    private final CommandSender sender;
    private final CommandMessages messages;
    @Nullable
    private final CommandTargetResolver targetResolver;

    /**
     * 创建不需要命令目标解析器的执行上下文
     *
     * <p>/whitelist 玩家命令只信任当前玩家实体, 不需要解析任意输入目标
     */
    public CommandContext(
            BayMcWhiteListPlugin plugin,
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            CommandMessages messages
    ) {
        this(plugin, runtime, sender, messages, null);
    }

    public CommandContext(
            BayMcWhiteListPlugin plugin,
            BayMcWhiteListPlugin.RuntimeState runtime,
            CommandSender sender,
            CommandMessages messages,
            CommandTargetResolver targetResolver
    ) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.sender = sender;
        this.messages = messages;
        this.targetResolver = targetResolver;
    }

    public BayMcWhiteListPlugin plugin() {
        return plugin;
    }

    public BayMcWhiteListPlugin.RuntimeState runtime() {
        return runtime;
    }

    public CommandSender sender() {
        return sender;
    }

    public CommandMessages messages() {
        return messages;
    }

    public CommandTargetResolver targetResolver() {
        if (targetResolver == null) {
            throw new IllegalStateException("This command context does not have a target resolver");
        }
        return targetResolver;
    }

    public Logger logger() {
        return plugin.getLogger();
    }

    public boolean requirePermission(String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        send("common.no-permission");
        return false;
    }

    public boolean requireExactArgs(String[] args, int expected, String usageKey) {
        if (CommandBoundaries.hasExactArgumentCount(args, expected)) {
            return true;
        }
        send(usageKey);
        return false;
    }

    public boolean requireDatabaseReady() {
        if (runtime.databaseReady()) {
            return true;
        }
        send("database.not-ready");
        return false;
    }

    public void send(String key) {
        runtime.lang().send(sender, key);
    }

    public void send(String key, Map<String, String> placeholders) {
        runtime.lang().send(sender, key, placeholders);
    }

    public CommandExecution runAsyncClosing(Runnable task) {
        runtime.scheduler().runAsync(() -> {
            try {
                task.run();
            } finally {
                runtime.close();
            }
        });
        return CommandExecution.ASYNC_RUNNING;
    }
}
