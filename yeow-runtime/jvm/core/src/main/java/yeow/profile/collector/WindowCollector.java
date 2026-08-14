package yeow.profile.collector;

import yeow.profile.ProfileConfig;
import yeow.profile.instrumentation.CommandMetric;
import yeow.profile.instrumentation.EventMetric;
import yeow.profile.instrumentation.JsPingMetric;
import yeow.profile.instrumentation.ProfileSink;
import yeow.profile.instrumentation.TaskMetric;
import yeow.profile.instrumentation.TaskPriority;
import yeow.profile.instrumentation.TickMetric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 窗口收集器：以固定 1s 窗口（20 tick）聚合 {@link ProfileSink} 输入，
 * 窗口结束时生成不可变的 {@link WindowMetrics} 交给监听器。
 *
 * 线程模型：onTick / onTask / onEvent / onCommand 在主线程；onJsPing 在 JS 线程。
 * 整个窗口累加器以 synchronized 保护（低频操作，开销可忽略）。
 */
public final class WindowCollector implements ProfileSink {

    public interface Listener {
        void onWindow(WindowMetrics window);
    }

    public static final int TICKS_PER_WINDOW = 20;

    private final ProfileConfig cfg;
    private final Listener listener;

    private final Set<String> pingedThisWindow = new LinkedHashSet<>();
    /** pingedThisWindow 中属于虚拟插件（Worker）的名字——心跳超时检测默认跳过。 */
    private final Set<String> virtualPinged = new LinkedHashSet<>();

    // 窗口累加器（synchronized 保护）
    private long windowStartMs;
    private int tickCount;
    private long tickDurSumNs;
    private long tickDurMaxNs;
    private int hnBacklogTicks;
    private int lowBacklogTicks;
    private final LogHistogram highHist = new LogHistogram();
    private final LogHistogram normalHist = new LogHistogram();
    private final LogHistogram lowHist = new LogHistogram();
    private final Map<String, long[]> plugins = new HashMap<>();
    private final Map<String, long[]> tasks = new HashMap<>();
    private final Map<String, Long> jsPings = new HashMap<>();
    private final Map<String, WindowMetrics.EventAgg> events = new HashMap<>();
    private final Map<String, WindowMetrics.CommandAgg> commands = new HashMap<>();

    public WindowCollector(ProfileConfig cfg, Listener listener) {
        this.cfg = cfg;
        this.listener = listener;
        this.windowStartMs = System.currentTimeMillis();
    }

    @Override
    public boolean taskSampled() {
        return cfg.fullEnabled();
    }

    @Override
    public synchronized void onTick(TickMetric m) {
        tickCount++;
        tickDurSumNs += m.tickDurationNs();
        if (m.tickDurationNs() > tickDurMaxNs) tickDurMaxNs = m.tickDurationNs();
        if (m.highDepth() > 0 || m.normalDepth() > 0) hnBacklogTicks++;
        if (m.lowDepth() > 0) lowBacklogTicks++;
        if (tickCount >= TICKS_PER_WINDOW) flushWindow();
    }

    @Override
    public synchronized void onTask(TaskMetric m) {
        LogHistogram h = switch (m.priority()) {
            case HIGH -> highHist;
            case NORMAL -> normalHist;
            case LOW -> lowHist;
        };
        h.record(m.durationNs());
        acc(plugins, m.plugin(), m.durationNs());
        acc(tasks, m.plugin() + ":" + m.taskType(), m.durationNs());
    }

    @Override
    public synchronized void onEvent(EventMetric m) {
        String key = m.plugin() + ":" + m.eventType();
        var a = events.computeIfAbsent(key, k -> new WindowMetrics.EventAgg(m.plugin(), m.eventType(), 0, 0, 0, 0, 0));
        events.put(key, new WindowMetrics.EventAgg(
            a.plugin(), a.eventType(),
            a.totalNs() + m.durationNs(), a.count() + 1,
            Math.max(a.maxNs(), m.durationNs()),
            a.slowCount() + (m.durationNs() > cfg.eventSlowMs() * 1_000_000L ? 1 : 0),
            a.timeouts() + (m.timedOut() ? 1 : 0)));
    }

