package yeow.profile.report;

import yeow.profile.collector.LogHistogram;
import yeow.profile.collector.WindowMetrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全量分析快照：从最近 N 个窗口构建的不可变报告数据。
 *
 * 窗口对齐：所有跨窗口聚合按窗口序列索引对齐，缺失窗口补 0--与旧实现不同，
 * 不存在"只收集出现过的窗口导致索引错位"的问题。
 */
public final class ProfileSnapshot {

    public int windowCount;
    public long windowMs;
    public double tps1m, tps5m;
    public double workingMsPerWindow;

    public TierStats hn;          // HIGH+NORMAL（实时调度）
    public TierStats low;         // LOW（批量，允许积压）
    public List<PluginStats> plugins;
    public List<JsThreadStats> jsThreads;
    public List<LatencyStats> events;
    public List<LatencyStats> commands;
    public HealthScore health;

    public record TierStats(long calls, double avgMs, double p50Ms, double p95Ms, double maxMs) {}

    public record PluginStats(
        String name,
        double avgMsPerWindow,
        double p50MsPerWindow,
        double p95MsPerWindow,
        double maxMsPerWindow,
        double avgCallsPerWindow,
        double pctOfScheduler,
        List<TaskBreakdown> topTasks
    ) {
        public record TaskBreakdown(
            String name,
            double avgMsPerWindow,
            double p95Ms,
            double maxMs,
            double avgCallsPerWindow,
            double perCallMs,
            double pctOfPlugin
        ) {}
    }

    public record JsThreadStats(
        String plugin,
        long baselineUs,
        double avgMs, double p50Ms, double p95Ms, double maxMs,
        int samples, boolean slow, boolean hung,
        boolean virtual, String createdBy
    ) {}

    public record LatencyStats(
        String key,
        double avgMs, double maxMs,
        int calls, int slow, int timeouts
    ) {}

    public static final class HealthScore {
        public int score = 100;
        public final List<String> reasons = new ArrayList<>();
        public String level() { return score >= 80 ? "ok" : score >= 50 ? "warn" : "bad"; }
    }

    public static ProfileSnapshot build(List<WindowMetrics> windows, int lowMsThreshold, int cmdMsThreshold,
                                        Map<String, yeow.PluginEntity> entities) {
        var s = new ProfileSnapshot();
        if (windows.isEmpty()) return s;
        s.windowCount = windows.size();
        s.windowMs = 1000;
        var last = windows.get(windows.size() - 1);
        s.tps1m = 0; s.tps5m = 0; // TPS 由运行时提供，窗口数据不携带（避免瞬时值冒充窗口值）

        long tickSum = 0;
        for (var w : windows) tickSum += w.tickDurationSumNs();
        s.workingMsPerWindow = tickSum / (double) windows.size() / 1_000_000.0;

        // 合并直方图（跨窗口）--sum/max 直接从 TierMetrics 累加，桶分布用 mergeBuckets
        var hnHist = new LogHistogram();
        var lowHist = new LogHistogram();
        long hnSum = 0, hnMax = 0, lowSum = 0, lowMax = 0;
        for (var w : windows) {
            mergeTier(hnHist, w.high());
            mergeTier(hnHist, w.normal());
            mergeTier(lowHist, w.low());
            hnSum += w.high().sumNs() + w.normal().sumNs();
            hnMax = Math.max(hnMax, Math.max(w.high().maxNs(), w.normal().maxNs()));
            lowSum += w.low().sumNs();
            lowMax = Math.max(lowMax, w.low().maxNs());
        }
        s.hn = tier(hnHist, hnSum, hnMax);
        s.low = tier(lowHist, lowSum, lowMax);
        s.plugins = buildPlugins(windows, s.hn);
        s.jsThreads = buildJsThreads(windows, entities);
        s.events = buildLatencies(windows, true, lowMsThreshold);
        s.commands = buildLatencies(windows, false, cmdMsThreshold);
        s.health = computeHealth(s);
        return s;
    }

    private static void mergeTier(LogHistogram target, WindowMetrics.TierMetrics t) {
        if (t == null) return;
        target.mergeBuckets(t.buckets());
    }

