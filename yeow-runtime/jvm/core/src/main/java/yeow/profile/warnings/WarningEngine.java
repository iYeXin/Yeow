package yeow.profile.warnings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

import yeow.PluginEntity;
import yeow.profile.ProfileConfig;
import yeow.profile.collector.WindowMetrics;

/**
 * 预警引擎：运行注册的检测器，按 (code, plugin) 节流，统一输出双语告警框。
 * 检测器不直接写日志--引擎是唯一输出口，便于替换（如转发到外部监控）。
 */
public final class WarningEngine {

    private static final Logger LOG = Logger.getLogger("Yeow");
    private static final String HELP_URL = "https://yexin.wiki/yeow/v1/runtime-warning";

    private static final String RED    = "\u001b[31m";
    private static final String YELLOW = "\u001b[33m";
    private static final String BLUE   = "\u001b[34m";
    private static final String RESET  = "\u001b[0m";

    private final ProfileConfig cfg;
    private final Function<String, PluginEntity> pluginLookup;
    private final List<WarningDetector> detectors = new ArrayList<>();
    private final Map<String, Long> lastEmitted = new ConcurrentHashMap<>();

    public WarningEngine(ProfileConfig cfg, Function<String, PluginEntity> pluginLookup) {
        this.cfg = cfg;
        this.pluginLookup = pluginLookup;
    }

    public WarningEngine register(WarningDetector detector) {
        detectors.add(detector);
        return this;
    }

    public List<WarningDetector> detectors() {
        return detectors;
    }

    /** 处理一个窗口：运行全部检测器，节流并输出。 */
    public void process(WindowMetrics window) {
        for (var d : detectors) {
            try {
                var warns = d.check(window);
                for (var w : warns) emit(d, w);
            } catch (Exception e) {
                LOG.warning("[Yeow] warning detector '" + d.code() + "' failed: " + e.getMessage());
            }
        }
    }

    private void emit(WarningDetector d, Warning w) {
        String key = d.code() + ":" + (w.plugin() == null ? "*" : w.plugin());
        long now = System.currentTimeMillis();
        Long prev = lastEmitted.get(key);
        long cd = Math.max(d.cooldownMs(), cfg.warnCooldownSec() * 1000L);
        if (prev != null && now - prev < cd) return;
        lastEmitted.put(key, now);
        printBoxed(w);
    }

    private void printBoxed(Warning w) {
        String color = switch (w.level()) {
            case SEVERE -> RED;
            case WARN -> YELLOW;
            default -> BLUE;
        };
        String line = color + "\u2500".repeat(56) + RESET;

        var sb = new StringBuilder();
        sb.append("\n").append(line).append("\n");
        sb.append("  [Yeow] ").append(color).append(w.code()).append(RESET)
            .append(" · ").append(w.title());
        if (w.plugin() != null && !w.plugin().isEmpty()) {
            var entity = pluginLookup.apply(w.plugin());
            String tag = entity != null && entity.isVirtual() && entity.source() != null
                ? " (worker of " + entity.source() + ")" : "";
            sb.append("  - ").append(w.plugin()).append(tag);
        }
        sb.append("\n");
        for (String l : w.lines()) {
            sb.append("  ").append(l).append("\n");
        }
        sb.append("  Help: ").append(HELP_URL).append("\n");
        sb.append(line).append("\n");
        LOG.warning(sb.toString());
    }
}
