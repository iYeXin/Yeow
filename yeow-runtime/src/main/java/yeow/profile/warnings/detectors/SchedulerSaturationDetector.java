package yeow.profile.warnings.detectors;

import yeow.profile.ProfileConfig;
import yeow.profile.collector.WindowMetrics;
import yeow.profile.warnings.Warning;
import yeow.profile.warnings.WarningDetector;
import yeow.profile.warnings.WarningLevel;

import java.util.List;

/**
 * 调度饱和告警：窗口内 HIGH+NORMAL 执行时间占 tick 总时长的比例超过阈值（默认 80%）。
 * 实时队列几乎吃满全部 tick——LOW 批量任务将被无限期推迟，交互响应同样受损。
 */
public final class SchedulerSaturationDetector implements WarningDetector {
    private final ProfileConfig cfg;

    public SchedulerSaturationDetector(ProfileConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String code() { return "scheduler.saturated"; }
    @Override public WarningLevel level() { return WarningLevel.WARN; }
    @Override public long cooldownMs() { return cfg.warnCooldownSec() * 1000L; }

    @Override
    public List<Warning> check(WindowMetrics w) {
        if (w.tickDurationSumNs() <= 0) return List.of();
        double pct = w.hnExecSumNs() * 100.0 / w.tickDurationSumNs();
        if (pct < cfg.saturationPct()) return List.of();
        return List.of(new Warning(level(), code(), null, "scheduler saturated",
            List.of(
                String.format(" HIGH/NORMAL 执行占 tick 时长的 %.0f%%（阈值 %d%%）。",
                    pct, cfg.saturationPct()),
                " 实时调度接近饱和，LOW 批量任务将被推迟，交互响应可能下降。",
                " 建议：检查是否有插件在实时队列中高频提交重任务（/yeow track 定位）。")));
    }
}
