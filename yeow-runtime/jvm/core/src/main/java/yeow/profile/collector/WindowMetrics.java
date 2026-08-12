package yeow.profile.collector;

import java.util.List;
import java.util.Map;

/**
 * 一个固定 1s 窗口（20 tick）的聚合输出，不可变。
 *
 * 语义约定（重要）：
 * - HIGH / NORMAL 队列承载实时性与交互响应，**不应存在积压**--分析指标与告警只看这两级；
 * - LOW 队列承载大批量重复任务，**允许积压与延迟完成**--不计入健康评分、不告警。
 */
public record WindowMetrics(
    long startMs,
    int tickCount,
    long tickDurationSumNs,
    long tickDurationMaxNs,
    /** 窗口内 HIGH/NORMAL 出现积压（深度>0）的 tick 数。 */
    int hnBacklogTicks,
    /** 窗口内 LOW 出现积压的 tick 数（仅展示，不告警）。 */
    int lowBacklogTicks,
    /** 本窗口发送过心跳 ping 的插件（期望响应集合）。 */
    List<String> pingedPlugins,
    TierMetrics high,
    TierMetrics normal,
    TierMetrics low,
    /** plugin → {ns, count}（仅全量分析时采集）。 */
    Map<String, PluginAgg> plugins,
    /** plugin:taskType → {ns, count}（仅全量分析时采集）。 */
    Map<String, TaskAgg> tasks,
    /** plugin → 本窗口最近一次心跳往返（ns），缺失表示窗口内无响应。 */
    Map<String, Long> jsPings,
    List<EventAgg> events,
    List<CommandAgg> commands
) {

    public record TierMetrics(long count, long sumNs, long maxNs, long[] buckets, double p50Ns, double p95Ns) {}

    public record PluginAgg(long ns, int count) {}

    public record TaskAgg(long ns, int count) {}

    public record EventAgg(String plugin, String eventType, long totalNs, int count, long maxNs, int slowCount, int timeouts) {
        public double avgMs() { return count > 0 ? (double) totalNs / count / 1_000_000.0 : 0; }
        public double maxMs() { return (double) maxNs / 1_000_000.0; }
    }

    public record CommandAgg(String plugin, String command, long totalNs, int count, long maxNs, int slowCount, int timeouts) {
        public double avgMs() { return count > 0 ? (double) totalNs / count / 1_000_000.0 : 0; }
        public double maxMs() { return (double) maxNs / 1_000_000.0; }
    }

    /** HIGH+NORMAL 的实时调度占用（ns）。 */
    public long hnExecSumNs() {
        return high.sumNs() + normal.sumNs();
    }
}
