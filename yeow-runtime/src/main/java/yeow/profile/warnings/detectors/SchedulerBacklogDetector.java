package yeow.profile.warnings.detectors;

import yeow.profile.ProfileConfig;
import yeow.profile.collector.WindowMetrics;
import yeow.profile.warnings.Warning;
import yeow.profile.warnings.WarningDetector;
import yeow.profile.warnings.WarningLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * 调度器积压告警（HIGH/NORMAL 专属——LOW 允许积压，不计入）：
 * - {@code budget.congested} — 滑动窗口内 HIGH/NORMAL 积压 tick 数达到 backlog-threshold（默认 35/40）。
 *   HIGH/NORMAL 承载实时性与交互响应，持续积压意味着任务提交速度超过处理能力，预算将被自动扩容。
 * - {@code budget.restored}  — 连续 40 tick 无积压后恢复（INFO）。
 */
public final class SchedulerBacklogDetector implements WarningDetector {
    private final ProfileConfig cfg;

    private final int[] window;
    private int idx;
    private int backlogCount;
    private int clearTicks;
    private boolean congested;

    public SchedulerBacklogDetector(ProfileConfig cfg) {
        this.cfg = cfg;
        this.window = new int[Math.max(cfg.backlogWindowTicks(), 1)];
    }

    @Override public String code() { return "budget.congested"; }
    @Override public WarningLevel level() { return WarningLevel.WARN; }
    @Override public long cooldownMs() { return cfg.warnCooldownSec() * 1000L; }

    @Override
    public List<Warning> check(WindowMetrics w) {
        // 把窗口内的积压 tick 数摊入滑动窗口（逐 tick 展开）
        for (int i = 0; i < w.hnBacklogTicks(); i++) pushBacklog(true);
        for (int i = 0; i < w.tickCount() - w.hnBacklogTicks(); i++) pushBacklog(false);

        var out = new ArrayList<Warning>();
        if (!congested && backlogCount >= cfg.backlogThreshold()) {
            congested = true;
            clearTicks = 0;
            out.add(new Warning(level(), code(), null, "scheduler backlog",
                List.of(
                    String.format(" HIGH/NORMAL 队列在最近 %d tick 中积压 %d 次（阈值 %d）。",
                        cfg.backlogWindowTicks(), backlogCount, cfg.backlogThreshold()),
                    " 实时调度队列不应存在积压——任务提交速度超过了处理能力。",
                    " 运行时将自动扩容 tick 预算（不影响 LOW 批量队列）。",
                    " 建议：排查高频循环提交任务的插件（可用 /yeow track 定位）。")));
        } else if (congested) {
            clearTicks += w.tickCount();
            if (clearTicks >= cfg.backlogWindowTicks()) {
                congested = false;
                backlogCount = 0;
                clearTicks = 0;
                out.add(new Warning(WarningLevel.INFO, "budget.restored", null, "budget restored",
                    List.of(" HIGH/NORMAL 队列已连续 " + cfg.backlogWindowTicks() + " tick 无积压，预算恢复基准。")));
            }
        }
        return out;
    }

    private void pushBacklog(boolean backlog) {
        backlogCount -= window[idx];
        window[idx] = backlog ? 1 : 0;
        backlogCount += window[idx];
        idx = (idx + 1) % window.length;
    }
}
