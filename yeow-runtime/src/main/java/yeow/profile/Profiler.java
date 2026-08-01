package yeow.profile;

import org.bukkit.command.CommandSender;
import yeow.PluginThread;
import yeow.profile.collector.WindowCollector;
import yeow.profile.collector.WindowMetrics;
import yeow.profile.instrumentation.CommandMetric;
import yeow.profile.instrumentation.EventMetric;
import yeow.profile.instrumentation.JsPingMetric;
import yeow.profile.instrumentation.ProfileSink;
import yeow.profile.instrumentation.TaskMetric;
import yeow.profile.instrumentation.TickMetric;
import yeow.profile.report.ProfileFormatter;
import yeow.profile.report.ProfileSnapshot;
import yeow.profile.report.TrackSession;
import yeow.profile.warnings.WarningEngine;
import yeow.profile.warnings.detectors.EventSlowDetector;
import yeow.profile.warnings.detectors.HeartbeatTimeoutDetector;
import yeow.profile.warnings.detectors.PluginHungDetector;
import yeow.profile.warnings.detectors.SchedulerBacklogDetector;
import yeow.profile.warnings.detectors.SchedulerSaturationDetector;
import yeow.profile.warnings.detectors.TabSlowDetector;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Profiler 门面：装配 收集器 → 预警引擎 → 报告，并向运行时暴露最小 API。
 *
 * 运行时组件只依赖 {@link ProfileSink}（经 {@link #sink()} 注入）；
 * 本门面负责：窗口监听（发心跳 ping）、插件注册/注销、/yeow profile|track 命令、关闭。
 */
public final class Profiler implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("Yeow");
    private static final int RING_CAPACITY = 300; // 5 分钟

    private final ProfileConfig cfg;
    private final WarningEngine warnings;
    private final WindowCollector collector;
    private final Deque<WindowMetrics> ring = new ArrayDeque<>();
    private final CompositeSink sink = new CompositeSink();
    private final Map<String, PluginThread> plugins = new ConcurrentHashMap<>();
    private volatile TrackSession track;

    private Profiler(ProfileConfig cfg) {
        this.cfg = cfg;
        this.warnings = new WarningEngine(cfg);
        this.collector = new WindowCollector(cfg, this::onWindow);
        if (cfg.warningsEnabled()) {
            warnings.register(new HeartbeatTimeoutDetector(cfg));
            warnings.register(new PluginHungDetector(cfg));
            warnings.register(new EventSlowDetector(cfg));
            warnings.register(new TabSlowDetector(cfg));
            warnings.register(new SchedulerBacklogDetector(cfg));
            warnings.register(new SchedulerSaturationDetector(cfg));
        }
    }

    public static Profiler create(ProfileConfig cfg) {
        return new Profiler(cfg);
    }

    /** 注入到运行时组件的插桩接口（禁用时组件判空短路）。 */
    public ProfileSink sink() {
        return sink;
    }

    public boolean warningsEnabled() {
        return cfg.warningsEnabled();
    }

    /** 插件线程启动后注册：接入心跳 pong。 */
    public void registerPlugin(PluginThread pt) {
        plugins.put(pt.name, pt);
        pt.setPongHandler(ns -> onPong(pt.name, ns));
    }

    /** 插件卸载时注销。 */
    public void unregisterPlugin(String name) {
        plugins.remove(name);
        collector.removePlugin(name);
    }

    private void onWindow(WindowMetrics w) {
        ring.addLast(w);
        while (ring.size() > RING_CAPACITY) ring.removeFirst();
        if (cfg.warningsEnabled()) warnings.process(w);

        // 发送下一轮心跳 ping
        long now = System.nanoTime();
        for (var e : plugins.entrySet()) {
            collector.notePingSent(e.getKey(), now);
            e.getValue().queue.sendJs("{\"t\":\"DEBUG\",\"p\":\"ping\"}");
        }
        var t = track;
        if (t != null && t.expired(System.currentTimeMillis())) finishTrack();
    }

    private void onPong(String plugin, long pongNs) {
        long rt = collector.roundTrip(plugin, pongNs);
        if (rt >= 0) sink.onJsPing(new JsPingMetric(plugin, rt));
        var t = track;
        if (t != null) t.recordJsPing(rt >= 0 ? rt : 0);
    }

    public boolean handleProfile(CommandSender sender) {
        if (!cfg.fullEnabled()) {
            sender.sendMessage("Full profiling is disabled. Set 'profile.enabled: true' in plugins/Yeow/runtime/config.yml");
            return true;
        }
        List<WindowMetrics> windows = new ArrayList<>(ring);
        if (windows.size() < 5) {
            sender.sendMessage("Not enough data yet (" + windows.size() + "/5 windows) — wait a few seconds and try again.");
            return true;
        }
        var snap = ProfileSnapshot.build(windows, cfg.eventSlowMs(), cfg.tabSlowMs());
        String summary = ProfileFormatter.compact(snap);
        LOG.info(summary);
        sender.sendMessage(summary);
        var file = new File(org.bukkit.Bukkit.getPluginManager().getPlugin("Yeow").getDataFolder(),
            "yeow-profile-" + System.currentTimeMillis() + ".txt");
        ProfileFormatter.save(ProfileFormatter.detailed(snap, windows), file.toPath());
        sender.sendMessage("Detailed report saved to " + file.getAbsolutePath());
        return true;
    }

    public boolean handleTrack(CommandSender sender, String pluginName, int seconds) {
        if (!cfg.fullEnabled()) {
            sender.sendMessage("Track is disabled. Set 'profile.enabled: true' in plugins/Yeow/runtime/config.yml");
            return true;
        }
        if (seconds <= 0 || seconds > 300) { sender.sendMessage("Duration must be 1-300 seconds."); return true; }
        if (track != null) finishTrack();
        track = new TrackSession(pluginName, seconds);
        sender.sendMessage("Tracking " + pluginName + " for " + seconds + "s...");
        return true;
    }

    private void finishTrack() {
        var t = track;
        if (t == null) return;
        track = null;
        String report = t.generateReport();
        LOG.info(report);
        var file = new File(org.bukkit.Bukkit.getPluginManager().getPlugin("Yeow").getDataFolder(),
            "yeow-track-" + t.pluginName() + "-" + System.currentTimeMillis() + ".txt");
        ProfileFormatter.save(report, file.toPath());
        org.bukkit.Bukkit.getLogger().info("Track report saved to " + file.getAbsolutePath());
    }

    @Override
    public void close() {
        plugins.clear();
        ring.clear();
        track = null;
    }

    /** 组合 sink：转发收集器 + 活动追踪会话。 */
    private final class CompositeSink implements ProfileSink {
        @Override public boolean taskSampled() { return collector.taskSampled(); }
        @Override public void onTick(TickMetric m) { collector.onTick(m); }
        @Override public void onTask(TaskMetric m) {
            collector.onTask(m);
            var t = track;
            if (t != null) t.recordTask(m);
        }
        @Override public void onEvent(EventMetric m) {
            collector.onEvent(m);
            var t = track;
            if (t != null) t.recordEvent(m);
        }
        @Override public void onCommand(CommandMetric m) {
            collector.onCommand(m);
            var t = track;
            if (t != null) t.recordCommand(m);
        }
        @Override public void onJsPing(JsPingMetric m) {
            collector.onJsPing(m);
        }
    }
}
