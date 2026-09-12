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
    private final boolean folia;
    private final Map<String, Object> values;

    public YeowConfig(File dataFolder) {
        this(dataFolder, false);
    }

    /**
     * @param folia Folia 平台：`folia:` section 提供 Folia 专用/语义不同参数
     *              （调度预算、in-flight、空闲阻塞等待）；Paper 参数保持顶层。
     *              仅影响首次生成的文件，已存在的 config.yml 保持用户值不变
     *              （默认值只补缺失键）。
     */
    public YeowConfig(File dataFolder, boolean folia) {
        this.folia = folia;
        var runtimeDir = new File(dataFolder, "runtime");
        runtimeDir.mkdirs();
        this.file = new File(runtimeDir, "config.yml");
        var defaults = defaults(folia);
        Map<String, Object> v;
        if (file.exists() && file.length() > 0) {
            try {
                Object loaded = new Yaml().load(Files.readString(file.toPath(), StandardCharsets.UTF_8));
                var expanded = loaded instanceof Map<?, ?> m ? expandFlatKeys(castMap(m)) : new LinkedHashMap<String, Object>();
                v = merge(defaults, expanded);
                // 平滑升级：已有配置缺失新字段时（合并补了默认值），写回文件；
                // 无缺失则不动文件（保留用户原有格式/注释/顺序）。
                if (!v.equals(expanded)) save(v);
            } catch (Exception e) {
                v = defaults;
            }
        } else {
            v = defaults;
            save(v); // 注意：save 须在 this.values 赋值前用参数传入，否则落盘的是 null
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

    /**
     * 调度预算。语义平台不同（各自 section，互不混淆）：
     * - Paper：`tick-budget-ms`（顶层）——每 tick 任务执行预算
     * - Folia：`folia.tick-budget-ms`——每 50ms 窗口内调度器活跃的**物理时间**上限
     */
    public long tickBudgetNs() {
        int ms = folia ? getInt("folia.tick-budget-ms", 20) : getInt("tick-budget-ms", 20);
        return ms * 1_000_000L;
    }
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
    public boolean profileAutoReloadEnabled() { return getBool("profile.auto-reload-enabled", true); }
    public int profileAutoReloadHungSeconds() { return getInt("profile.auto-reload-hung-seconds", 120); }
    public int profileAutoReloadCooldownSeconds() { return getInt("profile.auto-reload-cooldown-seconds", 300); }
    public int profileAutoReloadMaxRetries() { return getInt("profile.auto-reload-max-retries", 3); }

    /** 同步 task 调用超时（毫秒），默认 10000。 */
    public long taskSyncTimeoutMs() { return getInt("task-sync-timeout-ms", 10000); }

    /** util 通道单次输入上限（原始字节，默认 256 MiB）。 */
    public int utilMaxInputBytes() { return getInt("util.max-input-bytes", 256 * 1024 * 1024); }
    /** util 通道 gzip 解压输出上限（原始字节，默认 256 MiB）。 */
    public int utilMaxOutputBytes() { return getInt("util.max-output-bytes", 256 * 1024 * 1024); }

    /** 插件包内存缓存（assets 通道 / 原生二进制解压走内存，默认启用）。 */
    public boolean assetsCacheEnabled() { return getBool("assets.cache-enabled", true); }

    /**
     * 插件包内存缓存阈值（字节，默认 30 MiB）：包大小超过此值时不缓存到内存，
     * 回退 ZipFile 直读。`<= 0` 表示不限制。
     */
    public long assetsCacheMaxBytes() { return getInt("assets.cache-max-bytes", 30 * 1024 * 1024); }

    /**
     * 是否允许加载不可信原生服务（默认 true）。
     * true = 声明原生服务的插件正常加载，加载时打印醒目的不可信警告；
     * false = 申请了 `service:registerNative` 权限的插件拒绝加载。
     * 二进制 SHA-256 声明校验不受此开关影响（始终在注册时校验）。
     * 在线安全性校验（比对官方维护的安全清单）能力已在规划中，届时命中清单的
     * 二进制可免警告加载。
     */

    /** Folia：in-flight 任务上限（同时投递未完成数），默认 100（folia section）。 */
    public int maxInflight() { return getInt("folia.max-inflight", 100); }

    /**
     * Folia：调度循环空闲**阻塞等待**上限（微秒，folia section，默认 2000）。
     * 队列空时区域线程 park 等待新任务（wake 提前唤醒），不再忙等自旋——
     * region 线程满核饱和下 OS 唤醒延迟本身即 100µs~ms 级，忙等的响应优势消失，
     * park 释放 CPU 且区域 tick 停顿语义与自旋一致。替代原 scheduler-spin-us。
     */
    public int schedulerIdleWaitUs() { return getInt("folia.scheduler-idle-wait-us", 2000); }

    /**
     * Folia：热点迁移阈值（连续非本区域任务数，folia section，默认 2）。
     * 达到该数即让出驻留标记等待抢占。**调高**：多人/多插件等热点抖动场景更稳定
     * （不反复让出-抢占）；但热点迁移延迟增大（需要更多连续外来任务才让出）。
     * 不建议过高。
     */
    public int migrationThreshold() { return getInt("folia.migration-threshold", 2); }

    public boolean nativeServiceAllowUntrusted() { return getBool("native-service-allow-untrusted", true); }

    /** service 请求超时（毫秒，默认 30000）；超时后挂起请求被拒绝。 */
    public long serviceRequestTimeoutMs() { return getInt("service-request-timeout-ms", 30000); }

    // ── 默认值 / 合并 / 落盘 ────────────────────────────────────────

    private static Map<String, Object> defaults(boolean folia) {
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
        profile.put("auto-reload-enabled", true);
        profile.put("auto-reload-hung-seconds", 120);
        profile.put("auto-reload-cooldown-seconds", 300);
        profile.put("auto-reload-max-retries", 3);
        profile.put("backlog-threshold", 35);
        profile.put("backlog-window-ticks", 40);
        profile.put("scheduler-saturation-pct", 80);
        profile.put("scaler", scaler);

        var m = new LinkedHashMap<String, Object>();
        m.put("tick-budget-ms", 20);               // Paper：每 tick 任务预算（Folia 语义见 folia section）
        m.put("priority-ratios", List.of(0.5, 0.3, 0.2));
        m.put("auto-demote", true);
        m.put("demote-threshold", 200);
        m.put("idle-spin-us", 100);                // Paper 专用（Folia 用 folia.scheduler-idle-wait-us）
        m.put("concurrent-events", true);
        m.put("task-sync-timeout-ms", 10000);
        var util = new LinkedHashMap<String, Object>();
        util.put("max-input-bytes", 256 * 1024 * 1024);   // util 通道单次输入上限（原始字节）
        util.put("max-output-bytes", 256 * 1024 * 1024);  // gzip 解压输出上限（防压缩炸弹）
        m.put("util", util);
        m.put("profile", profile);
        var assets = new LinkedHashMap<String, Object>();
        assets.put("cache-enabled", true);               // 插件包内存缓存（assets 通道/原生解压走内存）
        assets.put("cache-max-bytes", 30 * 1024 * 1024); // 超过此大小不再缓存到内存（回退 ZipFile 直读）；<=0 = 不限制
        m.put("assets", assets);
        m.put("native-service-allow-untrusted", true);   // 允许加载不可信原生服务（默认允许并警告；false = 申请 registerNative 权限的插件拒绝加载）
        m.put("service-request-timeout-ms", 30000);      // service 请求超时（超时后挂起请求被拒绝）

        // ── Folia 专用 section（仅 Folia 生成）：语义与 Paper 不同或仅 Folia 使用的参数 ──
        if (folia) {
            var fol = new LinkedHashMap<String, Object>();
            fol.put("tick-budget-ms", 20);             // 语义不同：每 50ms 窗口内调度器活跃**物理时间**上限
            fol.put("max-inflight", 100);              // 同时投递未完成任务上限（区域并行度）
            fol.put("scheduler-idle-wait-us", 2000);   // 调度循环空闲**阻塞等待**上限（替代原 scheduler-spin-us）：
                                                       //   队列空时区域线程 park 等待新任务（wake 提前唤醒，不烧 CPU）；
                                                       //   须覆盖 JS 同步往返间隙（region 线程满核饱和下 100µs~ms 级）
            fol.put("migration-threshold", 2);           // 热点迁移阈值（连续非本区域任务数）：调高更稳（多人/多插件），
                                                         //   但热点迁移延迟增大，不建议过高
            m.put("folia", fol);
        }
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

    private void save(Map<String, Object> data) {
        var opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        try {
            Files.writeString(file.toPath(), new Yaml(opts).dump(data), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
