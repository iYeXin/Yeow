package yeow;

import java.util.concurrent.CompletableFuture;

/**
 * 插件实体——运行时视角的"插件"抽象。
 *
 * 在调度器看来，插件是**可提交任务并收到回复**的实体；在运行时看来，插件是
 * 由独立执行单元负责、存在行为与指标（如响应延迟）的实体。JS 的特殊性
 * （QuickJS 上下文、消息协议）只存在于 JS 适配器（{@link PluginThread}）内。
 *
 * <p><b>适配器（社区 / 多语言）</b>：Yeow 的标准开发语言是 JavaScript；其他语言
 * 通过社区适配器接入——适配器是平台相关的（如为 NeoForge 服务端提供适配器时，
 * 需自行适应其模组结构），但只需实现本接口并调用
 * {@link yeow.YeowRuntime#registerPluginEntity(PluginEntity)} 注册即可接入
 * 调度器 / 事件桥 / 命令桥 / Service / Profile 全链路。适配器的逻辑卸载
 * （unload / 重载）由适配器自身实现（本接口的 {@link #stopAndWait()} /
 * {@link #reload(String)}），/yeow 管理命令暂不感知适配器插件的存在。
 *
 * <p><b>消息契约</b>：{@link #postMessage(String)} 投递 JSON 字符串：
 * <ul>
 *   <li>生命周期：`{"t":"INIT"|"LOAD"|"DISABLE"|"RELOAD"}`（注册后投递 LOAD）</li>
 *   <li>回调：`{"t":"cb","p":"<cbId>","r":<data>}`（事件 / 命令 / 异步结果）</li>
 *   <li>心跳：`{"t":"DEBUG","p":"ping"}` → 实现应回 `{"t":"pong"}`（经各自通道）</li>
 * </ul>
 * 完成回报（如事件的 `event.complete`）由适配器经 task 通道回传
 * （SyncCallbackHelper 契约）。适配器可定义自己的消息格式，只要满足本接口语义。
 */
public interface PluginEntity {

    /** 插件名（全局唯一，加载时固定）。 */
    String name();

    /** 插件包来源（JAR/zip 路径）；虚拟插件为虚拟标识。 */
    String source();

    /**
     * 插件类型标记（适配器标识），如 `"js"`（官方）、`"python"`、`"tcp"` 等。
     * 用于注册日志与统计区分。
     */
    String type();

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
     * 插件声明的权限节点（如 `fs:server.*`）。运行时经
     * {@link yeow.YeowRuntime#submitMessage} 提交 service 等敏感通道时做节点匹配；
     * 适配器实体默认无权限约束（权限由适配器自行管理，可覆写以接入统一权限模型）。
     */
    default java.util.Set<String> declaredPermissions() {
        return java.util.Set.of();
    }

    /**
     * 发起一次心跳探测，返回往返耗时（纳秒）的 future。
     * 若已有未返回的 ping（in-flight）则返回 {@code null}，调用方不应重复发起。
     * future 在 pong 到达时完成；插件无响应时保持 pending。
     */
    CompletableFuture<Long> ping();

    /** 启动执行单元。 */
    void start();

    /** 请求停止并等待退出（超时后由实现强制终止）；适配器负责逻辑卸载。 */
    void stopAndWait();

    /** 重载（替换代码后重启执行单元）；不适用的实现可忽略。 */
    void reload(String code);
}
