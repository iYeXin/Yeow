package yeow;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;

public class YeowConfig {
    private final File dataFolder;
    private final int tickBudgetMs;
    private final double[] priorityRatios;
    private final boolean autoDemote;
    private final int demoteThreshold;
    private final int idleSpinUs;
    private final boolean concurrentEvents;
    private final boolean profileEnabled;
    private final boolean profileWarningsEnabled;
    private final int profileLatencyWarnThresholdMs;
    private final double profileLatencyDegradedMultiplier;
    private final int profileCallbackTimeoutEventMs;
    private final int profileCallbackTimeoutTabCompleteMs;
    private final int profileEventSlowThresholdMs;
    private final int profileTabSlowThresholdMs;
    private final int profileWarnCooldownSeconds;
    private final int profileSuspendWarnSeconds;
    private final int profileBacklogThreshold;
    private final int profileBacklogWindowTicks;
    private final int profileSaturationPct;
    private final boolean profileScalerEnabled;
    private final double profileScalerFactor;
    private final double profileScalerMax;
    private final boolean requireNativeApproval;

    public YeowConfig(File dataFolder) {
        this.dataFolder = dataFolder;
        dataFolder.mkdirs();
        var runtimeDir = new File(dataFolder, "runtime");
        runtimeDir.mkdirs();
        var file = new File(runtimeDir, "config.yml");
        var def = new YamlConfiguration();
        def.set("tick-budget-ms", 20);
        def.set("priority-ratios", java.util.List.of(0.5, 0.3, 0.2));
        def.set("auto-demote", true);
        def.set("demote-threshold", 200);
        def.set("idle-spin-us", 100);
        def.set("concurrent-events", true);
        def.set("profile.enabled", false);
        def.set("profile.warnings-enabled", true);
        def.set("profile.latency-warn-threshold-ms", 200);
        def.set("profile.latency-degraded-multiplier", 5.0);
        def.set("profile.callback-timeout-event-ms", 5000);
        def.set("profile.callback-timeout-tabcomplete-ms", 1000);
        def.set("profile.event-slow-threshold-ms", 2000);
        def.set("profile.tab-slow-threshold-ms", 500);
        def.set("profile.warn-cooldown-seconds", 1800);
        def.set("profile.suspend-warn-seconds", 30);
        def.set("profile.backlog-threshold", 35);
        def.set("profile.backlog-window-ticks", 40);
        def.set("profile.scheduler-saturation-pct", 80);
        def.set("profile.scaler.enabled", true);
        def.set("profile.scaler.expansion-factor", 1.3);
        def.set("profile.scaler.max-multiplier", 3.0);
        // 原生服务批准：默认要求批准（全部原生服务视为不安全，需 /yeow approve <plugin>）。
        // 内存是唯一信任源——运行期间修改本文件不生效（视为无效篡改），关闭时按内存合并写回。
        def.set("native-service-require-approval", true);

        var cfg = YamlConfiguration.loadConfiguration(file);
        cfg.setDefaults(def);
        cfg.options().copyDefaults(true);
        if (!file.exists() || file.length() == 0) try { cfg.save(file); } catch (Exception ignored) {}

        this.tickBudgetMs = cfg.getInt("tick-budget-ms", 20);
        this.autoDemote = cfg.getBoolean("auto-demote", true);
        this.demoteThreshold = cfg.getInt("demote-threshold", 200);
        this.idleSpinUs = cfg.getInt("idle-spin-us", 100);
        this.concurrentEvents = cfg.getBoolean("concurrent-events", true);
        this.profileEnabled = cfg.getBoolean("profile.enabled", true);
        this.profileWarningsEnabled = cfg.getBoolean("profile.warnings-enabled", true);
        this.profileLatencyWarnThresholdMs = cfg.getInt("profile.latency-warn-threshold-ms", 200);
        this.profileLatencyDegradedMultiplier = cfg.getDouble("profile.latency-degraded-multiplier", 5.0);
        this.profileCallbackTimeoutEventMs = cfg.getInt("profile.callback-timeout-event-ms", 5000);
        this.profileCallbackTimeoutTabCompleteMs = cfg.getInt("profile.callback-timeout-tabcomplete-ms", 1000);
        this.profileEventSlowThresholdMs = cfg.getInt("profile.event-slow-threshold-ms", 2000);
        this.profileTabSlowThresholdMs = cfg.getInt("profile.tab-slow-threshold-ms", 500);
        this.profileWarnCooldownSeconds = cfg.getInt("profile.warn-cooldown-seconds", 60);
        this.profileSuspendWarnSeconds = cfg.getInt("profile.suspend-warn-seconds", 30);
        this.profileBacklogThreshold = cfg.getInt("profile.backlog-threshold", 35);
        this.profileBacklogWindowTicks = cfg.getInt("profile.backlog-window-ticks", 40);
        this.profileSaturationPct = cfg.getInt("profile.scheduler-saturation-pct", 80);
        this.profileScalerEnabled = cfg.getBoolean("profile.scaler.enabled", true);
        this.profileScalerFactor = cfg.getDouble("profile.scaler.expansion-factor", 1.3);
        this.profileScalerMax = cfg.getDouble("profile.scaler.max-multiplier", 3.0);
        this.requireNativeApproval = cfg.getBoolean("native-service-require-approval", true);

        var ratios = cfg.getDoubleList("priority-ratios");
        this.priorityRatios = ratios.size() == 3
            ? new double[]{ratios.get(0), ratios.get(1), ratios.get(2)}
            : new double[]{0.5, 0.3, 0.2};
    }

