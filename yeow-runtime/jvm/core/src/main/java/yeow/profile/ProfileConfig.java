package yeow.profile;

import yeow.YeowConfig;

/**
 * Profile 只读配置视图（从 {@link YeowConfig} 提取，保持单点配置）。
 */
public final class ProfileConfig {
    private final boolean fullEnabled;
    private final boolean warningsEnabled;
    private final int warnCooldownSec;
    private final int latencyWarnMs;
    private final int eventSlowMs;
    private final int tabSlowMs;
    private final int callbackTimeoutEventMs;
    private final int callbackTimeoutTabMs;
    private final int suspendWarnSec;
    private final int backlogThreshold;
    private final int backlogWindowTicks;
    private final int saturationPct;
    private final boolean scalerEnabled;
    private final double scalerFactor;
    private final double scalerMax;

    private ProfileConfig(YeowConfig c) {
        this.fullEnabled = c.profileEnabled();
        this.warningsEnabled = c.profileWarningsEnabled();
        this.warnCooldownSec = c.profileWarnCooldownSeconds();
        this.latencyWarnMs = c.profileLatencyWarnThresholdMs();
        this.eventSlowMs = c.profileEventSlowThresholdMs();
        this.tabSlowMs = c.profileTabSlowThresholdMs();
        this.callbackTimeoutEventMs = c.profileCallbackTimeoutEventMs();
        this.callbackTimeoutTabMs = c.profileCallbackTimeoutTabCompleteMs();
        this.suspendWarnSec = c.profileSuspendWarnSeconds();
        this.backlogThreshold = c.profileBacklogThreshold();
        this.backlogWindowTicks = c.profileBacklogWindowTicks();
        this.saturationPct = c.profileSaturationPct();
        this.scalerEnabled = c.profileScalerEnabled();
        this.scalerFactor = c.profileScalerFactor();
        this.scalerMax = c.profileScalerMax();
    }

    public static ProfileConfig from(YeowConfig c) {
        return new ProfileConfig(c);
    }

    /** 全量分析（逐任务计时、per-plugin/per-task 分解、/yeow profile 报告）。 */
    public boolean fullEnabled() { return fullEnabled; }

    /** 运行时预警引擎（窗口级聚合，默认开启）。 */
    public boolean warningsEnabled() { return warningsEnabled; }

    public int warnCooldownSec() { return warnCooldownSec; }
    public int latencyWarnMs() { return latencyWarnMs; }

    /** 事件响应警告阈值（默认 2000ms；超时仍为 callbackTimeoutEventMs）。 */
    public int eventSlowMs() { return eventSlowMs; }

    /** Tab 补全响应警告阈值（默认 500ms；超时仍为 callbackTimeoutTabMs）。 */
    public int tabSlowMs() { return tabSlowMs; }

    /** 事件回调等待超时（运行时行为，EventBridge 使用）。 */
    public int callbackTimeoutEventMs() { return callbackTimeoutEventMs; }

    /** Tab 补全等待超时（运行时行为，CommandTasks 使用）。 */
    public int callbackTimeoutTabMs() { return callbackTimeoutTabMs; }

    public int suspendWarnSec() { return suspendWarnSec; }

    /** 扩容信号：滑动窗口内 HIGH/NORMAL 积压 tick 数阈值（默认 35）。 */
    public int backlogThreshold() { return backlogThreshold; }

    /** 积压统计窗口（默认 40 tick）。 */
    public int backlogWindowTicks() { return backlogWindowTicks; }

    /** 调度饱和告警百分比（默认 80）。 */
    public int saturationPct() { return saturationPct; }

    public boolean scalerEnabled() { return scalerEnabled; }
    public double scalerFactor() { return scalerFactor; }
    public double scalerMax() { return scalerMax; }
}
