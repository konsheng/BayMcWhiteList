package com.baymc.whitelist.command;

/**
 * 描述子命令执行结束后运行期快照的归属状态
 *
 * <p>同步命令返回 FINISHED, 由主命令入口关闭 RuntimeState;
 * 异步命令返回 ASYNC_RUNNING, 由异步任务在 finally 中关闭 RuntimeState
 */
public enum CommandExecution {
    /**
     * 子命令已经同步完成, 调用方可以立即释放运行期快照
     */
    FINISHED,
    /**
     * 子命令已经把运行期快照交给异步任务, 调用方不能提前释放
     */
    ASYNC_RUNNING
}