    public long tickBudgetNs() { return tickBudgetMs * 1_000_000L; }
    public double[] priorityRatios() { return priorityRatios; }
    public boolean autoDemote() { return autoDemote; }
    public int demoteThreshold() { return demoteThreshold; }
    public int idleSpinUs() { return idleSpinUs; }
    public boolean concurrentEvents() { return concurrentEvents; }
    public boolean profileEnabled() { return profileEnabled; }
    public boolean profileWarningsEnabled() { return profileWarningsEnabled; }
    public int profileLatencyWarnThresholdMs() { return profileLatencyWarnThresholdMs; }
    public double profileLatencyDegradedMultiplier() { return profileLatencyDegradedMultiplier; }
    public int profileCallbackTimeoutEventMs() { return profileCallbackTimeoutEventMs; }
    public int profileCallbackTimeoutTabCompleteMs() { return profileCallbackTimeoutTabCompleteMs; }
    public int profileEventSlowThresholdMs() { return profileEventSlowThresholdMs; }
    public int profileTabSlowThresholdMs() { return profileTabSlowThresholdMs; }
    public int profileWarnCooldownSeconds() { return profileWarnCooldownSeconds; }
    public int profileSuspendWarnSeconds() { return profileSuspendWarnSeconds; }
    public int profileBacklogThreshold() { return profileBacklogThreshold; }
    public int profileBacklogWindowTicks() { return profileBacklogWindowTicks; }
    public int profileSaturationPct() { return profileSaturationPct; }
    public boolean profileScalerEnabled() { return profileScalerEnabled; }
    public double profileScalerFactor() { return profileScalerFactor; }
    public double profileScalerMax() { return profileScalerMax; }

    /** 原生服务是否需要批准（默认 true；false = 默认批准）。内存为唯一信任源。 */
    public boolean requireNativeApproval() { return requireNativeApproval; }

    /**
     * 按内存值合并写回 config.yml 的 native-service-require-approval 项
     * （服务器关闭、插件卸载完成后调用；只覆盖该项，保留用户其他配置）。
     */
    public void saveRequireApproval() {
        try {
            var file = new File(new File(dataFolder, "runtime"), "config.yml");
            var cfg = YamlConfiguration.loadConfiguration(file);
            cfg.set("native-service-require-approval", requireNativeApproval);
            cfg.save(file);
        } catch (Exception ignored) { /* 写回失败不影响关闭流程 */ }
    }
}
