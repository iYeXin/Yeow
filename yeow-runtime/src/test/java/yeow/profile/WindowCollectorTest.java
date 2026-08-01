package yeow.profile;

import org.junit.jupiter.api.Test;
import yeow.YeowConfig;
import yeow.profile.collector.WindowCollector;
import yeow.profile.collector.WindowMetrics;
import yeow.profile.instrumentation.CommandMetric;
import yeow.profile.instrumentation.EventMetric;
import yeow.profile.instrumentation.JsPingMetric;
import yeow.profile.instrumentation.TaskMetric;
import yeow.profile.instrumentation.TaskPriority;
import yeow.profile.instrumentation.TickMetric;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WindowCollectorTest {

    private static ProfileConfig cfg() {
        return ProfileConfig.from(new YeowConfig(new File("target/test-data")));
    }

    @Test
    void windowFlushAfter20Ticks() {
        var window = new AtomicReference<WindowMetrics>();
        var c = new WindowCollector(cfg(), window::set);
        for (int i = 0; i < 20; i++) c.onTick(new TickMetric(0, 5_000_000L, 0, 0, 0));
        assertNotNull(window.get());
        assertEquals(20, window.get().tickCount());
        assertEquals(20 * 5_000_000L, window.get().tickDurationSumNs());
    }

    @Test
    void lowBacklogExcludedFromHnBacklog() {
        var window = new AtomicReference<WindowMetrics>();
        var c = new WindowCollector(cfg(), window::set);
        for (int i = 0; i < 20; i++) {
            c.onTick(new TickMetric(0, 5_000_000L, 0, 0, 3)); // 仅 LOW 积压
        }
        assertEquals(0, window.get().hnBacklogTicks());
        assertEquals(20, window.get().lowBacklogTicks());
    }

    @Test
    void hnBacklogCounted() {
        var window = new AtomicReference<WindowMetrics>();
        var c = new WindowCollector(cfg(), window::set);
        for (int i = 0; i < 20; i++) {
            c.onTick(new TickMetric(0, 5_000_000L, i % 2 == 0 ? 2 : 0, 0, 0));
        }
        assertEquals(10, window.get().hnBacklogTicks());
    }

    @Test
    void eventAggregationWithSlowAndTimeout() {
        var window = new AtomicReference<WindowMetrics>();
        var c = new WindowCollector(cfg(), window::set);
        c.onEvent(new EventMetric("p", "blockBreak", 1_000_000L, false));     // 1ms 正常
        c.onEvent(new EventMetric("p", "blockBreak", 2_500_000_000L, false)); // 2.5s 慢（>2s）
        c.onEvent(new EventMetric("p", "playerJoin", 9_000_000_000L, true));  // 9s 超时
        for (int i = 0; i < 20; i++) c.onTick(new TickMetric(0, 1_000_000L, 0, 0, 0));

        var w = window.get();
        assertEquals(2, w.events().size());
        var bb = w.events().stream().filter(e -> e.eventType().equals("blockBreak")).findFirst().orElseThrow();
        assertEquals(2, bb.count());
        assertEquals(1, bb.slowCount());
        var pj = w.events().stream().filter(e -> e.eventType().equals("playerJoin")).findFirst().orElseThrow();
        assertEquals(1, pj.timeouts());
    }

    @Test
    void commandAggregation() {
        var window = new AtomicReference<WindowMetrics>();
        var c = new WindowCollector(cfg(), window::set);
        c.onCommand(new CommandMetric("p", "back", 600_000_000L, false)); // 600ms 慢（>500ms）
        c.onCommand(new CommandMetric("p", "ping", 50_000_000L, false));
        for (int i = 0; i < 20; i++) c.onTick(new TickMetric(0, 1_000_000L, 0, 0, 0));
        var w = window.get();
        assertEquals(2, w.commands().size());
        assertEquals(1, w.commands().stream().filter(cd -> cd.command().equals("back")).findFirst().orElseThrow().slowCount());
    }

    @Test
    void taskSamplingAndPluginBreakdown() {
        var window = new AtomicReference<WindowMetrics>();
        var c = new WindowCollector(cfg(), window::set);
        c.onTask(new TaskMetric("p", "world.setBlock", TaskPriority.NORMAL, 500_000L));
        c.onTask(new TaskMetric("p", "world.setBlock", TaskPriority.NORMAL, 700_000L));
        c.onTask(new TaskMetric("q", "player.get", TaskPriority.HIGH, 300_000L));
        for (int i = 0; i < 20; i++) c.onTick(new TickMetric(0, 1_000_000L, 0, 0, 0));
        var w = window.get();
        assertEquals(2, w.plugins().size());
        assertEquals(2, w.plugins().get("p").count());
        assertEquals(2, w.tasks().size()); // p:world.setBlock + q:player.get
        assertTrue(w.high().count() > 0);
        assertTrue(w.normal().count() > 0);
    }

    @Test
    void jsPingRecorded() {
        var window = new AtomicReference<WindowMetrics>();
        var c = new WindowCollector(cfg(), window::set);
        c.onJsPing(new JsPingMetric("p", 150_000L));
        for (int i = 0; i < 20; i++) c.onTick(new TickMetric(0, 1_000_000L, 0, 0, 0));
        assertEquals(150_000L, window.get().jsPings().get("p"));
    }
}
