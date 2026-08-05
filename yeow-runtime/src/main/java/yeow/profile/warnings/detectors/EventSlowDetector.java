package yeow.profile.warnings.detectors;

import yeow.profile.ProfileConfig;
import yeow.profile.collector.WindowMetrics;
import yeow.profile.warnings.Warning;
import yeow.profile.warnings.WarningDetector;
import yeow.profile.warnings.WarningLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件响应告警：
 * - {@code event.slow}   — 窗口内存在事件响应超过 event-slow-threshold-ms（默认 2000ms）但未超时。
 *   阻塞主线程 2 秒已不可容忍，开发者必须重视（超时仍是 callback-timeout-event-ms = 5s）。
 * - {@code event.timeout} — 窗口内存在事件等待超时（5s，运行时已释放事件）。
 */
public final class EventSlowDetector implements WarningDetector {
    private final ProfileConfig cfg;

    public EventSlowDetector(ProfileConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String code() { return "event.slow"; }
    @Override public WarningLevel level() { return WarningLevel.WARN; }
    @Override public long cooldownMs() { return cfg.warnCooldownSec() * 1000L; }

    @Override
    public List<Warning> check(WindowMetrics w) {
        var out = new ArrayList<Warning>();
        for (var ev : w.events()) {
            if (ev.slowCount() > 0) {
                out.add(new Warning(level(), code(), ev.plugin(),
                    "event response slow",
                    List.of(
                        String.format(" 事件 %s 单次响应最长 %.0fms（警告阈值 %dms，超时 %dms）。",
                            ev.eventType(), ev.maxMs(), cfg.eventSlowMs(), cfg.callbackTimeoutEventMs()),
                        " 阻塞主线程超过 2s 已不可容忍——玩家交互与 TPS 都会受影响。",
                        " 建议：事件处理器改为异步（async handler）或手动释放（manualRelease）。")));
            }
            if (ev.timeouts() > 0) {
                out.add(new Warning(WarningLevel.WARN, "event.timeout", ev.plugin(),
                    "event callback timeout",
                    List.of(
                        String.format(" 事件 %s 窗口内超时 %d 次（等待上限 %dms）。",
                            ev.eventType(), ev.timeouts(), cfg.callbackTimeoutEventMs()),
                        " 事件已被运行时强制释放，此前的取消/修改不生效。",
                        " 建议：使用异步 handler 或手动 complete()；若事件处理器内是同步阻塞（如同步 http/fs 调用），",
                        " 请检查 JS 线程是否存在长同步阻塞——阻塞期间事件/命令/回调均无法投递。" +
                        " 同步请求请改用异步 API（request/fetch/requestAsync）。")));
            }
        }
        return out;
    }
}
