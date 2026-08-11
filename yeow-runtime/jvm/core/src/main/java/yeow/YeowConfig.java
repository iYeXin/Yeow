package yeow;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时配置（plugins/Yeow/runtime/config.yml）。
 * 纯 JDK + snakeyaml 实现（平台无关）：默认值合并 + 首次运行落盘；
 * 兼容旧版 Bukkit YamlConfiguration 的扁平点号键（profile.enabled 等）。
 */
public class YeowConfig {
    private final File file;
    private final Map<String, Object> values;

    public YeowConfig(File dataFolder) {
        var runtimeDir = new File(dataFolder, "runtime");
        runtimeDir.mkdirs();
        this.file = new File(runtimeDir, "config.yml");
        var defaults = defaults();
        Map<String, Object> v;
        if (file.exists() && file.length() > 0) {
            try {
                Object loaded = new Yaml().load(Files.readString(file.toPath(), StandardCharsets.UTF_8));
                v = loaded instanceof Map<?, ?> m ? expandFlatKeys(castMap(m)) : new LinkedHashMap<String, Object>();
                v = merge(defaults, v);
            } catch (Exception e) {
                v = defaults;
            }
        } else {
            v = defaults;
            save();
        }
        this.values = v;
    }

    // ── 配置读取（点号路径） ────────────────────────────────────────

    private Object get(String path) {
        Object cur = values;
        for (var key : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(key);
        }
        return cur;
    }

    private int getInt(String path, int def) { var v = get(path); return v instanceof Number n ? n.intValue() : def; }
    private double getDouble(String path, double def) { var v = get(path); return v instanceof Number n ? n.doubleValue() : def; }
    private boolean getBool(String path, boolean def) { var v = get(path); return v instanceof Boolean b ? b : def; }

    public long tickBudgetNs() { return getInt("tick-budget-ms", 20) * 1_000_000L; }
    public double[] priorityRatios() {
        var v = get("priority-ratios");
        if (v instanceof List<?> l) {
            var nums = l.stream().filter(x -> x instanceof Number).map(x -> (Number) x).toList();
            if (nums.size() == 3) return new double[]{ nums.get(0).doubleValue(), nums.get(1).doubleValue(), nums.get(2).doubleValue() };
        }
        return new double[]{ 0.5, 0.3, 0.2 };
    }
    public boolean autoDemote() { return getBool("auto-demote", true); }
    public int demoteThreshold() { return getInt("demote-threshold", 200); }
    public int idleSpinUs() { return getInt("idle-spin-us", 100); }
    public boolean concurrentEvents() { return getBool("concurrent-events", true); }
    public boolean profileEnabled() { return getBool("profile.enabled", true); }
    public boolean profileWarningsEnabled() { return getBool("profile.warnings-enabled", true); }
    public int profileLatencyWarnThresholdMs() { return getInt("profile.latency-warn-threshold-ms", 200); }
    public double profileLatencyDegradedMultiplier() { return getDouble("profile.latency-degraded-multiplier", 5.0); }
    public int profileCallbackTimeoutEventMs() { return getInt("profile.callback-timeout-event-ms", 5000); }
    public int profileCallbackTimeoutTabCompleteMs() { return getInt("profile.callback-timeout-tabcomplete-ms", 1000); }
    public int profileEventSlowThresholdMs() { return getInt("profile.event-slow-threshold-ms", 2000); }
    public int profileTabSlowThresholdMs() { return getInt("profile.tab-slow-threshold-ms", 500); }
    public int profileWarnCooldownSeconds() { return getInt("profile.warn-cooldown-seconds", 60); }
    public int profileSuspendWarnSeconds() { return getInt("profile.suspend-warn-seconds", 30); }
    public int profileBacklogThreshold() { return getInt("profile.backlog-threshold", 35); }
    public int profileBacklogWindowTicks() { return getInt("profile.backlog-window-ticks", 40); }
    public int profileSaturationPct() { return getInt("profile.scheduler-saturation-pct", 80); }
    public boolean profileScalerEnabled() { return getBool("profile.scaler.enabled", true); }
    public double profileScalerFactor() { return getDouble("profile.scaler.expansion-factor", 1.3); }
    public double profileScalerMax() { return getDouble("profile.scaler.max-multiplier", 3.0); }

