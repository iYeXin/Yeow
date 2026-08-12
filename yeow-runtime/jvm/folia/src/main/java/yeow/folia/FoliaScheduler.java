package yeow.folia;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import yeow.TaskScheduler;
import yeow.YeowConfig;
import yeow.profile.instrumentation.ProfileSink;
import yeow.profile.instrumentation.TaskMetric;
import yeow.profile.instrumentation.TaskPriority;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Folia 平台调度器（实验性）：**非阻塞调度器 + 区域驻留 + 迁移**。
 *
 * 设计要点（详见 Yeow-Docs/zh/advanced/folia.md）：
 * - **无独立线程**：调度循环（dispatch cycle）作为任务驻留在某 region 线程上运行，
 *   队列空/预算尽/in-flight 满时退出，由新提交（submit）/完成回调（wake）唤醒
 * - **非阻塞投递**：取到任务立即投递，**绝不等待结果**——目标在驻留区域就地执行
 *   （微秒级），在其他 region 经 Folia 调度器异步执行（区域并行，受 in-flight 上限约束）
 * - **预算**：每 50ms 窗口内调度器活跃的**物理时间**（now - windowStart）超过预算
 *   （复用 tick-budget-ms）即停止投递，窗口滚动后 delayed 恢复。不计各任务耗时累加，
 *   对并行执行友好
 * - **迁移**：连续 migration-threshold（默认 2）个非本区域任务 → **让出驻留标记**（schedRegion=null，不主动让位，
 *   等待下一个任务抢占）——A/B 路径执行前都尝试抢占标记（热点跟随最近任务目标）；
 *   实体下线/世界卸载的过期驻留权自动清理
 * - **事件/补全模式**：计数非零时 cycle 退出；事件线程作为临时调度器（drainForPlugins：
 *   不迁移、取事件插件任务 + 当前区域归属的其他插件任务，对齐 Paper 事件期间不冻结他人）
 * - **优先级**：取件唯一规则 = 始终优先更高优先级（HIGH → NORMAL → LOW，各池 FIFO）；
 *   LOW 不被饿死由**提交时自动降级**保证（Paper 同款滑动窗口：高频 NORMAL → LOW）
 * - **投递兜底**：非阻塞投递带 5s 超时回收（region 调度器无 retired 回调，防 in-flight
 *   泄漏导致调度器停摆）
 *
 * 调度器**对任务类型零认知**，每个任务类型只提供三件事（FoliaTasks，家族共享实现）：
 * <ul>
 *   <li>{@code ownedHere(params)} — 当前线程归属（直接基于原始参数，不经 key 反推）</li>
 *   <li>{@code getScheduler(params)} — 目标调度句柄（marker 驻留标记 + run 惰性调度闭包）</li>
 *   <li>{@code execute(params)} — 任务本体</li>
 * </ul>
 * 处理流程（{@link #processTask}）：ownedHere → true：就地执行；false：经 getScheduler().run
 * 投递（B 路径，目标线程执行）。**连续两次非本区域任务 → 让出驻留标记**（不主动让位，
 * 等待下一个任务抢占）；B 路径/A 路径执行前都尝试抢占标记（热点跟随最近任务目标）。
 */
public class FoliaScheduler implements TaskScheduler {
    private static final Logger LOG = Logger.getLogger("Yeow");
    static final Gson gson = new Gson();

    // ── 常量 ────────────────────────────────────────────────────────
    /** 预算窗口（50ms）：folia.tick-budget-ms 为该窗口内调度器活跃的物理时间上限。 */
    private static final long BUDGET_WINDOW_NS = 50_000_000L;
    /** 事件/补全自旋每轮最多执行的任务数：事件响应延迟敏感，其他插件积压不得拖慢 latch 检查。 */
    private static final int SPIN_DRAIN_BUDGET = 64;
    /** 投递超时兜底：100 ticks = 5s（低于 JS 侧 task-sync-timeout 10s，兜底错误先于调用方超时浮出）。 */
    private static final long DISPATCH_TIMEOUT_TICKS = 100;
    private static final long LOW_QUEUE_WARN_INTERVAL_MS = 60_000;
    private static final long LOW_QUEUE_WARN_THRESHOLD = 100_000;
    /** 调试日志开关（-Dyeow.debug.sched=true）：cycle 启停/迁移/锚定序列，用于定位调度退化。 */
    private static final boolean DBG = "true".equals(System.getProperty("yeow.debug.sched"));

    private final ConcurrentLinkedQueue<PendingTask> highPool = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingTask> normalPool = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingTask> lowPool = new ConcurrentLinkedQueue<>();

    private final YeowConfig config;
    private final FoliaRuntime runtime;

    /** 调度循环互斥 + 状态保护（防双 cycle、迁移原子性、唤醒竞态）。 */
    private final Object cycleLock = new Object();
    private volatile boolean cycleRunning = false;
    /** 调度器驻留区域目标 key（"uuid:x"/"world:y"/null=无主可争抢）。 */
    private volatile String schedRegion = null;
    /** 已投递未完成的任务数（就地执行不占用）。 */
    private final java.util.concurrent.atomic.AtomicInteger inflight = new java.util.concurrent.atomic.AtomicInteger();
    /** 预算窗口起点（物理时间）。 */
    private volatile long windowStart = System.nanoTime();
    private final int maxInflight;
    /** 空闲阻塞等待上限（ns，folia.scheduler-idle-wait-us；队列空时 park 而非忙等）。 */
    private final long idleWaitNs;
    private final long budgetNs;

    /** 事件/补全模式计数：非零时通用调度器暂停，事件线程作为临时调度器。 */
    private final java.util.concurrent.atomic.AtomicInteger eventModeCount = new java.util.concurrent.atomic.AtomicInteger();
    /** 队列取任务互斥锁：事件线程按插件扫描 与 通用 poll / purge 互斥。 */
    private final Object queueLock = new Object();
    /** NORMAL 高频任务自动降级（Paper 同款滑动窗口，提交时判定）。 */
    private final TaskFrequencyTracker freqTracker;
    /** 热点迁移阈值（连续非本区域任务数，folia.migration-threshold，默认 2）。 */
    private final int migrationThreshold;

    private volatile ProfileSink sink;
    private long lastLowQueueWarnMs;

    private volatile boolean running = false;

    record PendingTask(String taskType, JsonObject params, CompletableFuture<String> future, Consumer<Object> callback, Priority priority, String pluginName) {
        boolean isAsync() { return callback != null; }
    }

    public FoliaScheduler(YeowConfig config, FoliaRuntime runtime) {
        this.config = config;
        this.runtime = runtime;
        this.maxInflight = config.maxInflight();
        this.idleWaitNs = config.schedulerIdleWaitUs() * 1000L;
        this.budgetNs = config.tickBudgetNs();
        this.freqTracker = new TaskFrequencyTracker(config.demoteThreshold());
        this.migrationThreshold = Math.max(1, config.migrationThreshold());
    }

    public void setProfileSink(ProfileSink s) { this.sink = s; }

    /** 宿主运行时引用（FoliaTasks 的投递闭包需要 plugin 参数）。 */
    FoliaRuntime runtime() { return runtime; }

    @Override public void start() {
        running = true;
        // 无独立线程：首个任务提交时经 wake 启动调度循环
    }

    @Override public void shutdown() {
        running = false;
        synchronized (cycleLock) {
            cycleRunning = false;
            schedRegion = null;
            cycleLock.notifyAll();
        }
    }

    // ── 提交 ───────────────────────────────────────────────────────

    @Override
    public void submitGameSync(String taskType, JsonObject params, CompletableFuture<String> future, Priority priority, String pluginName) {
        submit(taskType, params, future, null, priority, pluginName);
    }

    @Override
    public void submitGameAsync(String taskType, JsonObject params, Consumer<Object> callback, Priority priority, String pluginName) {
        submit(taskType, params, null, callback, priority, pluginName);
    }

    /** 入队 + 唤醒（驻留标记由 FoliaTasks.getScheduler 计算——纯字符串，任意线程可算）。 */
    private void submit(String taskType, JsonObject params, CompletableFuture<String> future, Consumer<Object> callback, Priority priority, String pluginName) {
        var effective = effectivePriority(priority, pluginName, taskType);
        pool(effective).add(new PendingTask(taskType, params, future, callback, effective, pluginName));
        wake(FoliaTasks.getScheduler(taskType, params).marker());
    }

    /**
     * 提交时自动降级（Paper 同款）：NORMAL 任务调用频率超过 demote-threshold（默认 200 次/秒）
     * 即降为 LOW——高频批量任务不挤占交互，频率回落后自然恢复。仅影响提交行为，入队后不变。
     */
    private Priority effectivePriority(Priority priority, String pluginName, String taskType) {
        if (!config.autoDemote() || priority != Priority.NORMAL || pluginName == null) return priority;
        if (freqTracker.shouldDemote(pluginName, taskType)) return Priority.LOW;
        return priority;
    }

    private ConcurrentLinkedQueue<PendingTask> pool(Priority p) {
        return switch (p) { case HIGH -> highPool; case LOW -> lowPool; default -> normalPool; };
    }

    // ── 唤醒与调度循环 ─────────────────────────────────────────────

    /**
     * 唤醒调度循环（submit / 任务完成回调 / 事件模式结束调用）。
     * **只有非全局目标能抢占驻留权**（schedRegion 无主时）——全局任务只触发全局瞬态
     * （runCycleOn(null)），避免 GLOBAL 污染驻留导致所有任务走投递。
     */
    private void wake(String target) {
        String toRun;
        synchronized (cycleLock) {
            cycleLock.notifyAll(); // 唤醒空闲 park 的调度循环（所有提前返回路径都要唤醒）
            if (!running || cycleRunning || eventModeCount.get() > 0) return;
            if (schedRegion == null && target != null && !TargetKey.isGlobal(target)) {
                schedRegion = target; // 争抢驻留权（区域任务）
                if (DBG) LOG.info("[sched] claim region=" + target);
            }
            cycleRunning = true;
            toRun = schedRegion; // null → 全局瞬态（仅剩全局任务时）
        }
        runCycleOn(toRun, 0);
    }

    /**
     * 投递调度循环到驻留区域线程（代表对象解析在全局 region 线程进行；
     * 解析失败/无驻留 → 全局瞬态）。delayMs>0 为预算尽后的 delayed 重试。
     * **注意：Folia 的 runDelayed 参数是 tick 数（1 tick = 50ms），必须毫秒转 tick**——
     * 直接把 delayMs 当 tick 传入会把 30ms 变成 30 ticks = 1.5s 停顿（实测故障）。
     * 解析失败时**清掉过期驻留权**（实体已下线/世界已卸载）——否则每次 cycle 启动
     * 都白付一次失败解析 + 全局瞬态，且新热点无法抢占（wake 只在 schedRegion==null 时抢占）。
     */
    private void runCycleOn(String region, long delayMs) {
        Runnable global = () -> {
            if (delayMs > 0) Bukkit.getGlobalRegionScheduler().runDelayed(runtime, t -> dispatchCycle(), msToTicks(delayMs));
            else Bukkit.getGlobalRegionScheduler().run(runtime, t -> dispatchCycle());
        };
        if (TargetKey.isGlobal(region)) { global.run(); return; }
        Bukkit.getGlobalRegionScheduler().run(runtime, t -> {
            try {
                if (region.startsWith(TargetKey.UUID_PREFIX)) {
                    var entity = TargetKey.resolveEntity(region);
                    if (entity != null) {
                        if (delayMs > 0) {
                            entity.getScheduler().runDelayed(runtime, t2 -> dispatchCycle(),
                                () -> { /* retired：回退全局瞬态 */ global.run(); }, msToTicks(delayMs));
                        } else {
                            entity.getScheduler().run(runtime, t2 -> dispatchCycle(),
                                () -> global.run());
                        }
                        return;
                    }
                } else if (region.startsWith(TargetKey.WORLD_PREFIX)) {
                    var w = TargetKey.resolveWorld(region);
                    if (w != null) {
                        var c = TargetKey.chunkCoords(region);
                        if (delayMs > 0) Bukkit.getRegionScheduler().runDelayed(runtime, w, c[0], c[1], t2 -> dispatchCycle(), msToTicks(delayMs));
                        else Bukkit.getRegionScheduler().run(runtime, w, c[0], c[1], t2 -> dispatchCycle());
                        return;
                    }
                }
            } catch (Exception ignored) {}
            // 解析失败：实体/世界已不存在——清除过期驻留权（仅当未被并发抢占时才清）
            synchronized (cycleLock) {
                if (region.equals(schedRegion)) schedRegion = null;
            }
            global.run();
        });
    }

    /** 毫秒 → tick（Folia runDelayed 单位为 tick；1 tick = 50ms，向上取整至少 1）。 */
    private static long msToTicks(long ms) {
        return Math.max(1, (ms + 49) / 50);
    }

    /**
     * 调度循环（驻留区域线程上运行）：**队列非空时持续处理**，
     * **队列空后阻塞等待 idleWaitNs**（folia.scheduler-idle-wait-us，park 而非忙等）——
     * 新任务经 wake() notifyAll 提前唤醒，仍空才退出，由 wake / delayed 恢复。
     * 预算（folia.tick-budget-ms / 50ms 窗口，物理时间）防止常驻饿死区域。
     * 驻留让出：**连续 migration-threshold 个（默认 2）非本区域任务（ownedHere=false）
     * → 让出驻留标记**（不主动让位，等待下一个任务的抢占）；任务处理统一走 {@link #processTask}。
     */
    private void dispatchCycle() {
        if (DBG) LOG.info("[sched] cycle start thread=" + Thread.currentThread().getName()
            + " schedRegion=" + schedRegion);
        try {
            long idleSince = 0; // 空闲等待起点（0 = 队列非空状态）
            int foreignCount = 0;
            while (running) {
                if (!running || eventModeCount.get() > 0) return;
                if (inflight.get() >= maxInflight) return;
                long now = System.nanoTime();
                if (now - windowStart >= BUDGET_WINDOW_NS) windowStart = now; // 滚动窗口
                if (now - windowStart >= budgetNs) return;                     // 预算尽（物理时间）
                long iterStart = now;
                var t = pollAny();
                if (t == null) {
                    if (idleSince == 0) idleSince = now;
                    else if (now - idleSince >= idleWaitNs) return;            // 空闲累计超时 → 退出
                    idlePark(now + (idleWaitNs - (now - idleSince)), windowStart + budgetNs);
                    continue;
                }
                idleSince = 0; // 有任务：重置空闲计时，持续处理
                var o = processTask(t);
                if (DBG) {
                    var iterMs = (System.nanoTime() - iterStart) / 1_000_000;
                    if (iterMs > 200) LOG.info("[sched] SLOW iter=" + iterMs + "ms task=" + t.taskType()
                        + " local=" + o.local() + " marker=" + o.marker());
                }
                if (o.local()) {
                    foreignCount = 0;
                } else if (!TargetKey.isGlobal(o.marker())) {
                    foreignCount++;
                    if (foreignCount >= migrationThreshold) {
                        // 让出驻留标记：本区域不再是热点——等待下一个任务抢占
                        synchronized (cycleLock) {
                            schedRegion = null;
                        }
                        if (DBG) LOG.info("[sched] yield residency");
                        foreignCount = 0;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warning("[Yeow] scheduler cycle error: " + e.getMessage());
        } finally {
            finishCycle();
        }
    }

    /**
     * 循环退出收尾（cycleLock 内）：释放驻留权（等 wake 恢复），或预算尽时保持占位
     * 并 delayed 重启（窗口滚动后，避免 submit 争抢导致双 cycle）。
     * 竞态兜底：空闲退出窗口内新任务入队且预算未耗尽 → **立即重启**，防止丢失唤醒
     * （否则任务滞留直到 10s 同步超时）。
     */
    private void finishCycle() {
        boolean delayed = false;
        boolean immediateRestart = false;
        long delay = 0;
        synchronized (cycleLock) {
            if (!running || eventModeCount.get() > 0 || inflight.get() >= maxInflight || queueEmpty()) {
                // 事件模式 / in-flight 满（等 complete 唤醒）/ 队列空（等 submit 唤醒）
                cycleRunning = false;
                if (DBG) LOG.info("[sched] cycle exit: release (queueEmpty=" + queueEmpty() + ")");
                return;
            }
            // 到这里：队列非空且预算未耗尽（空闲退出窗口内新任务入队）→ 立即重启；
            // 或预算尽 → delayed 重启（窗口滚动后，保持占位防双 cycle）。
            long now = System.nanoTime();
            if (now - windowStart >= BUDGET_WINDOW_NS) windowStart = now;
            if (now - windowStart >= budgetNs) {
                delayed = true; // 保持 cycleRunning=true，窗口滚动后重启（阻止他人争抢，避免双 cycle）
                delay = Math.max(1, BUDGET_WINDOW_NS / 1_000_000L - (now - windowStart) / 1_000_000L);
            } else {
                immediateRestart = true; // 保持 cycleRunning=true，立即重启（见类注释竞态）
            }
            if (DBG) LOG.info("[sched] cycle exit: delayed=" + delayed + " immediate=" + immediateRestart
                + " region=" + schedRegion);
        }
        if (delayed) runCycleOn(schedRegion, delay);
        else if (immediateRestart) runCycleOn(schedRegion, 0);
    }

    /**
     * 空闲等待：**阻塞（park）而非忙等**。region 线程满核饱和下 OS 唤醒延迟本身即
     * 100µs~ms 级——忙等的微秒级响应优势消失，park 释放 CPU，且区域 tick 停顿
     * 语义与自旋一致（cycle 任务占据区域线程期间 tick 本就暂停）。
     *
     * 至多等待 min(空闲剩余额度 idleWaitNs, 预算剩余)；新任务经 wake() notifyAll
     * 提前唤醒（submit / 任务完成 / 事件模式结束均触发）。注意 OS 定时器粒度
     * （Windows 上 ~1-15.6ms）可能使实际阻塞略超设定值——仍空即退出，仅周期尾一次。
     */
    private void idlePark(long parkEnd, long budgetDeadline) {
        if (parkEnd > budgetDeadline) parkEnd = budgetDeadline;
        synchronized (cycleLock) {
            long remaining = parkEnd - System.nanoTime();
            if (remaining <= 0 || !running) return;
            long ms = remaining / 1_000_000;
            int ns = (int) (remaining % 1_000_000);
            try {
                cycleLock.wait(ms, ns);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── 任务处理（调度器对任务类型零认知，只消费三件事） ─────────────

    /** 任务处理结果：是否就地执行 + 驻留标记（foreign 让出统计用）。 */
    private record TaskOutcome(boolean local, String marker) {}

    /**
     * 统一任务处理（调度循环与事件自旋 pump 共用）：
     * <ol>
     *   <li>{@code ownedHere}（FoliaTasks，按任务类型基于原始参数判定）→ true：A 路径
     *       **就地执行**（不占 in-flight）；false：B 路径 {@link #dispatch} 投递</li>
     *   <li>两条路径执行前都**尝试抢占驻留标记**（标记为空才抢占——让出后等待被抢占的语义）</li>
     * </ol>
     */
    private TaskOutcome processTask(PendingTask t) {
        var taskType = t.taskType();
        var params = t.params();
        var dt = FoliaTasks.getScheduler(taskType, params);
        var startNs = System.nanoTime();
        // A/B 路径执行前都尝试抢占（标记为空才抢占——让出后等待被抢占的语义）。
        // B 路径抢占尤为重要：纯外来流量下让出后若无抢占，cycle 无法恢复驻留，
        // 预算尽重启会退化为全局瞬态（全投递）直到出现空闲间隙。
        claimResidency(dt.marker());
        if (FoliaTasks.ownedHere(taskType, params)) {
            var execStart = System.nanoTime();
            var result = executeOrErr(taskType, params);
            if (DBG) {
                var execMs = (System.nanoTime() - execStart) / 1_000_000;
                if (execMs > 200) LOG.info("[sched] SLOW local exec=" + execMs + "ms task=" + taskType + " marker=" + dt.marker());
            }
            onTaskResult(t, startNs, result);
            return new TaskOutcome(true, dt.marker());
        }
        dispatch(t, dt, startNs); // B 路径
        return new TaskOutcome(false, dt.marker());
    }

    /**
     * B 路径：非阻塞投递（in-flight++），经任务类型的 {@code getScheduler().run} 调度到
     * 目标线程执行。**超时兜底**：{@link #DISPATCH_TIMEOUT_TICKS} 内未完成则补 err +
     * 回收 in-flight——region 调度器没有 retired 回调（entity 才有），世界卸载/区域停摆时
     * 任务可能永不执行；无兜底则 in-flight 永久泄漏 → 达 max-inflight 后调度器停摆，
     * 且队列继续入队 → 内存无界增长。兜底只触发一次（done 原子开关）。
     */
    private void dispatch(PendingTask t, FoliaTasks.DispatchTarget dt, long startNs) {
        inflight.incrementAndGet();
        var done = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.function.Consumer<Object> finish = result -> {
            if (!done.compareAndSet(false, true)) return; // 兜底与正常完成互斥，只生效一次
            inflight.decrementAndGet();
            try { onTaskResult(t, startNs, result); } catch (Exception ignored) {}
            wake(dt.marker());
        };
        Bukkit.getGlobalRegionScheduler().runDelayed(runtime, tt ->
            finish.accept(Map.of("err", "task dispatch timed out: " + t.taskType() + " (target " + dt.marker() + ")")),
            DISPATCH_TIMEOUT_TICKS);
        dt.run().accept(finish); // 闭包内部：解析目标 → 目标线程调度 → finish(执行结果) / finish(err)
    }

    /** 任务结果收尾：Profile 插桩 + 完成 future/回调（就地与投递两条路径共用）。 */
    private void onTaskResult(PendingTask t, long startNs, Object result) {
        var elapsed = System.nanoTime() - startNs;
        ProfileSink s = sink;
        if (s != null && s.taskSampled()) {
            s.onTask(new TaskMetric(t.pluginName(), t.taskType(),
                switch (t.priority()) {
                    case HIGH -> TaskPriority.HIGH;
                    case NORMAL -> TaskPriority.NORMAL;
                    case LOW -> TaskPriority.LOW;
                },
                elapsed));
        }
        completeTask(t, result);
    }

    /**
     * 尝试抢占驻留标记（A/B 路径执行前调用）：标记为空（让出后）才抢占——
     * 热点跟随最近任务的目标；GLOBAL 不参与。抢占后 cycle 重启时经
     * {@link #runCycleOn} 锚定到新热点。
     */
    private void claimResidency(String marker) {
        if (marker == null || TargetKey.isGlobal(marker)) return;
        synchronized (cycleLock) {
            if (schedRegion == null) {
                schedRegion = marker;
                if (DBG) LOG.info("[sched] preempt claim region=" + marker);
            }
        }
    }

    private static void completeTask(PendingTask t, Object result) {
        if (t.isAsync()) t.callback().accept(result);
        else t.future().complete(gson.toJson(result));
    }

    private static Object executeOrErr(String taskType, JsonObject params) {
        try {
            return FoliaTasks.execute(taskType, params);
        } catch (Exception e) {
            return errObject(e, taskType);
        }
    }

    static Map<String, Object> errObject(Exception e, String taskType) {
        var err = new LinkedHashMap<String, Object>();
        err.put("err", e.getMessage() != null ? e.getMessage() : e.toString());
        err.put("type", e.getClass().getSimpleName());
        err.put("task", taskType);
        // 完整 Java 堆栈（JS 侧 task.ts post() reject 时附到错误上：'--- runtime executer error ---'）
        var sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        err.put("stack", sw.toString());
        return err;
    }

    // ── 队列互斥 ───────────────────────────────────────────────────

    /**
     * 通用取任务（调度循环）。**优先级机制的唯一实现点**：始终优先取更高优先级
     * （HIGH → NORMAL → LOW，各池内 FIFO）。LOW 不被饿死的保证来自提交时自动降级
     * （高频 NORMAL → LOW 不挤占交互）与 LOW_QUEUE_WARN 预警——取件本身不做复杂预算。
     * 与事件线程的按插件扫描互斥（queueLock）。
     */
    private PendingTask pollAny() {
        synchronized (queueLock) {
            var t = highPool.poll();
            if (t == null) t = normalPool.poll();
            if (t == null) t = lowPool.poll();
            return t;
        }
    }

    private boolean queueEmpty() {
        return highPool.isEmpty() && normalPool.isEmpty() && lowPool.isEmpty();
    }

    // ── 事件/补全模式（临时调度器） ─────────────────────────────────

    /** 进入事件/补全模式（事件线程调用，**不互斥**——多个事件/补全可同时进行）。 */
    public void enterEventMode() {
        eventModeCount.incrementAndGet();
    }

    /** 退出事件/补全模式（事件线程调用）：计数归零时唤醒通用调度循环。 */
    public void exitEventMode() {
        if (eventModeCount.decrementAndGet() == 0) {
            wake(schedRegion); // wake 内 notifyAll 唤醒空闲 park 的 cycle
        }
    }

    /**
     * 事件/补全线程自旋期间调用：取本事件订阅插件的任务（JS 单线程支点——事件期间该插件
     * 任务只可能来自 handler），**并顺带取当前线程所属区域的其他插件任务**——修复事件期间
     * 其他插件同步调用被冻结的问题（Paper 的 drainDuringWait 排空全部任务，此处对齐其语义）。
     * 非阻塞执行（就地/投递，ownedHere 保证线程安全）；每轮有执行上限，防止其他插件积压
     * 拖住事件响应（事件完成由 SpinPump 的 latch 检查，两轮 drain 之间及时退出）。
     */
    public void drainForPlugins(java.util.Set<String> pluginNames) {
        int budget = SPIN_DRAIN_BUDGET;
        while (budget-- > 0) {
            var t = pollForSpin(pluginNames);
            if (t == null) break;
            processTask(t);
        }
    }

    /**
     * 事件自旋取任务（与通用 pollAny 互斥：queueLock）：
     * ① 事件插件任务（任意目标）；② 其他插件中**目标属于当前线程区域**的任务（就地执行安全）。
     * 非本区域的非事件任务留给通用调度器（cycle 在事件期间暂停，事件结束即恢复）。
     */
    private PendingTask pollForSpin(java.util.Set<String> pluginNames) {
        synchronized (queueLock) {
            for (var pool : java.util.List.of(highPool, normalPool, lowPool)) {
                var it = pool.iterator();
                while (it.hasNext()) {
                    var t = it.next();
                    if (pluginNames.contains(t.pluginName())) {
                        it.remove();
                        return t;
                    }
                    if (FoliaTasks.ownedHere(t.taskType(), t.params())) {
                        it.remove();
                        return t;
                    }
                }
            }
            return null;
        }
    }

    // ── 插件清理 ───────────────────────────────────────────────────

    @Override
    public void purgePluginTasks(String pluginName) {
        synchronized (queueLock) {
            highPool.removeIf(t -> purge(t, pluginName));
            normalPool.removeIf(t -> purge(t, pluginName));
            lowPool.removeIf(t -> purge(t, pluginName));
        }
        freqTracker.removePlugin(pluginName);
    }

    private boolean purge(PendingTask t, String pluginName) {
        if (!pluginName.equals(t.pluginName())) return false;
        if (!t.isAsync()) t.future().complete(gson.toJson(Map.of("err", "plugin unloaded")));
        return true;
    }
}