    @Override
    public synchronized void onCommand(CommandMetric m) {
        String key = m.plugin() + ":" + m.command();
        var a = commands.computeIfAbsent(key, k -> new WindowMetrics.CommandAgg(m.plugin(), m.command(), 0, 0, 0, 0, 0));
        commands.put(key, new WindowMetrics.CommandAgg(
            a.plugin(), a.command(),
            a.totalNs() + m.durationNs(), a.count() + 1,
            Math.max(a.maxNs(), m.durationNs()),
            a.slowCount() + (m.durationNs() > cfg.tabSlowMs() * 1_000_000L ? 1 : 0),
            a.timeouts() + (m.timedOut() ? 1 : 0)));
    }

    @Override
    public synchronized void onJsPing(JsPingMetric m) {
        jsPings.put(m.plugin(), m.roundTripNs());
    }

    /** 记录一次 ping 发起（由 Profiler 在窗口回调中调用）；in-flight 管理在插件实体（PluginEntity.ping）。 */
    public synchronized void notePingSent(String plugin, boolean virtual) {
        pingedThisWindow.add(plugin);
        if (virtual) virtualPinged.add(plugin);
    }

    /** 标记期望响应（已有 in-flight ping、未发起新 ping 时使用），维持挂起检测的期望集合。 */
    public synchronized void noteExpected(String plugin, boolean virtual) {
        pingedThisWindow.add(plugin);
        if (virtual) virtualPinged.add(plugin);
    }

    /** 清除某插件的全部跟踪状态（插件卸载时）。 */
    public synchronized void removePlugin(String plugin) {
        jsPings.remove(plugin);
        pingedThisWindow.remove(plugin);
        virtualPinged.remove(plugin);
        plugins.remove(plugin);
        tasks.entrySet().removeIf(e -> e.getKey().startsWith(plugin + ":"));
        events.entrySet().removeIf(e -> e.getValue().plugin().equals(plugin));
        commands.entrySet().removeIf(e -> e.getValue().plugin().equals(plugin));
    }

    private void flushWindow() {
        var w = new WindowMetrics(
            windowStartMs,
            tickCount,
            tickDurSumNs,
            tickDurMaxNs,
            hnBacklogTicks,
            lowBacklogTicks,
            List.copyOf(pingedThisWindow),
            Set.copyOf(virtualPinged),
            tier(highHist), tier(normalHist), tier(lowHist),
            snapshotPlugins(plugins), snapshotTasks(tasks),
            new LinkedHashMap<>(jsPings),
            new ArrayList<>(events.values()),
            new ArrayList<>(commands.values()));
        reset();
        listener.onWindow(w);
    }

    private static WindowMetrics.TierMetrics tier(LogHistogram h) {
        return new WindowMetrics.TierMetrics(
            h.count(), h.sumNs(), h.maxNs(), h.buckets(), h.percentile(0.50), h.percentile(0.95));
    }

    private static Map<String, WindowMetrics.PluginAgg> snapshotPlugins(Map<String, long[]> m) {
        var out = new LinkedHashMap<String, WindowMetrics.PluginAgg>();
        m.forEach((k, v) -> out.put(k, new WindowMetrics.PluginAgg(v[0], (int) v[1])));
        return out;
    }

    private static Map<String, WindowMetrics.TaskAgg> snapshotTasks(Map<String, long[]> m) {
        var out = new LinkedHashMap<String, WindowMetrics.TaskAgg>();
        m.forEach((k, v) -> out.put(k, new WindowMetrics.TaskAgg(v[0], (int) v[1])));
        return out;
    }

    private void reset() {
        windowStartMs = System.currentTimeMillis();
        tickCount = 0;
        tickDurSumNs = 0;
        tickDurMaxNs = 0;
        hnBacklogTicks = 0;
        lowBacklogTicks = 0;
        highHist.reset();
        normalHist.reset();
        lowHist.reset();
        plugins.clear();
        tasks.clear();
        jsPings.clear();
        events.clear();
        commands.clear();
        pingedThisWindow.clear();
        virtualPinged.clear();
    }

    private static void acc(Map<String, long[]> map, String key, long ns) {
        map.compute(key, (k, v) -> {
            if (v == null) return new long[]{ns, 1};
            v[0] += ns;
            v[1] += 1;
            return v;
        });
    }
}
