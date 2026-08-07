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
    private final long taskSyncTimeoutMs;

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
        // 同步 task 调用超时（毫秒）：受服务器负载影响大，默认 10s（高于旧 5s）
        def.set("task-sync-timeout-ms", 10000);
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
        // 原生服务批准：默认要求批准（声明原生服务的插件加载时被拒，需 /yeow approve <code>）。
        // 运行时直接修改本字段即时生效（config.yml 为信任源）。
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
        this.taskSyncTimeoutMs = cfg.getLong("task-sync-timeout-ms", 10000);

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

    /** 同步 task 调用超时（毫秒），默认 10000。 */
    public long taskSyncTimeoutMs() { return taskSyncTimeoutMs; }

    /**
     * 原生服务是否需要批准（默认 true；false = 默认批准）。
     * config.yml 为信任源——运行时直接修改字段即时生效。
     */
    public boolean requireNativeApproval() {
        try {
            var file = new File(new File(dataFolder, "runtime"), "config.yml");
            return YamlConfiguration.loadConfiguration(file).getBoolean("native-service-require-approval", true);
        } catch (Exception e) {
            return true;
        }
    }
}