    private static TierStats tier(LogHistogram h, long sumNs, long maxNs) {
        return new TierStats(h.count(),
            sumNs / (double) (h.count() > 0 ? h.count() : 1) / 1_000_000.0,
            h.percentile(0.50) / 1_000_000.0, h.percentile(0.95) / 1_000_000.0,
            maxNs / 1_000_000.0);
    }

    private static List<PluginStats> buildPlugins(List<WindowMetrics> windows, TierStats hn) {
        int n = windows.size();
        // 插件集合：跨窗口并集
        Set<String> names = new LinkedHashSet<>();
        for (var w : windows) names.addAll(w.plugins().keySet());
        if (names.isEmpty()) return List.of();

        var out = new ArrayList<PluginStats>();
        double hnTotalNs = hn.avgMs() * n * 1_000_000.0;
        for (var name : names) {
            long totalNs = 0, totalCalls = 0, maxNs = 0;
            long[] perWindow = new long[n];
            long[] perWindowCalls = new long[n];
            for (int i = 0; i < n; i++) {
                var p = windows.get(i).plugins().get(name);
                long ns = p != null ? p.ns() : 0;
                int c = p != null ? p.count() : 0;
                perWindow[i] = ns;
                perWindowCalls[i] = c;
                totalNs += ns;
                totalCalls += c;
                if (ns > maxNs) maxNs = ns;
            }
            long[] sorted = perWindow.clone();
            java.util.Arrays.sort(sorted);
            long[] sortedCalls = perWindowCalls.clone();
            java.util.Arrays.sort(sortedCalls);

            // 任务分解（同样按窗口对齐）
            var taskOut = new ArrayList<PluginStats.TaskBreakdown>();
            Map<String, long[]> taskAgg = new LinkedHashMap<>();
            for (var w : windows) {
                for (var e : w.tasks().entrySet()) {
                    if (!e.getKey().startsWith(name + ":")) continue;
                    var a = taskAgg.computeIfAbsent(e.getKey(), k -> new long[2]);
                    a[0] += e.getValue().ns();
                    a[1] += e.getValue().count();
                }
            }
            for (var e : taskAgg.entrySet()) {
                String taskName = e.getKey().substring(name.length() + 1);
                long ns = e.getValue()[0], calls = e.getValue()[1];
                taskOut.add(new PluginStats.TaskBreakdown(
                    taskName,
                    (double) ns / n / 1_000_000.0,
                    0, // p95 需要直方图，粗化为 0（任务级只输出平均/峰值占比）
                    (double) ns / calls / 1_000_000.0,
                    (double) calls / n,
                    (double) ns / calls / 1_000_000.0,
                    totalNs > 0 ? ns * 100.0 / totalNs : 0));
            }
            taskOut.sort((a, b) -> Double.compare(b.avgMsPerWindow(), a.avgMsPerWindow()));
            out.add(new PluginStats(
                name,
                (double) totalNs / n / 1_000_000.0,
                sorted[n / 2] / 1_000_000.0,
                sorted[(int) (n * 0.95)] / 1_000_000.0,
                maxNs / 1_000_000.0,
                (double) totalCalls / n,
                hnTotalNs > 0 ? totalNs * 100.0 / hnTotalNs : 0,
                taskOut.subList(0, Math.min(8, taskOut.size()))));
        }
        out.sort((a, b) -> Double.compare(b.avgMsPerWindow(), a.avgMsPerWindow()));
        return out.subList(0, Math.min(10, out.size()));
    }

