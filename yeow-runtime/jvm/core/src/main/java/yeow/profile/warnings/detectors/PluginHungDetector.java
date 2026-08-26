package yeow.profile.warnings.detectors;

import yeow.profile.ProfileConfig;
import yeow.profile.collector.WindowMetrics;
import yeow.profile.warnings.Warning;
import yeow.profile.warnings.WarningDetector;
import yeow.profile.warnings.WarningLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件挂起：期望响应（发送过 ping）但连续超过 suspend-warn-seconds（默认 30s）无任何 pong。
 * 挂起意味着插件线程实际已死（死循环/死锁），SEVERE。
 * 自恢复：超过 auto-reload-hung-seconds（默认 120s）且满足冷却/重试限制时自动重载（默认启用）。
 */
public final class PluginHungDetector implements WarningDetector {
    private final ProfileConfig cfg;
    /** plugin → 最近一次响应对应的窗口结束时间（ms）。 */
    private final Map<String, Long> lastResponsive = new HashMap<>();

    public PluginHungDetector(ProfileConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String code() { return "plugin.hung"; }
    @Override public WarningLevel level() { return WarningLevel.SEVERE; }
    @Override public long cooldownMs() { return cfg.warnCooldownSec() * 1000L; }

    @Override
    public List<Warning> check(WindowMetrics w) {
        long windowEndMs = w.startMs() + (w.tickCount() > 0 ? 1000 : 0);
        // 本窗口有响应的插件 → 更新最近响应时间
        for (var plugin : w.jsPings().keySet()) {
            lastResponsive.put(plugin, windowEndMs);
        }
        // 已不再被监控的插件（卸载等）→ 清理状态
        lastResponsive.keySet().removeIf(p ->
            !w.pingedPlugins().contains(p) && !w.jsPings().containsKey(p));

        long thresholdMs = cfg.suspendWarnSec() * 1000L;
        var out = new ArrayList<Warning>();
        for (var plugin : w.pingedPlugins()) {
            if (w.jsPings().containsKey(plugin)) continue; // 本窗口有响应
            // 从未响应过（或从加载起就死循环）的插件：以第一个无响应窗口为基准，
            // 而不是当前窗口--否则 silentMs 恒为 0，hung 永不触发。
            long last = lastResponsive.computeIfAbsent(plugin, p -> windowEndMs);
            long silentMs = windowEndMs - last;
            if (silentMs >= thresholdMs) {
                var lines = new ArrayList<String>();
                lines.add(String.format(" 插件线程已 %d 秒无响应（阈值 %ds）。", silentMs / 1000, cfg.suspendWarnSec()));
                lines.add(" 可能原因：死循环 / 死锁 / 完全阻塞的同步调用。");
                lines.add(" 插件已实际停止工作：事件、命令、定时器均不再响应。");
                if (cfg.autoReloadEnabled()) {
                    long reloadSec = cfg.autoReloadHungSec();
                    if (silentMs >= reloadSec * 1000L) {
                        lines.add(String.format(" 已超过自恢复阈值 %ds — 将在后台自动重载（冷却 %ds，最大 %d 次）。",
                            reloadSec, cfg.autoReloadCooldownSec(), cfg.autoReloadMaxRetries()));
                    } else {
                        long remain = reloadSec - silentMs / 1000;
                        lines.add(String.format(" 将在 %d 秒后自动重载（阈值 %ds，冷却 %ds，最大 %d 次；可 /yeow reload 手动恢复）。",
                            remain, reloadSec, cfg.autoReloadCooldownSec(), cfg.autoReloadMaxRetries()));
                    }
                } else {
                    lines.add(" 恢复方法：重启服务器，或 /yeow reload 该插件。");
                    lines.add(String.format(" （自恢复已关闭：profile.auto-reload-enabled=false；重载阈值 %ds）", cfg.autoReloadHungSec()));
                }
                out.add(new Warning(level(), code(), plugin, "plugin thread hung", List.copyOf(lines)));
            }
        }
        return out;
    }
}
