package yeow.profile;

import yeow.PluginEntity;
import yeow.profile.collector.WindowMetrics;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * 挂起自恢复：基于心跳窗口的自动重载。
 *
 * <p>与 {@link yeow.profile.warnings.detectors.PluginHungDetector} 解耦：
 * <ul>
 *   <li>告警阈值 {@code suspend-warn-seconds}（默认30s）只告警；
 *   <li>重载阈值 {@code auto-reload-hung-seconds}（默认120s）才触发重载；
 *   <li>两者独立，均基于同一套 {@code lastResponsive} 计时；
 *   <li>默认启用（{@code auto-reload-enabled: true}），可独立关闭。
 * </ul>
 *
 * <p>触发路径：{@link Profiler#onWindow} 每秒窗口结束后同步调用
 * {@link #maybeRecover}（主线程），判定后切后台线程执行重载，
 * 避免阻塞 tick。
 */
public final class RecoveryManager {

    private static final Logger LOG = Logger.getLogger("Yeow");

    private final ProfileConfig cfg;
    private final Function<String, PluginEntity> pluginLookup;
    private volatile Consumer<String> reloadAction;
    private final ExecutorService executor;

    // 窗口维度状态（onWindow 主线程内 synchronized 保护）
    private final Map<String, Long> lastResponsive = new HashMap<>();
    private final Map<String, Long> lastReloadMs = new HashMap<>();
    private final Map<String, Integer> retryCount = new HashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    public RecoveryManager(ProfileConfig cfg, Function<String, PluginEntity> pluginLookup) {
        this.cfg = cfg;
        this.pluginLookup = pluginLookup;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "yeow-recovery");
            t.setDaemon(true);
            return t;
        });
    }

    public void setReloadAction(Consumer<String> action) {
        this.reloadAction = action;
    }

    /**
     * 每窗口结束时由 Profiler 主线程调用。
     * 判定逻辑：未响应时长 ≥ 重载阈值 → 冷却/重试/去重 → 异步重载。
     */
    public synchronized void maybeRecover(WindowMetrics w) {
        if (!cfg.autoReloadEnabled()) return;
        int reloadSec = cfg.autoReloadHungSec();
        if (reloadSec <= 0) return;
        long reloadMs = reloadSec * 1000L;
        long cooldownMs = Math.max(0, cfg.autoReloadCooldownSec()) * 1000L;
        int maxRetries = Math.max(0, cfg.autoReloadMaxRetries());

        long windowEndMs = w.startMs() + (w.tickCount() > 0 ? 1000 : 0);

        // 有响应的插件 → 刷新 lastResponsive，并重置重试计数（已恢复）
        for (String plugin : w.jsPings().keySet()) {
            lastResponsive.put(plugin, windowEndMs);
            // 恢复后清重试计数，下次挂起重新计数
            retryCount.remove(plugin);
        }
        // 已不在监控集合的插件 → 清状态（卸载等）
        lastResponsive.keySet().removeIf(p ->
            !w.pingedPlugins().contains(p) && !w.jsPings().containsKey(p));
        // 同步清理 reload 状态中已卸载的插件
        lastReloadMs.keySet().removeIf(p -> !w.pingedPlugins().contains(p) && !w.jsPings().containsKey(p));
        retryCount.keySet().removeIf(p -> !w.pingedPlugins().contains(p) && !w.jsPings().containsKey(p));
        pending.removeIf(p -> !w.pingedPlugins().contains(p) && !w.jsPings().containsKey(p));

        for (String plugin : w.pingedPlugins()) {
            // 虚拟插件（Worker）不自动重载：计算密集型阻塞属预期
            if (w.virtualPlugins().contains(plugin)) continue;
            if (w.jsPings().containsKey(plugin)) continue; // 本窗口有响应
            // dev 模式由 dev-server 热重载接管，跳过自动重载
            if ("true".equals(System.getProperty("yeow.dev"))) continue;

            var entity = pluginLookup.apply(plugin);
            if (entity == null) continue;
            if (entity.isVirtual()) continue;
            // dev 模式下热重载由 dev-server 接管，跳过自动重载以防冲突
            // isRunning 为 false 的插件不在 pingedPlugins 中，已过滤

            long last = lastResponsive.computeIfAbsent(plugin, k -> windowEndMs);
            long silentMs = windowEndMs - last;
            if (silentMs < reloadMs) continue;

            // 冷却检查
            Long lastAttempt = lastReloadMs.get(plugin);
            if (lastAttempt != null && windowEndMs - lastAttempt < cooldownMs) continue;

            int retries = retryCount.getOrDefault(plugin, 0);
            if (retries >= maxRetries) {
                // 已达最大重试，不再尝试；下次恢复后重试计数会被清零
                continue;
            }

            if (!pending.add(plugin)) continue; // 已有在途重载

            lastReloadMs.put(plugin, windowEndMs);
            retryCount.put(plugin, retries + 1);

            LOG.warning(String.format(
                "[Yeow] auto-reload: %s hung %ds (warn %ds / reload %ds, attempt %d/%d) — scheduling reload",
                plugin, silentMs / 1000, cfg.suspendWarnSec(), reloadSec, retries + 1, maxRetries));

            var action = reloadAction;
            if (action == null) {
                pending.remove(plugin);
                LOG.warning("[Yeow] auto-reload: no reload action configured for " + plugin);
                continue;
            }

            final long reloadMsFinal = reloadMs;
            executor.execute(() -> {
                try {
                    // 执行前二次校验：若在入队到执行期间插件已恢复（收到 pong），则跳过
                    boolean stillHung;
                    synchronized (RecoveryManager.this) {
                        Long lr = lastResponsive.get(plugin);
                        if (lr == null) stillHung = false;
                        else {
                            long silentNow = System.currentTimeMillis() - lr;
                            stillHung = silentNow >= reloadMsFinal;
                        }
                    }
                    if (!stillHung) {
                        LOG.info("[Yeow] auto-reload: " + plugin + " recovered before reload — skip");
                        return;
                    }
                    action.accept(plugin);
                    // 重载后是否恢复由下一窗口的 jsPings 决定；若恢复，maybeRecover 会清 retryCount
                    LOG.info("[Yeow] auto-reload: " + plugin + " reload dispatched");
                } catch (Exception e) {
                    LOG.warning("[Yeow] auto-reload failed for " + plugin + ": " + e.getMessage());
                } finally {
                    pending.remove(plugin);
                    // 注意：lastResponsive 不在此处重置，待下一窗口有 pong 时刷新
                    // 若重载后仍无响应，下一窗口仍会判定 silentMs 并在冷却后再次尝试
                }
            });
        }
    }

    public synchronized void onPluginRemoved(String plugin) {
        lastResponsive.remove(plugin);
        lastReloadMs.remove(plugin);
        retryCount.remove(plugin);
        pending.remove(plugin);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
