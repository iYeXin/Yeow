package yeow;

import com.google.gson.*;
import wiki.yexin.quickjs.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Worker -- 虚拟插件执行单元（基于 PluginEntity 接入运行时全链路）。
 *
 * 语义：
 * - 独立 QuickJS 上下文 + 独立线程；注册名 = `&lt;主插件&gt;.&lt;worker&gt;`（全局唯一）
 * - 事件/命令/服务以注册名登记（独立实体）；调度器任务归属注册名（独立统计/purge）
 * - fs/assets 委托主插件处理：共享主插件数据目录与权限
 * - 禁止嵌套 Worker（worker 通道返回错误）
 * - 不脱离主插件运行：主插件卸载时连带卸载
 * - profiler 统计（isVirtual = true，created by 主插件）
 */
public class WorkerThread implements PluginEntity, Runnable {
    static final Gson gson = new Gson();

    /** 注册名（plugins map 键）：<main>.<worker>。 */
    private final String entityName;
    /** 开发者视角的 worker 名（如 web-worker）。 */
    private final String name;
    /** 主插件（委托 fs/assets/http 处理 + 权限）。 */
    private final PluginThread main;
    private final String workerId;      // 主插件 JS 侧分配的 id（消息路由）
    private final MsgQueue queue = new MsgQueue();
    private final String initCode;
    private String injectCode;
    private volatile String userCode;
    private final TaskScheduler scheduler;
    private final Logger log;
    private volatile QuickJSContext ctx;
    private volatile boolean running = false;
    /** 强杀标记：waitForExit 超时且 interrupt 无法退出时置位，调用方必须重建全新实体。 */
    private volatile boolean forceKilled = false;
    /** 已遗弃（原生中断 / Thread.interrupt 均无法终止）：不再接收或分发任何消息；其上下文与线程泄漏。 */
    private volatile boolean abandoned = false;
    /** 是否正身处 JS 执行——强杀时用于区分"卡在 JS/原生运算"与"阻塞在 Java 调用"。 */
    private volatile boolean executingJs = false;
    private Thread thread;
    private ScheduledExecutorService timer;
    private ExecutorService ioExecutor;
    private final List<ScheduledFuture<?>> timerFutures = Collections.synchronizedList(new ArrayList<>());
    /** cbId → 定时任务（clear 协议用：JS 侧 clearTimeout/clearInterval 经 timer 通道取消，防僵尸周期任务）。 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timerTasks = new ConcurrentHashMap<>();
    /** 代际计数：每次 reload 自增——旧代 timer 任务投递前校验，杜绝热重载后消息串扰新生代。 */
    private volatile long generation = 0;
    private volatile CompletableFuture<Long> pendingPing;
    private volatile long pendingPingSentAt;
    /** 终止阶段 1：优雅退出等待（等待 JS 侧 unloadDone）。 */
    private static final long TERMINATE_GRACEFUL_MS = 5000;
    /** 终止阶段 3：强杀宽恕期（触发中断后等待线程自行退出）。 */
    private static final long TERMINATE_KILL_GRACE_MS = 1000;
    /** 主插件 → worker 消息回调 id（worker-inject 注册）。 */
    private volatile String messageCbId;
    /** 主插件 JS 侧该 worker 的 onMessage 回调 id（create 时传入；worker → main 投递用）。 */
    private volatile String mainMessageCb;
    /** 权限覆盖：allow 白名单（空 = 继承主插件全部权限）；deny 黑名单（优先级最高）。 */
    private final java.util.List<String> allowPermissions;
    private final java.util.List<String> denyPermissions;

    public String messageCbId() { return messageCbId; }
    public String mainMessageCb() { return mainMessageCb; }
    public void setMainMessageCb(String id) { this.mainMessageCb = id; }
    public java.util.List<String> allowPermissions() { return allowPermissions; }
    public java.util.List<String> denyPermissions() { return denyPermissions; }

