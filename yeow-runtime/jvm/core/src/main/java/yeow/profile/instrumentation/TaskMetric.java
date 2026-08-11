package yeow.profile.instrumentation;

/** 单个游戏任务执行样本（仅全量分析时采集）。 */
public record TaskMetric(
    String plugin,
    String taskType,
    TaskPriority priority,
    long durationNs
) {}