    /** 同步 task 调用超时（毫秒），默认 10000。 */
    public long taskSyncTimeoutMs() { return getInt("task-sync-timeout-ms", 10000); }

    /**
     * 原生服务是否需要批准（默认 true；false = 默认批准）。
     * config.yml 为信任源——每次调用重新读取文件，运行时直接修改字段即时生效。
     */
    public boolean requireNativeApproval() {
        try {
            Object loaded = new Yaml().load(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            var v = loaded instanceof Map<?, ?> m ? m.get("native-service-require-approval") : null;
            return v instanceof Boolean b ? b : true;
        } catch (Exception e) {
            return true;
        }
    }

    // ── 默认值 / 合并 / 落盘 ────────────────────────────────────────

    private static Map<String, Object> defaults() {
        var scaler = new LinkedHashMap<String, Object>();
        scaler.put("enabled", true);
        scaler.put("expansion-factor", 1.3);
        scaler.put("max-multiplier", 3.0);

        var profile = new LinkedHashMap<String, Object>();
        profile.put("enabled", false);
        profile.put("warnings-enabled", true);
        profile.put("latency-warn-threshold-ms", 200);
        profile.put("latency-degraded-multiplier", 5.0);
        profile.put("callback-timeout-event-ms", 5000);
        profile.put("callback-timeout-tabcomplete-ms", 1000);
        profile.put("event-slow-threshold-ms", 2000);
        profile.put("tab-slow-threshold-ms", 500);
        profile.put("warn-cooldown-seconds", 1800);
        profile.put("suspend-warn-seconds", 30);
        profile.put("backlog-threshold", 35);
        profile.put("backlog-window-ticks", 40);
        profile.put("scheduler-saturation-pct", 80);
        profile.put("scaler", scaler);

        var m = new LinkedHashMap<String, Object>();
        m.put("tick-budget-ms", 20);
        m.put("priority-ratios", List.of(0.5, 0.3, 0.2));
        m.put("auto-demote", true);
        m.put("demote-threshold", 200);
        m.put("idle-spin-us", 100);
        m.put("concurrent-events", true);
        m.put("task-sync-timeout-ms", 10000);
        m.put("profile", profile);
        m.put("native-service-require-approval", true);
        return m;
    }

    /** 深层合并：loaded 覆盖 defaults，缺失键保留默认值。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(Map<String, Object> defs, Map<String, Object> loaded) {
        var out = new LinkedHashMap<String, Object>(defs);
        for (var e : loaded.entrySet()) {
            var d = defs.get(e.getKey());
            if (d instanceof Map<?, ?> dm && e.getValue() instanceof Map<?, ?> lm) {
                out.put(e.getKey(), merge(castMap(dm), castMap(lm)));
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static Map<String, Object> castMap(Map<?, ?> m) {
        var out = new LinkedHashMap<String, Object>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /** 兼容旧版扁平点号键：`profile.enabled: true` → `profile: { enabled: true }`。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> expandFlatKeys(Map<String, Object> m) {
        var out = new LinkedHashMap<String, Object>();
        for (var e : m.entrySet()) {
            var key = e.getKey();
            if (key.contains(".") && !(e.getValue() instanceof Map)) {
                var parts = key.split("\\.");
                Map<String, Object> cur = out;
                for (int i = 0; i < parts.length - 1; i++) {
                    var next = cur.get(parts[i]);
                    if (!(next instanceof Map)) {
                        next = new LinkedHashMap<String, Object>();
                        cur.put(parts[i], next);
                    }
                    cur = (Map<String, Object>) next;
                }
                cur.put(parts[parts.length - 1], e.getValue());
            } else {
                out.put(key, e.getValue());
            }
        }
        return out;
    }

    private void save() {
        var opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        try {
            Files.writeString(file.toPath(), new Yaml(opts).dump(values), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
