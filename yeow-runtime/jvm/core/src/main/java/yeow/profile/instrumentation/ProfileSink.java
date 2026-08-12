package yeow.profile.instrumentation;

/**
 * 插桩接口（Profile 的唯一耦合面）。
 *
 * 运行时组件（Scheduler / EventBridge / CommandTasks / PluginThread）只依赖本接口，
 * 不依赖任何 profile 实现。插桩点共 5 个，每个均为薄调用--不做聚合、不做告警。
 *
 * 关闭相关采集时，组件持有 null 引用并在调用前判空，不构造样本对象。
 */
public interface ProfileSink {

    /**
     * 是否启用逐任务采样（仅全量分析 {@code profile.enabled} 时）。
     * 预警引擎不需要逐任务数据，关闭全量分析时返回 false，
     * 调用方据此短路，避免每任务构造 {@link TaskMetric} 的开销。
     */
    boolean taskSampled();

    /** 每 tick 结束：调度器 tick 耗时与三级队列深度。 */
    void onTick(TickMetric metric);

    /** 每个游戏任务执行完成（仅 {@link #taskSampled()} 为 true 时调用）。 */
    void onTask(TaskMetric metric);

    /** 每个插件的事件回调结束（含超时标记）。 */
    void onEvent(EventMetric metric);

    /** 每次 Tab 补全结束（含超时标记）。 */
    void onCommand(CommandMetric metric);

    /** 每次 JS 心跳 pong 到达（往返延迟）。 */
    void onJsPing(JsPingMetric metric);
}
