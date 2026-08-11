package yeow.profile.report;

import yeow.profile.collector.WindowMetrics;
import yeow.profile.instrumentation.CommandMetric;
import yeow.profile.instrumentation.EventMetric;
import yeow.profile.instrumentation.JsPingMetric;
import yeow.profile.instrumentation.TaskMetric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单插件深度追踪：订阅明细数据流，持续 N 秒后生成报告。
 * 只记录指定插件的任务/事件/补全/心跳明细。
 */
public final class TrackSession {

    private static final class Series {
        final List<Long> ns = new ArrayList<>();
    }

    private final String pluginName;
    private final long startMs;
    private final int durationSec;
    private final Map<String, Series> tasks = new LinkedHashMap<>();
    private final Map<String, Series> events = new LinkedHashMap<>();
    private final Map<String, Series> commands = new LinkedHashMap<>();
    private final List<Long> jsPings = new ArrayList<>();
    private int taskCalls;

    public TrackSession(String pluginName, int durationSec) {
        this.pluginName = pluginName;
        this.startMs = System.currentTimeMillis();
        this.durationSec = durationSec;
    }

    public boolean expired(long nowMs) {
        return nowMs - startMs > durationSec * 1000L;
    }

    public String pluginName() {
        return pluginName;
    }

    public void recordTask(TaskMetric m) {
        if (!pluginName.equals(m.plugin())) return;
        taskCalls++;
        tasks.computeIfAbsent(m.taskType(), k -> new Series()).ns.add(m.durationNs());
    }

    public void recordEvent(EventMetric m) {
        if (!pluginName.equals(m.plugin())) return;
        events.computeIfAbsent(m.eventType(), k -> new Series()).ns.add(m.durationNs());
    }

    public void recordCommand(CommandMetric m) {
        if (!pluginName.equals(m.plugin())) return;
        commands.computeIfAbsent(m.command(), k -> new Series()).ns.add(m.durationNs());
    }

    public void recordJsPing(long ns) {
        jsPings.add(ns);
    }

    public String generateReport() {
        var sb = new StringBuilder();
        sb.append("\n  -- Track Report: ").append(pluginName)
            .append(" (").append(durationSec).append("s, ").append(taskCalls).append(" task executions) --\n\n");

        if (!jsPings.isEmpty()) {
            var vals = jsPings.stream().mapToLong(Long::longValue).sorted().toArray();
            sb.append("  JS Thread latency\n");
            sb.append(String.format("    avg %s  p50 %s  p95 %s  max %s  (%d samples)\n\n",
                fmt(vals[0] == 0 ? 0 : avg(vals)), fmt(pct(vals, 0.50)), fmt(pct(vals, 0.95)),
                fmt(vals[vals.length - 1]), vals.length));
        }

        section(sb, "Per-Task", tasks);
        section(sb, "Events", events);
        section(sb, "Tab-complete", commands);
        return sb.toString();
    }

    private static void section(StringBuilder sb, String label, Map<String, Series> map) {
        if (map.isEmpty()) return;
        sb.append("  " + label + "\n");
        sb.append(String.format("    %-30s %8s %8s %8s %8s\n", "key", "avg", "p95", "max", "calls"));
        map.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().ns.size(), a.getValue().ns.size()))
            .limit(10)
            .forEach(e -> {
                var vals = e.getValue().ns.stream().mapToLong(Long::longValue).sorted().toArray();
                sb.append(String.format("    %-30s %8s %8s %8s %8d\n",
                    e.getKey(), fmt(avg(vals)), fmt(pct(vals, 0.95)), fmt(vals[vals.length - 1]), vals.length));
            });
        sb.append("\n");
    }

    private static double avg(long[] v) {
        long s = 0;
        for (long x : v) s += x;
        return (double) s / v.length;
    }

    private static double pct(long[] v, double p) {
        int i = (int) (p * (v.length - 1));
        return v[Math.max(0, Math.min(i, v.length - 1))];
    }

    private static String fmt(double ns) {
        if (ns < 1_000_000) return String.format("%.0fµs", ns / 1000);
        return String.format("%.2fms", ns / 1_000_000);
    }
}