    private static List<JsThreadStats> buildJsThreads(List<WindowMetrics> windows, Map<String, yeow.PluginEntity> entities) {
        // plugin → 跨窗口 ping 值列表（按窗口对齐，缺失窗口视为无响应）
        Map<String, List<Long>> series = new LinkedHashMap<>();
        for (var w : windows) {
            for (var e : w.jsPings().entrySet()) {
                series.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
            }
        }
        if (series.isEmpty()) return List.of();
        var out = new ArrayList<JsThreadStats>();
        for (var e : series.entrySet()) {
            var vals = e.getValue();
            long[] sorted = vals.stream().mapToLong(Long::longValue).sorted().toArray();
            long sum = 0;
            for (long v : sorted) sum += v;
            double avg = (double) sum / sorted.length / 1_000_000.0;
            double p50 = sorted[sorted.length / 2] / 1_000_000.0;
            double p95 = sorted[(int) (sorted.length * 0.95)] / 1_000_000.0;
            double max = sorted[sorted.length - 1] / 1_000_000.0;
            boolean slow = max > 200;
            boolean hung = windows.size() >= 30 && vals.size() < windows.size() / 2;
            var entity = entities != null ? entities.get(e.getKey()) : null;
            boolean virtual = entity != null && entity.isVirtual();
            String createdBy = virtual && entity != null ? entity.source() : null;
            out.add(new JsThreadStats(e.getKey(), -1, avg, p50, p95, max, sorted.length, slow, hung, virtual, createdBy));
        }
        out.sort((a, b) -> Double.compare(b.avgMs, a.avgMs));
        return out;
    }

    private static List<LatencyStats> buildLatencies(List<WindowMetrics> windows, boolean isEvent, int slowMs) {
        // key → [totalNs, count, maxNs, slow, timeouts]
        Map<String, long[]> agg = new LinkedHashMap<>();
        for (var w : windows) {
            if (isEvent) {
                for (var ev : w.events()) {
                    var a = agg.computeIfAbsent(ev.plugin() + ":" + ev.eventType(), k -> new long[5]);
                    a[0] += ev.totalNs(); a[1] += ev.count();
                    if (ev.maxNs() > a[2]) a[2] = ev.maxNs();
                    a[3] += ev.slowCount(); a[4] += ev.timeouts();
                }
            } else {
                for (var c : w.commands()) {
                    var a = agg.computeIfAbsent(c.plugin() + ":" + c.command(), k -> new long[5]);
                    a[0] += c.totalNs(); a[1] += c.count();
                    if (c.maxNs() > a[2]) a[2] = c.maxNs();
                    a[3] += c.slowCount(); a[4] += c.timeouts();
                }
            }
        }
        var out = new ArrayList<LatencyStats>();
        for (var e : agg.entrySet()) {
            var v = e.getValue();
            out.add(new LatencyStats(e.getKey(),
                v[1] > 0 ? (double) v[0] / v[1] / 1_000_000.0 : 0,
                (double) v[2] / 1_000_000.0,
                (int) v[1], (int) v[3], (int) v[4]));
        }
        out.sort((a, b) -> Long.compare(b.calls, a.calls));
        return out;
    }

    private static HealthScore computeHealth(ProfileSnapshot s) {
        var h = new HealthScore();
        if (s.hn != null && s.hn.calls() > 0 && s.workingMsPerWindow > 0) {
            double pct = s.hn.avgMs() / s.workingMsPerWindow * 100;
            if (pct > 80) { h.score -= 30; h.reasons.add("实时调度占用 " + String.format("%.0f%%", pct) + " working（>80%）"); }
            else if (pct > 50) { h.score -= 15; h.reasons.add("实时调度占用 " + String.format("%.0f%%", pct) + " working（>50%）"); }
        }
        for (var js : s.jsThreads) {
            if (js.hung()) { h.score -= 40; h.reasons.add("JS 线程挂起（" + js.plugin() + "）"); }
            else if (js.slow()) { h.score -= 20; h.reasons.add("JS 线程响应慢（" + js.plugin() + " >200ms）"); }
        }
        for (var ev : s.events) {
            if (ev.timeouts() > 0) { h.score -= 20; h.reasons.add("事件超时（" + ev.key() + " ×" + ev.timeouts() + "）"); }
            else if (ev.slow() > 0) { h.score -= 10; h.reasons.add("事件响应慢（" + ev.key() + "）"); }
        }
        for (var c : s.commands) {
            if (c.timeouts() > 0) { h.score -= 15; h.reasons.add("补全超时（" + c.key() + " ×" + c.timeouts() + "）"); }
            else if (c.slow() > 0) { h.score -= 10; h.reasons.add("补全响应慢（" + c.key() + "）"); }
        }
        h.score = Math.max(0, Math.min(100, h.score));
        return h;
    }
}
