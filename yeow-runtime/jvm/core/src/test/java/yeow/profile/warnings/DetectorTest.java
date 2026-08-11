package yeow.profile.warnings;

import org.junit.jupiter.api.Test;
import yeow.profile.ProfileConfig;
import yeow.profile.collector.LogHistogram;
import yeow.profile.collector.WindowMetrics;
import yeow.profile.warnings.detectors.EventSlowDetector;
import yeow.profile.warnings.detectors.HeartbeatTimeoutDetector;
import yeow.profile.warnings.detectors.PluginHungDetector;
import yeow.profile.warnings.detectors.SchedulerBacklogDetector;
import yeow.profile.warnings.detectors.SchedulerSaturationDetector;
import yeow.profile.warnings.detectors.TabSlowDetector;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DetectorTest {

    private static final ProfileConfig CFG = ProfileConfig.from(
        new yeow.YeowConfig(new java.io.File("target/test-data")));

    private static WindowMetrics window(int ticks, int hnBacklog, int lowBacklog,
            WindowMetrics.TierMetrics high, WindowMetrics.TierMetrics normal, WindowMetrics.TierMetrics low,
            List<WindowMetrics.EventAgg> events, List<WindowMetrics.CommandAgg> commands,
            List<String> pinged, Map<String, Long> jsPings, long tickDurNs) {
        return new WindowMetrics(0, ticks, tickDurNs, tickDurNs,
            hnBacklog, lowBacklog, pinged, high, normal, low,
            Map.of(), Map.of(), jsPings, events, commands);
    }

    private static WindowMetrics.TierMetrics tier(long count, long sumNs) {
        var h = new LogHistogram();
        if (count > 0) h.record(sumNs / count);
        return new WindowMetrics.TierMetrics(count, sumNs, sumNs,
            h.buckets(), h.percentile(0.50), h.percentile(0.95));
    }

    @Test
    void heartbeatWarnsWhenNoPong() {
        var d = new HeartbeatTimeoutDetector(CFG);
        // 发了 ping 但窗口内无 pong（死循环场景）→ 告警
        var w = window(20, 0, 0, tier(0,0), tier(0,0), tier(0,0),
            List.of(), List.of(), List.of("hungry"), Map.of(), 1_000_000_000L);
        assertFalse(d.check(w).isEmpty());
        // 有 pong 且正常 → 不告警
        var ok = window(20, 0, 0, tier(0,0), tier(0,0), tier(0,0),
            List.of(), List.of(), List.of("fine"), Map.of("fine", 50_000L), 1_000_000_000L);
        assertTrue(d.check(ok).isEmpty());
    }

    @Test
    void heartbeatWarnsOnSlowRoundTrip() {
        var d = new HeartbeatTimeoutDetector(CFG);
        var w = window(20, 0, 0, tier(0,0), tier(0,0), tier(0,0),
            List.of(), List.of(), List.of("slow"), Map.of("slow", 300_000_000L), 1_000_000_000L);
        assertFalse(d.check(w).isEmpty());
    }

    /** 构造一个 1s 窗口：startMs 起、20 tick、插件已被 ping 但无 pong。 */
    private static WindowMetrics hungWindow(long startMs, String plugin) {
        return new WindowMetrics(startMs, 20, 1_000_000_000L, 1_000_000_000L, 0, 0,
            List.of(plugin), tier(0,0), tier(0,0), tier(0,0),
            Map.of(), Map.of(), Map.of(), List.of(), List.of());
    }

    @Test
    void pluginHungFromFirstUnresponsiveWindow() {
        var d = new PluginHungDetector(CFG);
        long t0 = 10_000L;
        // 从加载起就死循环：从未响应过任何 ping → 以第一个无响应窗口为基准
        for (int i = 0; i < 30; i++) { // silent = i 秒（0..29）
            var w = hungWindow(t0 + i * 1000L, "hungry");
            assertTrue(d.check(w).isEmpty(), "silent " + i + "s must not warn");
        }
        var w30 = hungWindow(t0 + 30 * 1000L, "hungry"); // silent = 30s
        var warns = d.check(w30);
        assertTrue(warns.stream().anyMatch(x -> x.code().equals("plugin.hung")));
    }

    @Test
    void pluginHungFromLastResponse() {
        var d = new PluginHungDetector(CFG);
        long t0 = 10_000L;
        // 曾正常响应（窗口 end = t0+1000 → 基准）
        var ok = new WindowMetrics(t0, 20, 1_000_000_000L, 1_000_000_000L, 0, 0,
            List.of("p"), tier(0,0), tier(0,0), tier(0,0),
            Map.of(), Map.of(), Map.of("p", 50_000L), List.of(), List.of());
        assertTrue(d.check(ok).isEmpty());
        // 之后死循环：窗口 start = t0+(i+2)s，end = t0+(i+3)s → silent = (i+2)s
        for (int i = 0; i < 28; i++) { // silent = 2..29s
            var w = hungWindow(t0 + (i + 2) * 1000L, "p");
            assertTrue(d.check(w).isEmpty(), "silent " + (i + 2) + "s must not warn");
        }
        var w30 = hungWindow(t0 + 30 * 1000L, "p"); // end = t0+31s → silent = 30s
        var warns = d.check(w30);
        assertTrue(warns.stream().anyMatch(x -> x.code().equals("plugin.hung")));
    }

    @Test
    void pluginHungRecoversAfterPong() {
        var d = new PluginHungDetector(CFG);
        long t0 = 10_000L;
        // 死循环 30s 触发（silent = i 秒，基准 = 窗口 0 的 end = t0+1000）
        for (int i = 0; i < 30; i++) d.check(hungWindow(t0 + i * 1000L, "p"));
        var w30 = hungWindow(t0 + 30 * 1000L, "p"); // silent = 30s → 触发
        assertTrue(d.check(w30).stream().anyMatch(x -> x.code().equals("plugin.hung")));
        // 恢复响应 → 更新基准，不再告警
        var ok = new WindowMetrics(t0 + 31 * 1000L, 20, 1_000_000_000L, 1_000_000_000L, 0, 0,
            List.of("p"), tier(0,0), tier(0,0), tier(0,0),
            Map.of(), Map.of(), Map.of("p", 50_000L), List.of(), List.of());
        assertTrue(d.check(ok).isEmpty());
        // 再次死循环需重新积累（新基准 = t0+32s）
        for (int i = 0; i < 29; i++) { // silent = i+1 .. 29s
            var w = hungWindow(t0 + 32 * 1000L + i * 1000L, "p");
            assertTrue(d.check(w).isEmpty(), "silent " + (i + 1) + "s must not warn");
        }
        var w30b = hungWindow(t0 + 61 * 1000L, "p"); // end = t0+62s → silent = 30s
        assertTrue(d.check(w30b).stream().anyMatch(x -> x.code().equals("plugin.hung")));
    }

    @Test
    void eventSlowWarnsAt2sNotTimeout() {        var d = new EventSlowDetector(CFG);
        var w = window(20, 0, 0, tier(0,0), tier(0,0), tier(0,0),
            List.of(new WindowMetrics.EventAgg("p", "blockBreak", 2_500_000_000L, 1, 2_500_000_000L, 1, 0)),
            List.of(), List.of(), Map.of(), 1_000_000_000L);
        var warns = d.check(w);
        assertTrue(warns.stream().anyMatch(x -> x.code().equals("event.slow")));
        assertFalse(warns.stream().anyMatch(x -> x.code().equals("event.timeout")));
    }

    @Test
    void eventTimeoutSeparateCode() {
        var d = new EventSlowDetector(CFG);
        var w = window(20, 0, 0, tier(0,0), tier(0,0), tier(0,0),
            List.of(new WindowMetrics.EventAgg("p", "blockBreak", 6_000_000_000L, 1, 6_000_000_000L, 1, 1)),
            List.of(), List.of(), Map.of(), 1_000_000_000L);
        var warns = d.check(w);
        assertTrue(warns.stream().anyMatch(x -> x.code().equals("event.timeout")));
    }

    @Test
    void tabSlowWarns() {
        var d = new TabSlowDetector(CFG);
        var w = window(20, 0, 0, tier(0,0), tier(0,0), tier(0,0),
            List.of(),
            List.of(new WindowMetrics.CommandAgg("p", "back", 600_000_000L, 1, 600_000_000L, 1, 0)),
            List.of(), Map.of(), 1_000_000_000L);
        assertFalse(d.check(w).isEmpty());
    }

    @Test
    void backlogTriggersAt35Of40() {
        var d = new SchedulerBacklogDetector(CFG);
        var w = window(20, 0, 0, tier(0,0), tier(0,0), tier(0,0),
            List.of(), List.of(), List.of(), Map.of(), 1_000_000_000L);
        // 第一窗口无积压（0/20）
        assertTrue(d.check(w).isEmpty());
        // 两个窗口各 20 积压 → 40/40 → 触发
        var busy = window(20, 20, 0, tier(0,0), tier(0,0), tier(0,0),
            List.of(), List.of(), List.of(), Map.of(), 1_000_000_000L);
        assertTrue(d.check(busy).isEmpty()); // 20/40 未到阈值
        var warns = d.check(busy);           // 40/40 ≥ 35 → 触发
        assertTrue(warns.stream().anyMatch(x -> x.code().equals("budget.congested")));
    }

    @Test
    void lowBacklogDoesNotTriggerBacklog() {
        var d = new SchedulerBacklogDetector(CFG);
        var w = window(20, 0, 20, tier(0,0), tier(0,0), tier(0,0),
            List.of(), List.of(), List.of(), Map.of(), 1_000_000_000L);
        assertTrue(d.check(w).isEmpty());
        assertTrue(d.check(w).isEmpty()); // LOW 积压不累计
    }

    @Test
    void saturationOnlyCountsHn() {
        var d = new SchedulerSaturationDetector(CFG);
        // 20 tick × 10ms；HIGH+NORMAL 共 180ms → 90% > 80% → 告警
        var w = window(20, 0, 0,
            tier(10, 90_000_000L), tier(10, 90_000_000L), tier(0, 0),
            List.of(), List.of(), List.of(), Map.of(), 200_000_000L);
        assertFalse(d.check(w).isEmpty());

        // 仅 LOW 大量执行（180ms）→ 不告警（批量队列允许）
        var w2 = window(20, 0, 0,
            tier(0, 0), tier(0, 0), tier(100, 180_000_000L),
            List.of(), List.of(), List.of(), Map.of(), 200_000_000L);
        assertTrue(d.check(w2).isEmpty());
    }
}
