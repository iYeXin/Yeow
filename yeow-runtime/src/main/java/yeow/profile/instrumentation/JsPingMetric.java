package yeow.profile.instrumentation;

/** 单次 JS 心跳 pong 往返样本。 */
public record JsPingMetric(
    String plugin,
    long roundTripNs
) {}