    public WorkerThread(String name, String workerId, PluginThread main, String initCode, String userCode, java.util.List<String> allowPermissions, java.util.List<String> denyPermissions) {
        this.name = name;
        this.workerId = workerId;
        this.main = main;
        this.entityName = main.name + "." + name;
        this.initCode = initCode;
        this.userCode = userCode;
        this.scheduler = main.getSchedulerRef();
        this.log = main.core().host().logger();
        this.allowPermissions = allowPermissions != null ? java.util.List.copyOf(allowPermissions) : java.util.List.of();
        this.denyPermissions = denyPermissions != null ? java.util.List.copyOf(denyPermissions) : java.util.List.of();
        try (var is = WorkerThread.class.getResourceAsStream("/js/worker-inject.js")) {
            injectCode = new String(is.readAllBytes(), StandardCharsets.UTF_8)
                .replace("__WORKER_ID__", workerId).replace("__MAIN__", main.name);
        } catch (Exception ignored) {}
    }

    // ── PluginEntity ──────────────────────────────────────────────
    @Override public String name() { return entityName; }
    @Override public String source() { return main.name(); } // created by 主插件
    @Override public String type() { return "worker"; }
    @Override public boolean isVirtual() { return true; }
    @Override public boolean isRunning() { return running; }
    @Override public void postMessage(Object message) {
        if (abandoned) return; // 已遗弃引擎：不再分发
        queue.sendJs(message);
    }

    @Override
    public CompletableFuture<Long> ping() {
        if (abandoned) return null;
        synchronized (this) {
            if (pendingPing != null) return null;
            var fut = new CompletableFuture<Long>();
            pendingPing = fut;
            pendingPingSentAt = System.nanoTime();
            queue.sendJs("{\"t\":\"DEBUG\",\"p\":\"ping\"}");
            return fut;
        }
    }

    private void onPong() {
        CompletableFuture<Long> fut;
        long sentAt;
        synchronized (this) {
            fut = pendingPing;
            pendingPing = null;
            sentAt = pendingPingSentAt;
        }
        if (fut != null) fut.complete(System.nanoTime() - sentAt);
    }

    @Override public void start() { running = true; thread = new Thread(this, "yeow-worker-" + entityName); thread.setDaemon(true); thread.start(); }
    public void stop() { if (ctx != null) queue.sendJs(gson.toJson(Map.of("t","DISABLE"))); }

    @Override
    public void stopAndWait() {
        stop();
        waitForExit();
        cleanupResources();
    }

    /** PluginEntity 契约实现：忽略重建结果（内部强杀场景由主插件 handleWorker 处理）。 */
    @Override public void reload(String newCode) { reloadInternal(newCode); }

    /**
     * 重载。返回 false 表示旧线程被强杀（interrupt 无法退出）——调用方**必须**重建
     * 全新 WorkerThread（新线程/新队列/新上下文）：旧实体仍被卡死的线程引用，若在本
     * 对象上 start() 新线程，旧线程恢复后可能从共享队列偷取消息（双线程并发执行）。
     */
    public boolean reloadInternal(String newCode) {
        queue.sendJs(gson.toJson(Map.of("t","RELOAD")));
        waitForExit();
        boolean killed = forceKilled;
        forceKilled = false;
        cleanupResources();
        if (killed) return false;
        generation++; // 代际隔离：旧代 timer 任务投递前校验（见 sendTimerCb），不再进入新生代队列
        queue.clear();
        this.userCode = newCode;
        start();
        return true;
    }

