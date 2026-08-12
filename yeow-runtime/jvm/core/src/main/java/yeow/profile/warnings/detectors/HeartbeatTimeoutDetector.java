package yeow.profile.warnings.detectors;

import yeow.profile.ProfileConfig;
import yeow.profile.collector.WindowMetrics;
import yeow.profile.warnings.Warning;
import yeow.profile.warnings.WarningDetector;
import yeow.profile.warnings.WarningLevel;

import java.util.ArrayList;
import java.util.List;

/** 心跳超时：某插件单次心跳往返超过阈值（默认 200ms）--JS 线程响应缓慢。 */
public final class HeartbeatTimeoutDetector implements WarningDetector {
    private final ProfileConfig cfg;

    public HeartbeatTimeoutDetector(ProfileConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String code() { return "heartbeat.timeout"; }
    @Override public WarningLevel level() { return WarningLevel.WARN; }
    @Override public long cooldownMs() { return cfg.warnCooldownSec() * 1000L; }

    @Override
    public List<Warning> check(WindowMetrics w) {
        long threshold = cfg.latencyWarnMs() * 1_000_000L;
        var out = new ArrayList<Warning>();
        for (var plugin : w.pingedPlugins()) {
            Long rt = w.jsPings().get(plugin);
            if (rt == null) {
                // 发出 ping 但本窗口无任何 pong -- JS 线程未响应（死循环/长阻塞）
                out.add(new Warning(level(), code(), plugin, "heartbeat timeout",
                    List.of(
                        " JS 线程在心跳周期内没有任何响应（可能死循环或长阻塞）。",
                        " 影响：事件与命令处理延迟、TPS 下降。",
                        " 建议：避免长时间同步操作，改用异步 API 或分片执行；",
                        " 若持续超过 " + cfg.suspendWarnSec() + "s 将升级为 plugin.hung。")));
            } else if (rt > threshold) {
                double ms = rt / 1_000_000.0;
                out.add(new Warning(level(), code(), plugin, "heartbeat timeout",
                    List.of(
                        String.format(" JS 线程响应缓慢：单次心跳 %.0fms（阈值 %dms）。", ms, cfg.latencyWarnMs()),
                        " 可能原因：长同步循环 / 死循环 / 阻塞式 IO。",
                        " 影响：事件与命令处理延迟、TPS 下降。",
                        " 建议：避免长时间同步操作，改用异步 API 或分片执行。")));
            }
        }
        return out;
    }
}
