package yeow.profile.warnings.detectors;

import yeow.profile.ProfileConfig;
import yeow.profile.collector.WindowMetrics;
import yeow.profile.warnings.Warning;
import yeow.profile.warnings.WarningDetector;
import yeow.profile.warnings.WarningLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Tab 补全响应告警：
 * - {@code tab.slow}    — 窗口内补全响应超过 tab-slow-threshold-ms（默认 500ms）。
 * - {@code tab.timeout} — 补全等待超时（callback-timeout-tabcomplete-ms = 1s）。
 */
public final class TabSlowDetector implements WarningDetector {
    private final ProfileConfig cfg;

    public TabSlowDetector(ProfileConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String code() { return "tab.slow"; }
    @Override public WarningLevel level() { return WarningLevel.WARN; }
    @Override public long cooldownMs() { return cfg.warnCooldownSec() * 1000L; }

    @Override
    public List<Warning> check(WindowMetrics w) {
        var out = new ArrayList<Warning>();
        for (var c : w.commands()) {
            if (c.slowCount() > 0) {
                out.add(new Warning(level(), code(), c.plugin(),
                    "tab-complete slow",
                    List.of(
                        String.format(" 命令 %s 补全最长 %.0fms（警告阈值 %dms，超时 %dms）。",
                            c.command(), c.maxMs(), cfg.tabSlowMs(), cfg.callbackTimeoutTabMs()),
                        " 玩家输入该命令时可能感到卡顿。",
                        " 建议：优化 completer，或对结果做缓存。")));
            }
            if (c.timeouts() > 0) {
                out.add(new Warning(WarningLevel.WARN, "tab.timeout", c.plugin(),
                    "tab-complete timeout",
                    List.of(
                        String.format(" 命令 %s 窗口内补全超时 %d 次（等待上限 %dms）。",
                            c.command(), c.timeouts(), cfg.callbackTimeoutTabMs()),
                        " 已返回空补全列表。")));
            }
        }
        return out;
    }
}
