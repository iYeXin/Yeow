package yeow;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 游戏任务调度器契约（**平台实现**）。
 *
 * 调度模型（队列结构、优先级预算、自动降级、任务在游戏线程的投递与执行方式）是
 * 平台特异的--Paper 实现为"调度线程串行派发 + 主线程 pump"；Folia 实现为
 * "调度线程串行派发 + region 线程 pump"。core 只依赖本接口的提交/清理语义。
 */
public interface TaskScheduler {

    enum Priority { HIGH, NORMAL, LOW }

    /** 启动调度（RuntimeCore.start 时调用）。 */
    void start();

    /** 停止调度（RuntimeCore.shutdown 时调用）。 */
    void shutdown();

    /** 提交同步任务：结果经 future 完成（JS 线程阻塞等待）。 */
    void submitGameSync(String taskType, JsonObject params, CompletableFuture<String> future, Priority priority, String pluginName);

    /** 提交异步任务：结果经 callback 回投。 */
    void submitGameAsync(String taskType, JsonObject params, Consumer<Object> callback, Priority priority, String pluginName);

    /** 插件卸载：移除其全部队列任务并立即释放同步调用方。 */
    void purgePluginTasks(String pluginName);
}