    /**
     * 终止时序（四阶段）：
     * <ol>
     *   <li><b>优雅退出</b>：调用方已发送 DISABLE/RELOAD，本方法最多等待
     *       {@link #TERMINATE_GRACEFUL_MS}，直到 JS 侧执行完 onUnload 并回投
     *       {@code lifecycle:unloadDone}（{@code running=false}）。</li>
     *   <li><b>优雅失败</b>：超时仍未退出（未收到 unloadDone）。</li>
     *   <li><b>强杀</b>：置 {@code running=false}，触发 {@code ctx.interrupt()}（同一标志
     *       同时驱动 QuickJS 解释器中断与 {@code $_send} 上行检查点，均为不可捕获），并
     *       {@code thread.interrupt()} 唤醒 Java 阻塞点；随后等待
     *       {@link #TERMINATE_KILL_GRACE_MS} 的宽恕期让线程自行中止/回收。</li>
     *   <li><b>遗弃</b>：宽恕期结束仍存活 → 置 {@code abandoned}/{@code forceKilled} 并输出
     *       severe 警告，由调用方重建全新实体；旧线程一旦从卡住的原生调用返回，仍会在自身
     *       线程走 run() finally 销毁上下文（遗弃是临时的）。</li>
     * </ol>
     *
     * <b>绝不在本线程调用 ctx.destroy()</b>：跨线程销毁会导致 use-after-free / JVM 崩溃；
     * 上下文一律由创建它的 JS 线程自行销毁。JS 线程为 daemon，不阻塞 JVM 退出。
     */
    private void waitForExit() {
        // 阶段 1-2：优雅退出（等待 unloadDone），超时进入强杀
        long gracefulDeadline = System.currentTimeMillis() + TERMINATE_GRACEFUL_MS;
        while (running && System.currentTimeMillis() < gracefulDeadline) {
            try { Thread.sleep(10); } catch (InterruptedException e) { break; }
        }
        if (running) {
            boolean inJs = executingJs;
            log.warning("[" + entityName + "] worker graceful unload timed out after "
                + (TERMINATE_GRACEFUL_MS / 1000) + "s - entering forced termination "
                + (inJs ? "(executing JS)" : "(blocked outside JS)"));
            // 阶段 3：强杀——中断（QuickJS poll + $_send 检查点）+ 唤醒 Java 阻塞点，并提供宽恕期
            running = false;
            var c = ctx;
            if (c != null) { try { c.interrupt(); } catch (Exception ignored) {} } // 不可捕获中断
            thread.interrupt();
            try { thread.join(TERMINATE_KILL_GRACE_MS); } catch (InterruptedException ignored) {}
            if (thread.isAlive()) {
                // 阶段 4：宽恕期结束仍存活 → 遗弃 + 隔离（由调用方重建全新实体）
                abandoned = true;
                forceKilled = true;
                log.severe("[" + entityName + "] worker JS thread could not be terminated by native interrupt "
                    + (inJs ? "(stuck in an uninterruptible native operation, e.g. catastrophic regex/JSON)"
                            : "(blocked in a Java call that ignored Thread.interrupt())")
                    + "; the QuickJS engine is ABANDONED and QUARANTINED — no further messages/events will be "
                    + "delivered and its $send raises an uncatchable abort. It is reclaimed automatically if the "
                    + "stuck call ever returns; otherwise its context, thread and memory are leaked until a server restart.");
                try { thread.join(TERMINATE_KILL_GRACE_MS); } catch (InterruptedException ignored) {}
            }
        } else if (thread != null) {
            try { thread.join(2000); } catch (InterruptedException ignored) {}
        }
    }

    /** 上行结果：优先写入常驻缓冲区（返回状态码 1），越界/不支持则回退 JSON 字符串（$send 解析）。 */
    private Object encodeResult(Object result) {
        // 二进制传输停用（默认）：结果统一走 JSON 字符串，二进制分支保留但不可达。
        if (!yeow.transport.BinaryCodec.ENABLED) return gson.toJson(result);
        if (result == null) return 0;
        if (ctx != null && yeow.transport.BinaryCodec.encodeBinary(ctx.buffer(), null, result, gson)) return 1;
        return gson.toJson(result);
    }

    /** JS 入口包装：标记"执行中"，供强杀时区分卡在 JS 还是 Java 阻塞。 */
    private Object jsEval(String code, String file) {
        executingJs = true;
        try { return ctx.evaluate(code, file); } finally { executingJs = false; }
    }

    private Object jsCall(long handle, String arg) {
        executingJs = true;
        try { return ctx.callHandle(handle, arg); } finally { executingJs = false; }
    }

