package yeow;

import java.util.concurrent.CompletableFuture;

/**
 * 插件实体——运行时视角的"插件"抽象。
 *
 * 在调度器看来，插件是**可提交任务并收到回复**的实体；在运行时看来，插件是
 * 由独立执行单元负责、存在行为与指标（如响应延迟）的实体。JS 的特殊性
 * （QuickJS 上下文、消息协议）只存在于 JS 适配器（{@link PluginThread}）内；
 * 未来的虚拟插件（Worker API）、多语言适配器实现本接口即可接入全链路
 * （调度器 / 事件桥 / 命令桥 / Service / Profile）。
 *
 * 消息契约：{@link #postMessage(String)} 投递 JSON 字符串，格式为
 * `{"t":"cb","p":"<cbId>","r":<data>}` 回调与生命周期消息（INIT/LOAD/DISABLE/RELOAD），
 * 由适配器自行消化；`event.complete` / `command.tabComplete` 等完成回报经 task
 * 通道回传（SyncCallbackHelper 契约）。
 */
public interface PluginEntity {

    /** 插件名（全局唯一，加载时固定）。 */
    String name();

    /** 插件包来源（JAR/zip 路径）；虚拟插件为虚拟标识。 */
    String source();

    /** 是否运行中。 */
    boolean isRunning();

    /**
     * 是否为虚拟插件（Worker API 等非包实体）。
     * 性能统计（Profile 报告 / 告警）按此区分与标记；普通插件为 false。
     */
    boolean isVirtual();

    /** 向插件投递消息（回调 / 生命周期）。线程安全，不阻塞。 */
    void postMessage(String json);

    /**
     * 发起一次心跳探测，返回往返耗时（纳秒）的 future。
     * 若已有未返回的 ping（in-flight）则返回 {@code null}，调用方不应重复发起。
     * future 在 pong 到达时完成；插件无响应时保持 pending。
     */
    CompletableFuture<Long> ping();

    /** 启动执行单元。 */
    void start();

    /** 请求停止并等待退出（超时后由实现强制终止）。 */
    void stopAndWait();

    /** 重载（替换代码后重启执行单元）；不适用的实现可忽略。 */
    void reload(String code);
}
