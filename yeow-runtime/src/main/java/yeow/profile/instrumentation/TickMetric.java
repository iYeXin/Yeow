package yeow.profile.instrumentation;

/** 每 tick 结束的调度器状态。 */
public record TickMetric(
    long timestampMs,
    long tickDurationNs,
    int highDepth,
    int normalDepth,
    int lowDepth
) {}
