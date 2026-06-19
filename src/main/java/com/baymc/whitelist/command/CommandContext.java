package com.baymc.whitelist.command;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.command.target.CommandTargetResolver;
import org.bukkit.command.CommandSender;

import java.util.Map;
import java.util.logging.Logger;

/**
 * 单次 /baymcwhitelist 子命令执行期间共享的上下文
 *
 * <p>这里集中放置命令流程都会用到的插件实例, 运行期快照, 发送者,
 * 消息格式化器和目标解析器, 避免每个子命令重复传递一长串参数
 */
public final class CommandContext {
    private final BayMcWhiteListPlugin plugin;
    private final BayMcWhiteListPlugin.RuntimeState runtime;
    private final CommandSender sender;
    private final CommandMessages messages;
    private final CommandTargetResolver targetResolver;

    /**
     * 创建一次子命令执行上下文
     *
     * @param plugin 当前插件实例
     * @param runtime 当前命令捕获到的运行期快照
     * @param sender 命令发送者
     * @param messages 命令消息和占位符格式化工具
     * @param targetResolver 命令目标解析工具
     */
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
        return targetResolver;
    }

    public Logger logger() {
        return plugin.getLogger();
    }

    /**
     * 检查命令发送者权限, 并在失败时发送统一语言文件提示
     */
    public boolean requirePermission(String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        send("common.no-permission");
        return false;
    }

    /**
     * 检查参数数量是否完全匹配, 失败时发送对应 usage 语言键
     */
    public boolean requireExactArgs(String[] args, int expected, String usageKey) {
        if (CommandBoundaries.hasExactArgumentCount(args, expected)) {
            return true;
        }
        send(usageKey);
        return false;
    }

    /**
     * 拦截需要数据库的命令, 避免仓库未就绪时继续执行写入或查询
     */
    public boolean requireDatabaseReady() {
        if (runtime.databaseReady()) {
            return true;
        }
        send("database.not-ready");
        return false;
    }

    /**
     * 使用当前运行期语言文件向命令发送者发送普通消息
     */
    public void send(String key) {
        runtime.lang().send(sender, key);
    }

    /**
     * 使用当前运行期语言文件向命令发送者发送带占位符的消息
     */
    public void send(String key, Map<String, String> placeholders) {
        runtime.lang().send(sender, key, placeholders);
    }

    /**
     * 把数据库或外部查询任务交给异步调度器, 并在任务结束后释放运行期快照
     *
     * <p>调用方收到 ASYNC_RUNNING 后不能再关闭 RuntimeState, 否则同一次命令
     * 会在异步查询尚未完成时提前释放数据库租约
     */
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
