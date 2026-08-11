package yeow.profile.instrumentation;

/** 单次 Tab 补全结束样本。 */
public record CommandMetric(
    String plugin,
    String command,
    long durationNs,
    boolean timedOut
) {}
