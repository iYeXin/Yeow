package yeow.profile.instrumentation;

/** 单个插件的事件回调结束样本。 */
public record EventMetric(
    String plugin,
    String eventType,
    long durationNs,
    boolean timedOut
) {}