    private void jsDrain() {
        executingJs = true;
        try { ctx.drainJobs(); } finally { executingJs = false; }
    }

    private void cleanupResources() {
        timerTasks.forEach((k, f) -> f.cancel(false));
        timerTasks.clear();
        timerFutures.forEach(f -> f.cancel(false));
        timerFutures.clear();
        if (timer != null) timer.shutdownNow();
        if (ioExecutor != null) ioExecutor.shutdownNow();
        scheduler.purgePluginTasks(entityName);
        // 命令注销/事件退订/服务清理/指标注销由 RuntimeCore.unloadPlugin 统一完成
        // http 监听经主插件注册（委托处理），随主插件生命周期清理
    }

    /** 定时器回调投递：代际校验——热重载后旧代 timer 消息不得进入新生代队列（防跨代 cbId 串扰）。 */
    private void sendTimerCb(String cbId, long gen) {
        if (gen != generation) return;
        queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",true)));
    }

    @Override
    public void run() {
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "worker-timer-" + entityName); t.setDaemon(true); return t; });
        this.ioExecutor = Executors.newCachedThreadPool(r -> { var t = new Thread(r, "worker-io-" + entityName); t.setDaemon(true); return t; });
        try {
            ctx = QuickJSContext.create();
        } catch (Exception e) {
            log.warning("[" + entityName + "] Failed to create QuickJS context: " + e.getMessage());
            return;
        }
        if (ctx == null) return;
        try {
            inject();
            if (initCode != null) jsEval(initCode, "init.js");
            if (injectCode != null) jsEval(injectCode, "worker-inject.js");
            messageCbId = String.valueOf(jsEval("globalThis.__workerMessageCbId", "worker-inject.js"));
            if (userCode != null) jsEval(userCode, "main.js");

            long hmHandle = ctx.bindGlobal("$hm");
            if (hmHandle != 0) {
                jsCall(hmHandle, gson.toJson(Map.of("t", "INIT")));
                jsCall(hmHandle, gson.toJson(Map.of("t", "LOAD")));
            }
            // 实体注册（plugins map + profiler）由 load 通道的 registerPluginEntity 完成

            while (running) {
                var raw = queue.takeJs();
                while (running) {
                    if (raw == null) break;
                    try {
                        if (hmHandle != 0) {
                            if (raw instanceof String s) {
                                jsCall(hmHandle, s);
                            } else if (yeow.transport.BinaryCodec.ENABLED
                                    && yeow.transport.BinaryCodec.encodeBinary(ctx.buffer(), null, raw, gson)) {
                                jsCall(hmHandle, null);
                            } else {
                                jsCall(hmHandle, gson.toJson(raw));
                            }
                        } else {
                            String json = (raw instanceof String s) ? s : gson.toJson(raw);
                            var escaped = json.replace("\\","\\\\").replace("'","\\'");
                            jsEval("$hm('" + escaped + "')", "dispatch.js");
                        }
                    } catch (QuickJSException ex) { main.handleJSErrorPublic(ex, name); } catch (Exception ignored) {}
                    try {
                        jsDrain();
                    } catch (QuickJSException ex) {
                        main.handleJSErrorPublic(ex, name);
                    } catch (Exception e) {
                        log.warning("[" + entityName + "] job error: " + e.getMessage());
                    }
                    raw = queue.pollJs();
                }
            }
        } catch (QuickJSException e) { main.handleJSErrorPublic(e, name); } catch (Exception e) {
            log.warning("[" + entityName + "] " + e.getMessage());
        } finally {
            // Always destroy the context on its creating thread. This reclaims an abandoned
            // engine once the stuck call returns or an interrupt checkpoint fires.
            var myCtx = ctx;
            if (myCtx != null && myCtx == ctx) {
                ctx = null;
                try { myCtx.destroy(); } catch (Exception ignored) {}
                if (abandoned) {
                    log.warning("[" + entityName + "] abandoned worker JS engine reclaimed — thread and context released");
                }
            }
        }
    }

    private void inject() {
        // Worker 的 __plugin.name 为注册名（<主插件>.<worker>），version/author 继承主插件（yeow.json）
        ctx.evaluate("globalThis.__plugin = {name:'" + PluginThread.esc(entityName) + "',version:'" + PluginThread.esc(main.version()) + "',author:'" + PluginThread.esc(main.author()) + "'};");
        ctx.evaluate("globalThis.$dev = " + main.isDevMode() + ";");
        ctx.evaluate("globalThis.$binary = " + yeow.transport.BinaryCodec.ENABLED + ";");

        ctx.setGlobalFunction("$_send", args -> {
            if (abandoned) {
                // 已遗弃/隔离的引擎：拒绝任何 JS → Java 调用（不产生副作用），直接抛错回 JS。
                throw new QuickJSException("this JS engine is abandoned and quarantined; $send is disabled");
            }
            try {
                // 上行请求 payload：二进制（args[1] == null，载荷已在常驻缓冲区）或 JSON 字符串。
                // 二进制停用时恒为 JSON 字符串。
                boolean binary = yeow.transport.BinaryCodec.ENABLED && args.length > 1 && args[1] == null;
                String channel;
                JsonObject obj;
                String pld = null;
                if (binary) {
                    channel = yeow.transport.BinaryCodec.decodeChannel(ctx.buffer());
                    JsonElement e = yeow.transport.BinaryCodec.decodeBinary(ctx.buffer());
                    obj = (e != null && e.isJsonObject()) ? e.getAsJsonObject() : new JsonObject();
                } else {
                    channel = String.valueOf(args[0]);
                    pld = String.valueOf(args.length > 1 ? args[1] : "{}");
                    obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
                }
                var rt = main.core();
                if ("worker".equals(channel)) {
                    // Worker 不能创建新的 Worker；仅允许 postToMain（Worker → 主插件消息）
                    if ("postToMain".equals(obj.get("t").getAsString())) {
                        var msg = obj.getAsJsonObject("p").get("msg");
                        main.postMessage(gson.toJson(Map.of("t","cb","p",mainMessageCb(),"r", gson.fromJson(msg.toString(), Object.class))));
                        return null;
                    }
                    return gson.toJson(Map.of("err", "workers cannot create workers"));
                }
                if ("task".equals(channel)) {
                    var denied = checkTaskPermission(obj);
                    if (denied != null) return denyResult(obj, denied);
                    Object result = rt != null ? rt.submitTask(WorkerThread.this, obj) : Map.of("err", "runtime unavailable");
                    return encodeResult(result);
                }
                if ("timer".equals(channel)) {
                    var type = obj.get("type").getAsString();
                    var denied = checkPermission("timer", type);
                    if (denied != null) return gson.toJson(Map.of("err", denied));
                    if ("clear".equals(type)) {
                        // clear 协议：JS 侧 clearTimeout/clearInterval 取消 Java 定时任务
                        var cbId = obj.get("cb").getAsString();
                        var f = timerTasks.remove(cbId);
                        if (f != null) f.cancel(false);
                        return null;
                    }
                    var cbId = obj.get("cb").getAsString(); var delay = obj.get("delay").getAsLong();
                    // 延迟下限（协议层防御）：timeout ≥0；interval ≥1（scheduleAtFixedRate period 必须 >0）
                    final long gen = generation; // 代际捕获：热重载后旧任务投递前校验
                    if ("timeout".equals(type)) {
                        var f = timer.schedule(() -> { timerTasks.remove(cbId); sendTimerCb(cbId, gen); },
                            Math.max(0, delay), TimeUnit.MILLISECONDS);
                        timerTasks.put(cbId, f);
                    } else if ("interval".equals(type)) {
                        var f = timer.scheduleAtFixedRate(() -> sendTimerCb(cbId, gen),
                            Math.max(1, delay), Math.max(1, delay), TimeUnit.MILLISECONDS);
                        timerTasks.put(cbId, f);
                    }
                    return null;
                }
                // fs / http / assets：委托主插件（共享数据目录、资源）；权限按 Worker 覆盖判定
                if ("fs".equals(channel) || "assets".equals(channel) || "http".equals(channel)) {
                    var denied = checkPermission(channel, obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) return denyResult(obj, denied);
                    if (obj.has("cb")) {
                        var cbId = obj.get("cb").getAsString();
                        ioExecutor.submit(() -> {
                            var result = switch (channel) {
                                case "fs" -> main.handleFsPublic(obj);
                                case "assets" -> main.handleAssetsPublic(obj);
                                default -> main.handleHttpPublic(obj);
                            };
                            queue.sendJs(yeow.channel.SyncCallbackHelper.cbMessageRaw(cbId, result));
                        });
                        return null;
                    }
                    return switch (channel) {
                        case "fs" -> main.handleFsPublic(obj);
                        case "assets" -> main.handleAssetsPublic(obj);
                        default -> main.handleHttpPublic(obj);
                    };
                }
                if ("service".equals(channel)) {
                    var denied = checkPermission("service", obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) return gson.toJson(Map.of("err", denied));
                    return handleService(obj);
                }
                if ("debug".equals(channel)) {
                    var dt = obj.get("t").getAsString();
                    if ("reportError".equals(dt)) { main.handleJSReportPublic(gson.toJson(obj.get("p")), name); }
                    else if ("pong".equals(dt)) { onPong(); }
                    else if ("payload".equals(dt)) {
                        // 任意载荷回显（基准测试）：与主插件同语义，Worker 线程本地处理。
                        // 传输方向与请求保持对称：二进制 → 二进制结果；JSON（json 开关）→ JSON 结果。
                        var body = obj.has("p") ? obj.get("p") : null;
                        if (obj.has("cb")) {
                            var cbId = obj.get("cb").getAsString();
                            queue.sendJs(binary
                                ? yeow.channel.SyncCallbackHelper.cbMessageObject(cbId, body)
                                : yeow.channel.SyncCallbackHelper.cbMessageRaw(cbId, gson.toJson(body)));
                            return null;
                        }
                        return binary ? encodeResult(body) : gson.toJson(body);
                    }
                    return null;
                }
                if ("lifecycle".equals(channel)) {
                    if ("unloadDone".equals(obj.get("type").getAsString())) { running = false; return null; }
                    running = false; return null;
                }
                if ("log".equals(channel)) {
                    var msg = obj.has("message") ? obj.get("message").getAsString() : (pld != null ? pld : obj.toString());
                    var level = obj.has("level") ? obj.get("level").getAsString() : "INFO";
                    if (checkPermission("log", level) != null) return null;
                    switch (level) {
                        case "WARN" -> log.warning(msg);
                        case "ERROR" -> log.severe(msg);
                        default -> log.info(msg);
                    }
                    return null;
                }
                if ("env".equals(channel)) {
                    var denied = checkPermission("env", "");
                    if (denied != null) return gson.toJson(Map.of("err", denied));
                    return main.handleEnvPublic();
                }
                return null;
            } catch (Exception ex) {
                log.warning("[" + entityName + "] $_send err: " + ex.getMessage());
                return gson.toJson(Map.of("err", ex.getMessage() != null ? ex.getMessage() : ex.toString()));
            }
        });

        // init.js 已定义 console；这里静默插件直接 console.log（日志统一走 $_send → log 通道）
        ctx.evaluate("if (globalThis.console) { globalThis.console.log = function() {}; }");
    }

    // ── 统一权限门控（Worker 覆盖：deny 优先，allow 白名单，基集 = 继承主插件权限）──

    private String checkPermission(String channel, String op) {
        return PermissionGate.check(main.permissions(), allowPermissions, denyPermissions, channel + ":" + op);
    }

    /** task 通道门控：校验全部任务节点（单任务 `type` 或批量 `tasks[].type`）。 */
    private String checkTaskPermission(JsonObject obj) {
        var nodes = PermissionGate.taskNodes(obj);
        if (nodes == null) return null;
        for (var node : nodes) {
            var denied = PermissionGate.check(main.permissions(), allowPermissions, denyPermissions, node);
            if (denied != null) return denied;
        }
        return null;
    }

    private Object denyResult(JsonObject obj, String denied) {
        if (obj != null && obj.has("cb") && !obj.get("cb").getAsString().isEmpty()) {
            var cbId = obj.get("cb").getAsString();
            queue.sendJs(gson.toJson(Map.of("t", "cb", "p", cbId, "r", Map.of("err", denied))));
            return null;
        }
        return gson.toJson(Map.of("err", denied));
    }

    /** service 通道（ownerPlugin = 本 worker 注册名，独立实体语义）。 */
    private String handleService(JsonObject obj) {
        try {
            var t = obj.get("t").getAsString();
            var sm = main.core().serviceManager();
            return switch (t) {
                case "register" -> { var refName = obj.get("refName").getAsString(); var onReq = obj.get("onRequest").getAsString(); var isPublic = obj.has("public") && obj.get("public").getAsBoolean(); yield sm.registerPluginService(refName, entityName, onReq, isPublic); }
                case "request" -> {
                    var svcId = obj.get("serviceId").getAsString();
                    var path = obj.has("path") ? obj.get("path").getAsString() : "/";
                    var ct = obj.has("contentType") && !obj.get("contentType").isJsonNull() ? obj.get("contentType").getAsString() : null;
                    var headers = obj.has("headers") && obj.get("headers").isJsonObject() ? obj.getAsJsonObject("headers") : new JsonObject();
                    var timeout = obj.has("timeout") ? obj.get("timeout").getAsLong() : main.core().config().serviceRequestTimeoutMs();
                    var reqId = obj.get("requestId").getAsString();
                    sm.trackRequestConsumer(reqId, entityName, svcId, timeout);
                    sm.request(svcId, path, headers, ct, yeow.service.ServiceManager.bodyBytes(obj), reqId, entityName);
                    yield null;
                }
                case "awaitReady" -> { var svcId = obj.get("serviceId").getAsString(); var cbId = obj.get("cb").getAsString(); sm.awaitReady(svcId, cbId, entityName); yield null; }
                case "response" -> {
                    var reqId = obj.get("requestId").getAsString();
                    var ct = obj.has("contentType") && !obj.get("contentType").isJsonNull() ? obj.get("contentType").getAsString() : null;
                    var headers = obj.has("headers") && obj.get("headers").isJsonObject() ? obj.getAsJsonObject("headers") : new JsonObject();
                    sm.respondBody(reqId, entityName, headers, ct, yeow.service.ServiceManager.bodyBytes(obj));
                    yield null;
                }
                case "info" -> sm.serviceInfo(obj.get("serviceId").getAsString()).toString();
                case "unregister" -> {
                    var svcId = obj.get("serviceId").getAsString();
                    var token = obj.has("token") && !obj.get("token").isJsonNull() ? obj.get("token").getAsString() : null;
                    yield sm.unregisterService(svcId, token, entityName);
                }
                case "subscribe" -> { var svcId = obj.get("serviceId").getAsString(); var eventPath = obj.get("eventPath").getAsString(); var cbId = obj.get("cb").getAsString(); sm.subscribe(svcId, eventPath, cbId, entityName); yield "true"; }
                case "unsubscribe" -> { var svcId = obj.get("serviceId").getAsString(); var eventPath = obj.get("eventPath").getAsString(); sm.unsubscribe(svcId, eventPath, entityName); yield "true"; }
                case "publish" -> { var token = obj.get("token").getAsString(); var eventPath = obj.get("eventPath").getAsString(); var body = obj.has("body") ? obj.getAsJsonObject("body") : new JsonObject(); sm.publish(token, eventPath, body); yield "true"; }
                default -> gson.toJson(Map.of("err", "Unknown service op: " + t));
            };
        } catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }
}
