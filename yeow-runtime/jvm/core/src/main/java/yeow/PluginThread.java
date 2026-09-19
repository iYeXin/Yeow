package yeow;

import com.google.gson.*;
import wiki.yexin.quickjs.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.zip.*;
import javax.net.ssl.*;
import java.net.*;

public class PluginThread implements Runnable, PluginEntity {
    static final Gson gson = new Gson();

    public final String name;
    public final String version;
    public final String author;
    public final String jarPath;
    /** 插件包内存镜像（加载时一步到位；null = 缓存关闭/加载失败/dev 回退，assets 与原生解压走 ZipFile 直读）。 */
    public final PluginPackage pkg;
    public final MsgQueue queue = new MsgQueue();
    private String initCode;
    private volatile String userCode;
    private final TaskScheduler scheduler;
    private final RuntimeCore core;
    private final Logger log;
    /** 依附于本插件的 Worker（虚拟插件）：key = worker 名；主插件卸载时连带卸载。 */
    private final ConcurrentHashMap<String, WorkerThread> workers = new ConcurrentHashMap<>();
    private volatile Set<String> permissions;
    private final NativeManifest nativeManifest; // yeow.json native 声明（serviceId + 打包后路径 → SHA-256）
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
    private volatile String devAssetsDir;
    private volatile boolean devMode;
    private final ConcurrentHashMap<String, com.sun.net.httpserver.HttpServer> httpServers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HttpConn> httpPending = new ConcurrentHashMap<>();
    private volatile CompletableFuture<Long> pendingPing;
    private volatile long pendingPingSentAt;

    record HttpConn(String serverId, long createdAt, com.sun.net.httpserver.HttpExchange exchange) {}

    /** HTTP 请求无响应超时：JS 侧从未 respond 时关闭连接并移除（防连接/内存泄漏）。 */
    private static final long HTTP_PENDING_TIMEOUT_MS = 30_000;
    private static final long HTTP_SWEEP_INTERVAL_MS = 10_000;
    /** 终止阶段 1：优雅退出等待（等待 JS 侧 unloadDone）。 */
    private static final long TERMINATE_GRACEFUL_MS = 5000;
    /** 终止阶段 3：强杀宽恕期（触发中断后等待线程自行退出）。 */
    private static final long TERMINATE_KILL_GRACE_MS = 1000;
    private final java.util.concurrent.atomic.AtomicBoolean httpSweepStarted = new java.util.concurrent.atomic.AtomicBoolean();

    public void setDevAssetsDir(String d) { devAssetsDir = d; }
    public String getDevAssetsDir() { return devAssetsDir; }
    public void setDevMode(boolean m) { devMode = m; }
    public boolean isDevMode() { return devMode; }

    // ── PluginEntity ──────────────────────────────────────────
    @Override public String name() { return name; }
    @Override public String source() { return jarPath; }
    @Override public String type() { return "js"; }
    @Override public boolean isVirtual() { return false; }
    @Override public void postMessage(Object message) {
        if (abandoned) return; // 已遗弃引擎：不再分发（避免消息在死队列中无界累积）
        // 原始对象（JS 线程负责编码为二进制）；字符串走 JSON 回退路径
        queue.sendJs(message);
    }

    @Override
    public CompletableFuture<Long> ping() {
        if (abandoned) return null; // 遗弃引擎不再探活
        synchronized (this) {
            if (pendingPing != null) return null; // in-flight，不重复发起
            var fut = new CompletableFuture<Long>();
            pendingPing = fut;
            pendingPingSentAt = System.nanoTime();
            queue.sendJs("{\"t\":\"DEBUG\",\"p\":\"ping\"}");
            return fut;
        }
    }

    /** pong 到达：完成 in-flight ping future（往返纳秒）。 */
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
    // ──────────────────────────────────────────────────────────

    public PluginThread(String name, String version, String author, String jarPath, PluginPackage pkg, String initCode, String userCode, RuntimeCore core, Set<String> permissions, NativeManifest nativeManifest) {
        this.name = name;
        this.version = version != null ? version : "";
        this.author = author != null ? author : "";
        this.jarPath = jarPath; this.pkg = pkg; this.initCode = initCode; this.userCode = userCode;
        this.scheduler = core.scheduler();
        this.core = core;
        this.log = core.host().logger();
        this.permissions = permissions != null ? Set.copyOf(permissions) : Set.of();
        this.nativeManifest = nativeManifest != null ? nativeManifest : NativeManifest.EMPTY;
    }

    /** 兼容旧构造（version/author 置空；仅测试用，生产路径必须传 yeow.json 解析值）。 */
    public PluginThread(String name, String jarPath, String initCode, String userCode, RuntimeCore core, Set<String> permissions, NativeManifest nativeManifest) {
        this(name, "", "", jarPath, null, initCode, userCode, core, permissions, nativeManifest);
    }

    public RuntimeCore core() { return core; }

    /** 插件版本（yeow.json；__plugin.version 注入源）。 */
    public String version() { return version; }

    /** 插件作者（yeow.json；__plugin.author 注入源）。 */
    public String author() { return author; }

    /** 插件包内存镜像（null = 未启用；Worker 委托时经主插件 assets 通道共享）。 */
    public PluginPackage pluginPackage() { return pkg; }

    /** 权限快照（重建实体用，不可变）。 */
    Set<String> permissions() { return permissions; }

    /** 更新权限集（开发模式热重载时由运行时按新构建包的 computedPermissions 刷新）。 */
    public void updatePermissions(Set<String> perms) {
        this.permissions = perms != null ? Set.copyOf(perms) : Set.of();
    }

    /** 原生服务声明元数据（重建实体用，不可变）。 */
    NativeManifest nativeManifest() { return nativeManifest; }

    public void start() { running = true; thread = new Thread(this, "yeow-" + name); thread.setDaemon(true); thread.start(); }
    public boolean isRunning() { return running; }

    /** Fire-and-forget: ask the JS thread to run onUnload and exit. */
    public void stop() {
        if (ctx != null) queue.sendJs(gson.toJson(Map.of("t","DISABLE")));
    }

    /**
     * Send DISABLE, wait up to 5s for the JS thread to exit (force-kill if hung),
     * then clean up timers / IO / http resources.
     */
    public void stopAndWait() {
        stop();
        waitForExit();
        cleanupResources();
    }

    /**
     * 重载（PluginEntity 契约实现）。内部强杀场景的实体重建由 RuntimeCore 处理，
     * 本方法忽略重建结果。
     */
    @Override public void reload(String newCode) { reloadInternal(newCode); }

    /**
     * 重载。返回 false 表示旧线程被强杀（interrupt 无法退出）——调用方**必须**经
     * {@link RuntimeCore#rebuildPluginEntity} 重建全新实体（新线程/新队列/新上下文）：
     * 旧实体仍被卡死的线程引用，若在本对象上 start() 新线程，旧线程恢复后可能从
     * 共享队列偷取消息（双线程并发执行同一插件逻辑）。
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
            log.warning("[" + name + "] graceful unload timed out after "
                + (TERMINATE_GRACEFUL_MS / 1000) + "s - entering forced termination "
                + (inJs ? "(executing JS)" : "(blocked outside JS)"));
            // 阶段 3：强杀——中断（QuickJS poll + $_send 检查点）+ 唤醒 Java 阻塞点，并提供宽恕期
            running = false;
            var c = ctx;
            if (c != null) { try { c.interrupt(); } catch (Exception ignored) {} } // 不可捕获中断
            thread.interrupt(); // 唤醒可中断的 Java 阻塞点
            try { thread.join(TERMINATE_KILL_GRACE_MS); } catch (InterruptedException ignored) {}
            if (thread.isAlive()) {
                // 阶段 4：宽恕期结束仍存活 → 遗弃 + 隔离（由调用方重建全新实体）
                abandoned = true;
                forceKilled = true;
                log.severe("[" + name + "] JS thread could not be terminated by native interrupt "
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
        // 先卸载依附的 Worker（虚拟插件：完整清理经 RuntimeCore.unloadPlugin）
        for (var w : new ArrayList<>(workers.values())) {
            core.unloadPlugin(w.name());
        }
        workers.clear();
        timerTasks.forEach((k, f) -> f.cancel(false));
        timerTasks.clear();
        timerFutures.forEach(f -> f.cancel(false));
        timerFutures.clear();
        if (timer != null) timer.shutdownNow();
        if (ioExecutor != null) ioExecutor.shutdownNow();
        scheduler.purgePluginTasks(name);
        var dir = "plugins/" + name;
        httpServers.entrySet().removeIf(e -> { if (e.getKey().startsWith(dir)) { try { e.getValue().stop(0); } catch (Exception ignored) {} return true; } return false; });
        httpPending.entrySet().removeIf(e -> { if (e.getValue().serverId().startsWith(dir)) { try { e.getValue().exchange().close(); } catch (Exception ignored) {} return true; } return false; });
        // 流句柄（gzip 压缩/解压、文件读/写）：热重载/卸载时统一关闭
        for (var h : streamHandles.values()) { try { h.close(); } catch (Exception ignored) {} }
        streamHandles.clear();
        // 平台侧命令/事件/GUI/BossBar 清理（热重载、卸载、Worker 均经此路径）
        core.host().purgePlatformResources(name);
    }

    /** 定时器回调投递：代际校验——热重载后旧代 timer 消息不得进入新生代队列（防跨代 cbId 串扰）。 */
    private void sendTimerCb(String cbId, long gen) {
        if (gen != generation) return;
        queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",true)));
    }

    @Override
    public void run() {
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "timer-" + name); t.setDaemon(true); return t; });
        this.ioExecutor = Executors.newCachedThreadPool(r -> { var t = new Thread(r, "io-" + name); t.setDaemon(true); return t; });
        try {
            ctx = QuickJSContext.create();
        } catch (Exception e) {
            log.warning("[" + name + "] Failed to create QuickJS context: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            return;
        }
        if (ctx == null) { log.warning("[" + name + "] ctx is null"); return; }
        try {
            inject();
            if (initCode == null) { log.warning("[" + name + "] initCode is null"); return; }
            jsEval(initCode, "init.js");
            if (userCode == null) { log.warning("[" + name + "] userCode is null"); return; }
            jsEval(userCode, "main.js");

            long hmHandle = ctx.bindGlobal("$hm");
            if (hmHandle == 0) log.warning("[" + name + "] $hm not found");
            if (hmHandle != 0) jsCall(hmHandle, gson.toJson(Map.of("t", "INIT")));

            var prof = core.profiler();
            if (prof != null) prof.registerPlugin(PluginThread.this);

            // 消息驱动的消息循环（原子性由 BlockingQueue 保证，单消费者线程）：
            //   - 无消息 → 阻塞在 takeJs（循环"未运行"态，零轮询）
            //   - 收到消息 → 处理 → 队列有剩余立即继续（pollJs），空则回 takeJs
            // 退出：DISABLE/RELOAD 消息 → JS 侧 unloadDone → running=false → 退出循环。
            while (running) {
                var raw = queue.takeJs();
                while (running) {
                    if (raw == null) break;
                    try {
                        if (hmHandle != 0) {
                            if (raw instanceof String s) {
                                jsCall(hmHandle, s); // ready JSON
                            } else if (yeow.transport.BinaryCodec.ENABLED
                                    && yeow.transport.BinaryCodec.encodeBinary(ctx.buffer(), null, raw, gson)) {
                                jsCall(hmHandle, null); // binary in resident buffer
                            } else {
                                jsCall(hmHandle, gson.toJson(raw)); // JSON (default / overflow / unsupported)
                            }
                        } else {
                            String json = (raw instanceof String s) ? s : gson.toJson(raw);
                            var escaped = json.replace("\\","\\\\").replace("'","\\'");
                            jsEval("$hm('" + escaped + "')", "dispatch.js");
                        }
                    } catch (QuickJSException ex) { handleJSError(ex); } catch (Exception ignored) {}
                    try {
                        jsDrain();
                    } catch (QuickJSException ex) {
                        // A pending job threw (e.g. an async error surfaced by the native wrapper).
                        // Report it but keep the message loop alive - the plugin must not die here.
                        handleJSError(ex);
                    } catch (Exception e) {
                        log.warning("[" + name + "] job error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
                    }
                    raw = queue.pollJs(); // 有剩余立即取；空则退出内层，回到 takeJs 阻塞
                }
            }
        } catch (QuickJSException e) { handleJSError(e); } catch (Exception e) {
            log.warning("[" + name + "] " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            // Always destroy the context on the thread that created it (the only safe place).
            // This also RECLAIMS an abandoned engine as soon as the stuck call returns or an
            // interrupt checkpoint fires — abandonment is temporary, not a permanent leak.
            // (Only a native operation that never returns leaks forever.)
            var myCtx = ctx;
            if (myCtx != null && myCtx == ctx) {
                ctx = null;
                try { myCtx.destroy(); } catch (Exception ignored) {}
                if (abandoned) {
                    log.warning("[" + name + "] abandoned JS engine reclaimed — thread and context released");
                }
            }
        }
    }

    private void inject() {
        // __plugin 元信息来自 yeow.json（加载时解析，经构造传入；Worker 见 WorkerThread.inject，继承主插件版本/作者）
        ctx.evaluate("globalThis.__plugin = {name:'" + esc(name) + "',version:'" + esc(version) + "',author:'" + esc(author) + "'};");
        ctx.evaluate("globalThis.$dev = " + devMode + ";");
        ctx.evaluate("globalThis.$binary = " + yeow.transport.BinaryCodec.ENABLED + ";");

        ctx.setGlobalFunction("$_send", args -> {
            if (abandoned) {
                // 已遗弃/隔离的引擎：拒绝任何 JS → Java 调用（不产生副作用），直接抛错回 JS。
                throw new QuickJSException("this JS engine is abandoned and quarantined; $send is disabled");
            }
            try {
                // 上行请求 payload：二进制（args[1] == null，载荷已由 $send 写入常驻缓冲区）
                // 或 JSON 字符串。二进制停用时恒为 JSON 字符串。
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
                var rt = core;
                if ("worker".equals(channel)) {
                    // Worker 通道（内部控制，不受权限模型约束）：创建/卸载/投递/重载主插件的 Worker
                    return handleWorker(obj);
                } else if ("task".equals(channel)) {
                    // task 通道为共有接口（与 YeowRuntime.submitTask 同一实现）；
                    // 经统一门控（task:* 默认拥有，仅 Worker 的 allow/deny 会收紧）
                    var denied = checkTaskPermission(obj);
                    if (denied != null) return permissionDenied(obj, denied);
                    Object result = rt != null ? rt.submitTask(PluginThread.this, obj) : Map.of("err", "runtime unavailable");
                    return encodeResult(result);
                } else if ("timer".equals(channel)) {
                    var type = obj.get("type").getAsString();
                    var denied = checkChannelPermission("timer", type);
                    if (denied != null) return gson.toJson(Map.of("err", denied));
                    if ("clear".equals(type)) {
                        // clear 协议：JS 侧 clearTimeout/clearInterval 取消 Java 定时任务
                        // （此前只做本地注销——interval 的 scheduleAtFixedRate 会永久空转）
                        var cbId = obj.get("cb").getAsString();
                        var f = timerTasks.remove(cbId);
                        if (f != null) f.cancel(false);
                        return null;
                    }
                    var cbId = obj.get("cb").getAsString(); var delay = obj.get("delay").getAsLong();
                    // 延迟下限（协议层防御）：timeout ≥0；interval ≥1（scheduleAtFixedRate period 必须 >0，
                    // 否则抛 IAE 且 JS 侧回调已注册——静默失败 + 悬挂注册）
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
                } else if ("fs".equals(channel)) {
                    var denied = checkChannelPermission("fs", obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) {
                        if (obj.has("cb")) { var cbId = obj.get("cb").getAsString(); queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",Map.of("err", denied)))); return null; }
                        return gson.toJson(Map.of("err", denied));
                    }
                    if (obj.has("cb")) {
                        var cbId = obj.get("cb").getAsString();
                        ioExecutor.submit(() -> { var result = handleFs(obj); queue.sendJs(yeow.channel.SyncCallbackHelper.cbMessageRaw(cbId, result)); });
                        return null;
                    }
                    return handleFs(obj);
                } else if ("http".equals(channel)) {
                    var denied = checkChannelPermission("http", obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) {
                        if (obj.has("cb")) { var cbId = obj.get("cb").getAsString(); queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",Map.of("err", denied)))); return null; }
                        return gson.toJson(Map.of("err", denied));
                    }
                    return handleHttp(obj);
                } else if ("assets".equals(channel)) {
                    // assets 资源只读 / 解压被限定在本插件数据目录内（见 handleAssets）；
                    // 统一门控下默认允许，仅 Worker 的 allow/deny 可收紧
                    var denied = checkChannelPermission("assets", obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) return permissionDenied(obj, denied);
                    if (obj.has("cb")) {
                        var cbId = obj.get("cb").getAsString();
                        ioExecutor.submit(() -> { var result = handleAssets(obj); queue.sendJs(yeow.channel.SyncCallbackHelper.cbMessageRaw(cbId, result)); });
                        return null;
                    }
                    return handleAssets(obj);
                } else if ("util".equals(channel)) {
                    // util 通道（纯计算）：gzip 压缩/解压 + UTF-8 ↔ 字节转换。
                    // 字节数据以 base64 字符串承载（JS 侧引擎原生 Uint8Array.toBase64/fromBase64）；
                    // encode/decode 语义 = buffer ↔ 字符串，base64 只是承载形式。
                    var denied = checkChannelPermission("util", obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) return permissionDenied(obj, denied);
                    if (obj.has("cb")) {
                        var cbId = obj.get("cb").getAsString();
                        ioExecutor.submit(() -> { var result = handleUtil(obj); queue.sendJs(yeow.channel.SyncCallbackHelper.cbMessageRaw(cbId, result)); });
                        return null;
                    }
                    return handleUtil(obj);
                } else if ("debug".equals(channel)) {
                    var dt = obj.get("t").getAsString();
                    if ("reportError".equals(dt)) { handleJSReport(gson.toJson(obj.get("p"))); }
                    else if ("pong".equals(dt)) { onPong(); }
                    else if ("payload".equals(dt)) {
                        // 任意载荷回显（基准测试/往返延迟测量）：内容即原样返回，零解释。
                        // 传输方向与请求保持对称：二进制请求（binary=true）→ 二进制结果；
                        // JSON 请求（$send 的 json 开关，含 >16KB 回退）→ JSON 字符串结果。
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
                    else if ("command".equals(dt)) {
                        // 运行时内部测试节点（如性能基准）：**仅开发模式开放**（-Dyeow.dev=true，
                        // dev-server 默认启用）——生产环境拒绝。
                        // core 只转发、不关心具体逻辑（指令解析/执行线程由平台实现负责；
                        // 可选接口，平台不实现返回 not implemented；Folia 不实现）。
                        if (!devMode) return gson.toJson(Map.of("err", "debug command disabled (dev-only)"));
                        var cp = obj.has("p") ? obj.getAsJsonObject("p") : new JsonObject();
                        return gson.toJson(core.host().debugCommand(cp));
                    }
                    return null;
                } else if ("lifecycle".equals(channel)) {
                    var lt = obj.has("type") ? obj.get("type").getAsString() : "";
                    if ("gc-collect".equals(lt)) {
                        var ids = obj.getAsJsonArray("ids");
                        for (var el : ids) { core.instances().release(el.getAsString()); }
                        return null;
                    }
                    if ("unloadDone".equals(lt)) { running = false; return null; }
                    running = false; return null;
                } else if ("log".equals(channel)) {
                    var msg = obj.has("message") ? obj.get("message").getAsString() : (pld != null ? pld : obj.toString());
                    var level = obj.has("level") ? obj.get("level").getAsString() : "INFO";
                    // 被门控拒绝时静默丢弃（避免错误日志再触发日志通道形成回环）
                    if (checkChannelPermission("log", level) != null) return null;
                    switch (level) {
                        case "WARN" -> log.warning(msg);
                        case "ERROR" -> log.severe(msg);
                        default -> log.info(msg);
                    }
                    return null;
                } else if ("env".equals(channel)) {
                    var denied = checkChannelPermission("env", "");
                    if (denied != null) return gson.toJson(Map.of("err", denied));
                    return handleEnv();
                }
                else if ("service".equals(channel)) {
                    var denied = checkChannelPermission("service", obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) return gson.toJson(Map.of("err", denied));
                    return handleService(obj);
                }
                else { return null; }
            } catch (Exception ex) {
                log.warning("[" + name + "] $_send err: " + ex.getMessage());
                return gson.toJson(Map.of("err", ex.getMessage() != null ? ex.getMessage() : ex.toString()));
            }
        });

        // init.js 已定义 console；这里静默插件直接 console.log（日志统一走 $_send → log 通道）
        ctx.evaluate("if (globalThis.console) { globalThis.console.log = function() {}; }");
    }

    /** JS 单引号字符串转义（__plugin 注入用）。 */
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * 统一消息节点权限门控（主插件视角：仅声明权限集 + 默认策略，无 allow/deny 覆盖）。
     * 匹配与判定细节见 {@link PermissionGate}。
     */
    private String checkChannelPermission(String channel, String op) {
        return PermissionGate.check(permissions, null, null, channel + ":" + op);
    }

    /** task 通道门控：校验全部任务节点（单任务 `type` 或批量 `tasks[].type`）。 */
    private String checkTaskPermission(JsonObject obj) {
        var nodes = PermissionGate.taskNodes(obj);
        if (nodes == null) return null;
        for (var node : nodes) {
            var denied = PermissionGate.check(permissions, null, null, node);
            if (denied != null) return denied;
        }
        return null;
    }

    /**
     * 权限拒绝的统一响应：含非空 `cb` 时经回调异步回投 `{"err":...}`（返回 null），
     * 否则同步返回 `{"err":...}`。
     */
    private Object permissionDenied(JsonObject obj, String denied) {
        if (obj != null && obj.has("cb") && !obj.get("cb").getAsString().isEmpty()) {
            var cbId = obj.get("cb").getAsString();
            queue.sendJs(gson.toJson(Map.of("t", "cb", "p", cbId, "r", Map.of("err", denied))));
            return null;
        }
        return gson.toJson(Map.of("err", denied));
    }

    /** 运行时配置目录（plugins/Yeow/runtime/）：fs 写操作一律禁止修改（读取不受限）。 */
    private static final Path RUNTIME_DIR = Path.of("plugins", "Yeow", "runtime").toAbsolutePath().normalize();

    // ── Worker 通道（内部控制）与公共包装（WorkerThread 委托）─────────────

    /**
     * Worker 通道：主插件 JS 侧的 createWorker/load/unload/postMessage/reload。
     * 请求：{ "t": "create|unload|post|reload|postToMain", "p": {...} }--p 含 cb（异步回调 ok/err）。
     */
    private String handleWorker(JsonObject obj) {
        try {
            var t = obj.get("t").getAsString();
            var p = obj.has("p") ? obj.getAsJsonObject("p") : new JsonObject();
            var cb = p.has("cb") ? p.get("cb").getAsString() : null;
            java.util.function.Consumer<String> respond = (result) -> {
                if (cb != null) queue.sendJs(gson.toJson(Map.of("t","cb","p",cb,"r",result)));
            };
            return switch (t) {
                case "create" -> {
                    // 仅注册（构造句柄，不启动）：worker.load() 才执行 init/inject/代码/INIT/LOAD
                    var wname = p.get("name").getAsString();
                    if (wname.isEmpty() || "main".equals(wname) || workers.containsKey(wname)) {
                        respond.accept("{\"err\":\"invalid or duplicate worker name: " + wname + "\"}");
                        yield null;
                    }
                    var code = workerCode(p);
                    if (code == null) { respond.accept("{\"err\":\"worker entry not found\"}"); yield null; }
                    var allow = workerPermList(p, "allow");
                    var deny = workerPermList(p, "deny");
                    var w = new WorkerThread(wname, wname, PluginThread.this, initCode, code, allow, deny);
                    if (p.has("msgCb") && !p.get("msgCb").isJsonNull()) w.setMainMessageCb(p.get("msgCb").getAsString());
                    workers.put(wname, w);
                    respond.accept("true");
                    yield null;
                }
                case "load" -> {
                    // 启动已注册的 Worker（注册实体 → 执行 init.js → worker-inject.js → 代码 → INIT → LOAD）
                    var w = workers.get(p.get("name").getAsString());
                    if (w == null) { respond.accept("{\"err\":\"worker not registered\"}"); yield null; }
                    if (!w.isRunning()) {
                        var rt = core;
                        if (rt != null && rt.getPlugin(w.name()) == null) rt.registerPluginEntity(w, false);
                        else w.start();
                        long deadline = System.currentTimeMillis() + 5000;
                        while (System.currentTimeMillis() < deadline && (w.messageCbId() == null || !w.isRunning())) {
                            try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                        }
                    }
                    respond.accept("true");
                    yield null;
                }
                case "unload" -> {
                    // 卸载：停止线程并清理（物理销毁 JS 上下文），句柄保留在注册表--可重新 load
                    var w = workers.get(p.get("name").getAsString());
                    if (w != null) core.unloadPlugin(w.name());
                    respond.accept("true");
                    yield null;
                }
                case "post" -> {
                    var w = workers.get(p.get("name").getAsString());
                    if (w == null || !w.isRunning() || w.messageCbId() == null) { respond.accept("{\"err\":\"worker not loaded\"}"); yield null; }
                    var msg = p.has("msg") ? p.get("msg") : JsonNull.INSTANCE;
                    w.postMessage(gson.toJson(Map.of("t","cb","p",w.messageCbId(),"r", gson.fromJson(msg.toString(), Object.class))));
                    respond.accept("true");
                    yield null;
                }
                case "reload" -> {
                    // 重载运行中的 Worker；未加载（未 load）时报错
                    var wname2 = p.get("name").getAsString();
                    var w = workers.get(wname2);
                    if (w == null) { respond.accept("{\"err\":\"worker not registered\"}"); yield null; }
                    if (!w.isRunning()) { respond.accept("{\"err\":\"worker not loaded\"}"); yield null; }
                    var code = workerCode(p);
                    if (code == null) { respond.accept("{\"err\":\"worker entry not found\"}"); yield null; }
                    if (!w.reloadInternal(code)) {
                        // 旧线程被强杀：重建全新 WorkerThread（新线程/新队列/新上下文），
                        // 防止卡死的旧线程从共享队列偷取消息（双线程并发执行）。
                        var oldMainCb = w.mainMessageCb();
                        var nw = new WorkerThread(wname2, wname2, PluginThread.this, initCode, code, w.allowPermissions(), w.denyPermissions());
                        if (oldMainCb != null) nw.setMainMessageCb(oldMainCb);
                        workers.put(wname2, nw);
                        w = nw;
                        var rt = core;
                        if (rt != null) {
                            rt.unregisterPluginEntity(w.name()); // 强杀路径旧实体未清理——先摘除再注册
                            if (rt.getPlugin(w.name()) == null) rt.registerPluginEntity(w, false);
                            else w.start();
                        } else {
                            w.start();
                        }
                    }
                    long deadline = System.currentTimeMillis() + 5000;
                    while (System.currentTimeMillis() < deadline && (w.messageCbId() == null || !w.isRunning())) {
                        try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                    }
                    respond.accept("true");
                    yield null;
                }
                case "destroy" -> {
                    // 彻底销毁：卸载（物理销毁 JS 上下文 + 清理事件/命令/服务/任务）并从注册表移除；
                    // 与 unload 不同，句柄不可再 load——yeow-api 侧同时放行同名重建。
                    var w = workers.remove(p.get("name").getAsString());
                    if (w != null) core.unloadPlugin(w.name());
                    respond.accept("true");
                    yield null;
                }
                default -> gson.toJson(Map.of("err", "Unknown worker op: " + t));
            };
        } catch (Exception e) {
            return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /** 解析 worker 载荷的 permissions.allow / permissions.deny（缺省 → 空列表）。 */
    private static java.util.List<String> workerPermList(JsonObject p, String key) {
        if (p == null || !p.has("permissions") || !p.get("permissions").isJsonObject()) return java.util.List.of();
        var po = p.getAsJsonObject("permissions");
        if (!po.has(key) || !po.get(key).isJsonArray()) return java.util.List.of();
        var out = new java.util.ArrayList<String>();
        for (var el : po.getAsJsonArray(key)) if (el.isJsonPrimitive()) out.add(el.getAsString());
        return out;
    }

    /** 从 code 或 entry（assets 资源路径）读取 worker 代码；失败返回 null。 */
    private String workerCode(JsonObject p) {
        if (p.has("code") && !p.get("code").isJsonNull() && !p.get("code").getAsString().isEmpty())
            return p.get("code").getAsString();
        if (p.has("entry") && !p.get("entry").isJsonNull()) {
            var entry = p.get("entry").getAsString();
            var raw = handleAssetsPublic(gson.toJson(Map.of("t","read","p",Map.of("path", entry))));
            var j = gson.fromJson(raw, JsonObject.class);
            if (j != null && j.has("data")) return j.get("data").getAsString();
        }
        return null;
    }

    public TaskScheduler getSchedulerRef() { return scheduler; }
    public String checkChannelPermissionPublic(String channel, String op) { return checkChannelPermission(channel, op); }
    public String handleFsPublic(JsonObject obj) { return handleFs(obj); }
    public String handleAssetsPublic(JsonObject obj) { return handleAssets(obj); }
    public String handleHttpPublic(JsonObject obj) { return handleHttp(obj); }
    public String handleFsPublic(String pld) { return handleFs(gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class)); }
    public String handleAssetsPublic(String pld) { return handleAssets(gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class)); }
    public String handleHttpPublic(String pld) { return handleHttp(gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class)); }
    public void handleJSReportPublic(String pld, String origin) { handleJSReport(pld, origin); }
    public void handleJSErrorPublic(QuickJSException e, String origin) { handleJSError(e, origin); }
    public String handleEnvPublic() { return handleEnv(); }

    /**
     * env 通道（同步）：运行时环境信息 + 微秒时间戳。
     * - cpus：CPU 逻辑核心数
     * - memory：JVM 总内存（字节）
     * - arch：系统架构（如 windows-x64 / linux-x64 / linux-arm64）
     * - minecraftVersion：Minecraft 版本（如 1.21.4）
     * - yeow：运行时信息 { platform, version }
     * - timestamp：epoch 毫秒时间戳（小数部分为微秒，即微秒精度）
     * - pluginDir：插件数据目录路径（原 dir 通道并入；fs plugin 级 base，如 plugins/<pluginName>）
     */
    private String handleEnv() {
        var osName = System.getProperty("os.name").toLowerCase();
        String os = osName.contains("win") ? "windows" : osName.contains("mac") ? "macos" : "linux";
        var archRaw = System.getProperty("os.arch").toLowerCase();
        String arch = archRaw.contains("aarch64") || archRaw.contains("arm64") ? "arm64"
            : archRaw.contains("x86_64") || archRaw.contains("amd64") ? "x64"
            : archRaw.contains("arm") ? "armv7" : archRaw;
        var instant = java.time.Instant.now();
        double timestamp = instant.getEpochSecond() * 1000.0 + instant.getNano() / 1_000_000.0;
        String yeowVersion = core.host().runtimeVersion();
        if (yeowVersion == null) yeowVersion = "0.5.0";
        return gson.toJson(Map.of(
            "cpus", Runtime.getRuntime().availableProcessors(),
            "memory", Runtime.getRuntime().totalMemory(),
            "arch", os + "-" + arch,
            "minecraftVersion", core.host().minecraftVersion(),
            "yeow", Map.of("platform", core.host().platformName(), "version", yeowVersion),
            "timestamp", timestamp,
            // 原 dir 通道并入 env：插件数据目录（fs plugin 级 base；Worker 共享主插件目录）
            "pluginDir", "plugins/" + name));
    }

    /** 运行时配置目录（含 config.yml）的修改--fs 写操作（全部级别）一律拦截。 */
    private void assertNotRuntimeDir(Path path) throws SecurityException {
        if (path.startsWith(RUNTIME_DIR)) {
            throw new SecurityException("Cannot modify Yeow runtime directory (plugins/Yeow/runtime): " + path);
        }
    }

    /** 周期清理：JS 侧从未 respond 的请求（超时）→ 503 关闭，防止连接与内存泄漏。 */
    private void sweepHttpPending() {
        if (httpPending.isEmpty()) return;
        long cutoff = System.currentTimeMillis() - HTTP_PENDING_TIMEOUT_MS;
        httpPending.entrySet().removeIf(e -> {
            var conn = e.getValue();
            if (conn.createdAt() >= cutoff) return false;
            try { conn.exchange().sendResponseHeaders(503, -1); } catch (Exception ignored) {}
            try { conn.exchange().close(); } catch (Exception ignored) {}
            return true;
        });
    }

    private String handleFs(JsonObject obj) {        try {
            var task = obj.get("t").getAsString(); var p = obj.get("p").getAsJsonObject();
            var dot = task.indexOf('.');
            var level = dot > 0 ? task.substring(0, dot) : "plugin";
            var op = dot > 0 ? task.substring(dot + 1) : task;
            // plugin：插件数据目录（免声明）；server：服务器根（工作目录，需 fs:server.*）；
            // outer：任意路径（相对路径仍基于服务器根计算，需 fs:outer.*）。
            var base = switch (level) {
                case "server" -> Path.of("").toAbsolutePath().normalize();
                case "outer" -> null;
                default -> Path.of("plugins", name).toAbsolutePath().normalize();
            };
            return switch (op) {
                case "readFile" -> { var path = resolvePath(base, p.get("path").getAsString(), false); yield gson.toJson(Map.of("data", Files.readString(path))); }
                case "writeFile" -> { var path = resolvePath(base, p.get("path").getAsString()); assertNotRuntimeDir(path); Files.writeString(path, p.get("data").getAsString()); yield "true"; }
                case "appendFile" -> { var path = resolvePath(base, p.get("path").getAsString()); assertNotRuntimeDir(path); Files.writeString(path, p.get("data").getAsString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); yield "true"; }
                case "exists" -> { var path = resolvePath(base, p.get("path").getAsString(), false); yield String.valueOf(Files.exists(path)); }
                case "stat" -> {
                    var path = resolvePath(base, p.get("path").getAsString(), false);
                    if (!Files.exists(path)) throw new IllegalArgumentException("not found: " + path);
                    var attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
                    yield gson.toJson(Map.of(
                        "isFile", attrs.isRegularFile(),
                        "isDirectory", attrs.isDirectory(),
                        "size", attrs.size(),
                        "mtimeMs", attrs.lastModifiedTime().toMillis(),
                        "ctimeMs", attrs.creationTime().toMillis()));
                }
                case "isDirectory" -> { var path = resolvePath(base, p.get("path").getAsString(), false); yield String.valueOf(Files.isDirectory(path)); }
                case "delete" -> {
                    var path = resolvePath(base, p.get("path").getAsString(), false);
                    assertNotRuntimeDir(path);
                    if (!Files.exists(path)) yield "false";
                    // 目录递归删除（与规范 fs.md 一致）：先删最深文件再删目录自身
                    if (Files.isDirectory(path)) {
                        try (var s = Files.walk(path)) {
                            s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (Exception ignored) {} });
                        }
                    } else {
                        Files.deleteIfExists(path);
                    }
                    yield "true";
                }
                case "mkdir" -> { var path = resolvePath(base, p.get("path").getAsString(), false); assertNotRuntimeDir(path); Files.createDirectories(path); yield "true"; }
                case "list" -> { var path = resolvePath(base, p.get("path").getAsString(), false); try (var s = Files.list(path)) { yield gson.toJson(s.map(x -> x.getFileName().toString()).toList()); } }
                case "readBase64" -> { var path = resolvePath(base, p.get("path").getAsString(), false); yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(Files.readAllBytes(path)))); }
                case "writeBase64" -> { var path = resolvePath(base, p.get("path").getAsString()); assertNotRuntimeDir(path); Files.write(path, Base64.getDecoder().decode(p.get("data").getAsString())); yield "true"; }
                case "appendBase64" -> { var path = resolvePath(base, p.get("path").getAsString()); assertNotRuntimeDir(path); Files.write(path, Base64.getDecoder().decode(p.get("data").getAsString()), StandardOpenOption.CREATE, StandardOpenOption.APPEND); yield "true"; }
                case "systemPaths" -> {
                    // 仅 outer 级：返回常用系统路径（桌面/临时目录/用户主目录）
                    if (!"outer".equals(level)) throw new IllegalArgumentException("systemPaths is outer-level only");
                    var home = System.getProperty("user.home", "");
                    yield gson.toJson(Map.of(
                        "home", home,
                        "desktop", Path.of(home, "Desktop").toString(),
                        "temp", System.getProperty("java.io.tmpdir", "")));
                }
                case "getServerPath" -> {
                    // 仅 outer 级：服务器根目录（Java 进程工作目录）的绝对路径
                    if (!"outer".equals(level)) throw new IllegalArgumentException("getServerPath is outer-level only");
                    yield gson.toJson(Map.of("path", Path.of("").toAbsolutePath().normalize().toString()));
                }
                // ── 流式读写（有状态句柄：openXxx → read/write ×n → end/close；缓冲 256 KiB）──
                case "openRead" -> {
                    var path = resolvePath(base, p.get("path").getAsString(), false);
                    if (!Files.isRegularFile(path)) throw new IllegalArgumentException("not a file: " + path);
                    // 常用选项：start/end 字节偏移（start 含、end 含；缺省 = 全文件）
                    long start = p.has("start") ? p.get("start").getAsLong() : 0;
                    long end = p.has("end") ? p.get("end").getAsLong() : -1;
                    if (start < 0) throw new IllegalArgumentException("start must be >= 0");
                    if (end >= 0 && end < start) throw new IllegalArgumentException("end must be >= start");
                    var id = newHandle("fr", new yeow.util.FileStreams.Reader(path, start, end));
                    yield gson.toJson(Map.of("id", id, "size", Files.size(path)));
                }
                case "read" -> {
                    var h = handle("fr", p, yeow.util.FileStreams.Reader.class);
                    var max = p.has("maxBytes") ? p.get("maxBytes").getAsInt() : 1024 * 1024;
                    var b = h.read(max);
                    yield b == null ? gson.toJson(Map.of("eof", true))
                        : gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(b)));
                }
                case "openWrite" -> {
                    var path = resolvePath(base, p.get("path").getAsString());
                    assertNotRuntimeDir(path);
                    // 常用选项：flags——w 覆盖（默认）/ a 追加 / wx 排他创建（已存在报错）
                    var flags = p.has("flags") ? p.get("flags").getAsString() : "w";
                    yield gson.toJson(Map.of("id", newHandle("fw", new yeow.util.FileStreams.Writer(path, flags))));
                }
                case "write" -> {
                    var h = handle("fw", p, yeow.util.FileStreams.Writer.class);
                    h.write(Base64.getDecoder().decode(p.get("data").getAsString()));
                    yield "true";
                }
                case "end" -> {
                    var h = handle("fw", p, yeow.util.FileStreams.Writer.class);
                    h.end();
                    streamHandles.remove(p.get("id").getAsString());
                    yield "true";
                }
                case "close" -> { closeHandle(p); yield "true"; }
                default -> throw new IllegalArgumentException("Unknown fs: " + task);
            };
        } catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }

    /**
     * 路径解析（写操作语义）：规范化 + 越界检查 + 创建父目录。
     * 读操作请用 {@link #resolvePath(Path, String, boolean)} 传 false——
     * 查询操作不得有创建目录的副作用（原实现 readFile/exists/list 会静默建目录）。
     */
    private Path resolvePath(Path base, String userPath) throws IOException {
        return resolvePath(base, userPath, true);
    }

    private Path resolvePath(Path base, String userPath, boolean createParent) throws IOException {
        var p = (base != null ? base.resolve(userPath) : Path.of(userPath)).normalize();
        if (base != null && !p.startsWith(base)) throw new SecurityException("Path traversal: " + userPath);
        if (createParent) Files.createDirectories(p.getParent());
        return p;
    }

    /** 递归复制目录（assetsExtractDir：dev 模式源目录 → 目标）。 */
    private static void copyDirRecursive(Path src, Path dst) throws IOException {
        try (var stream = Files.walk(src)) {
            for (var p : stream.toList()) {
                var target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private String handleHttp(JsonObject obj) {
        try {
            var t = obj.get("t").getAsString(); var p = obj.get("p").getAsJsonObject();
            return switch (t) {
                case "listen" -> {
                    var pluginName = p.get("pluginName").getAsString(); var callbackId = p.get("callbackId").getAsString(); var port = p.get("port").getAsInt();
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(port), 0);
                    var id = "plugins/" + name + "/" + (port == 0 ? server.getAddress().getPort() : port);
                    server.createContext("/", exchange -> {
                        try {
                            var req = new LinkedHashMap<String, Object>();
                            var connId = UUID.randomUUID().toString();
                            req.put("serverId", id); req.put("connId", connId);
                            req.put("method", exchange.getRequestMethod()); req.put("path", exchange.getRequestURI().getPath());
                            req.put("query", exchange.getRequestURI().getQuery());
                            var headers = new LinkedHashMap<String, String>();
                            exchange.getRequestHeaders().forEach((k,v) -> headers.put(k.toLowerCase(), String.join(", ", v)));
                            req.put("headers", headers);
                            // 仅当存在请求体时才读取：无 Content-Length 的 keep-alive 请求
                            // （如普通 GET）体流为未定义长度，readAllBytes 会永久阻塞等 EOF →
                            // 该请求的回调永不投递且 io 线程被占死（http 回调丢失根因）。
                            var cl = headers.get("content-length");
                            var te = headers.get("transfer-encoding");
                            if ((cl != null && Long.parseLong(cl) > 0) || "chunked".equalsIgnoreCase(te)) {
                                req.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                            } else {
                                req.put("body", "");
                            }
                            httpPending.put(connId, new HttpConn(id, System.currentTimeMillis(), exchange));
                            queue.sendJs(gson.toJson(Map.of("t","cb","p",callbackId,"r",req)));
                        } catch (Exception ex) {
                            log.warning("[" + name + "] http request handling error: " + ex.getMessage());
                        }
                    });
                    server.setExecutor(ioExecutor); server.start();
                    httpServers.put(id, server);
                    // 周期清扫：JS 侧从不 respond 的请求超时后 503 关闭（首次 listen 时启动一次）
                    if (httpSweepStarted.compareAndSet(false, true)) {
                        timerFutures.add(timer.scheduleAtFixedRate(this::sweepHttpPending,
                            HTTP_SWEEP_INTERVAL_MS, HTTP_SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS));
                    }
                    yield gson.toJson(Map.of("serverId", id, "port", server.getAddress().getPort()));
                }
                case "respond" -> {
                    var connId = p.get("connId").getAsString();
                    var conn = httpPending.remove(connId);
                    if (conn != null) {
                        try {
                            var status = p.has("status") ? p.get("status").getAsInt() : 200;
                            if (p.has("headers") && !p.get("headers").isJsonNull()) {
                                var hs = p.getAsJsonObject("headers");
                                hs.entrySet().forEach(e2 -> conn.exchange().getResponseHeaders().add(e2.getKey(), e2.getValue().getAsString()));
                            }
                            var body = p.has("body") && !p.get("body").isJsonNull() ? p.get("body").getAsString() : "";
                            // 编码（与 fs 写语义一致）：默认 utf8 文本；'base64' 时 body 视为 base64 编码的二进制，解码后原样写出
                            var isB64 = p.has("encoding") && "base64".equalsIgnoreCase(p.get("encoding").getAsString());
                            if (isB64 && !body.isEmpty()) {
                                var bytes = Base64.getDecoder().decode(body);
                                conn.exchange().sendResponseHeaders(status, bytes.length);
                                try (var os = conn.exchange().getResponseBody()) { os.write(bytes); }
                            } else if (body.isEmpty()) {
                                conn.exchange().sendResponseHeaders(status, -1);
                            } else {
                                var bytes = body.getBytes(StandardCharsets.UTF_8);
                                conn.exchange().sendResponseHeaders(status, bytes.length);
                                try (var os = conn.exchange().getResponseBody()) { os.write(bytes); }
                            }
                        } catch (Exception ignored) {}
                        finally { try { conn.exchange().close(); } catch (Exception ignored) {} }
                    }
                    yield "true";
                }
                case "close" -> {
                    var serverId = p.get("serverId").getAsString(); var srv = httpServers.remove(serverId);
                    if (srv != null) srv.stop(0);
                    httpPending.entrySet().removeIf(e -> {
                        if (serverId.equals(e.getValue().serverId())) { try { e.getValue().exchange().close(); } catch (Exception ignored) {} return true; }
                        return false;
                    });
                    yield "true";
                }
                case "request" -> {
                    var url = p.get("url").getAsString(); var method = p.has("method") ? p.get("method").getAsString() : "GET";
                    var body = p.has("body") && !p.get("body").isJsonNull() ? p.get("body").getAsString() : null;
                    // 请求体编码（与 respond 同语义）：缺省/'utf8' → body 为 UTF-8 文本；'base64' → body 为 base64 二进制
                    var bodyBase64 = p.has("encoding") && "base64".equalsIgnoreCase(p.get("encoding").getAsString());
                    var headers = p.has("headers") ? p.getAsJsonObject("headers") : new JsonObject();
                    var responseType = p.has("responseType") ? p.get("responseType").getAsString() : "text";
                    var timeout = p.has("timeout") ? p.get("timeout").getAsLong() : 0;
                    yield handleHttpRequest(url, method, body, bodyBase64, headers, responseType, timeout);
                }
                case "requestAsync" -> {
                    var url = p.get("url").getAsString(); var method = p.has("method") ? p.get("method").getAsString() : "GET";
                    var body = p.has("body") && !p.get("body").isJsonNull() ? p.get("body").getAsString() : null;
                    // 请求体编码（与 respond 同语义）：缺省/'utf8' → body 为 UTF-8 文本；'base64' → body 为 base64 二进制
                    var bodyBase64 = p.has("encoding") && "base64".equalsIgnoreCase(p.get("encoding").getAsString());
                    var headers = p.has("headers") ? p.getAsJsonObject("headers") : new JsonObject();
                    var responseType = p.has("responseType") ? p.get("responseType").getAsString() : "text";
                    var timeout = p.has("timeout") ? p.get("timeout").getAsLong() : 0;
                    var cb = p.get("cb").getAsString();
                    ioExecutor.submit(() -> { var result = handleHttpRequest(url, method, body, bodyBase64, headers, responseType, timeout); queue.sendJs(yeow.channel.SyncCallbackHelper.cbMessageRaw(cb, result)); });
                    yield null;
                }
                default -> gson.toJson(Map.of("err", "Unknown http op: " + t));
            };
        } catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }

    private String handleHttpRequest(String url, String method, String body, boolean bodyBase64, JsonObject headers, String responseType, long timeoutMs) {
        try {
            var uri = URI.create(url); var conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod(method.toUpperCase());
            // 超时（毫秒）：请求级 timeout 覆盖默认（连接 5s / 读取 10s）
            conn.setConnectTimeout(timeoutMs > 0 ? (int) Math.min(timeoutMs, Integer.MAX_VALUE) : 5000);
            conn.setReadTimeout(timeoutMs > 0 ? (int) Math.min(timeoutMs, Integer.MAX_VALUE) : 10000);
            if (headers != null) headers.entrySet().forEach(e -> conn.setRequestProperty(e.getKey(), e.getValue().getAsString()));
            if (body != null && ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))) {
                conn.setDoOutput(true);
                // 请求体（与 fs.writeFile / respond 同语义）：base64 时解码为原始字节写出
                var bytes = bodyBase64 ? Base64.getDecoder().decode(body) : body.getBytes(StandardCharsets.UTF_8);
                conn.getOutputStream().write(bytes);
            }
            conn.connect();
            var status = conn.getResponseCode();
            java.io.InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            var responseBody = "";
            if (is != null) {
                var bytes = is.readAllBytes();
                responseBody = "base64".equalsIgnoreCase(responseType)
                    ? Base64.getEncoder().encodeToString(bytes)
                    : new String(bytes, StandardCharsets.UTF_8);
            }
            var responseHeaders = new LinkedHashMap<String, String>();
            conn.getHeaderFields().forEach((k,v) -> { if (k != null) responseHeaders.put(k.toLowerCase(), String.join(", ", v)); });
            return gson.toJson(Map.of("status", status, "body", responseBody, "headers", responseHeaders));
        } catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }

    /**
     * 解压目标解析：**必须位于插件数据目录 `plugins/<name>/` 内**。assets 通道不设权限拦截，
     * 靠此限定兜底（`resolvePath` 已做规范化 + 越界检查 + 创建父目录）。
     */
    private Path assetsTarget(String dest) throws IOException {
        var pluginDir = Path.of("plugins", name).toAbsolutePath().normalize();
        var target = resolvePath(pluginDir, dest);
        assertNotRuntimeDir(target);
        return target;
    }

    private String handleAssets(JsonObject obj) {
        try {
            var task = obj.get("t").getAsString(); var p = obj.get("p").getAsJsonObject();
            var rawPath = p.get("path").getAsString();
            // extract 必须指定 dest；extractDir 的 dest 可选（默认 assets/<path>）——dest 均基于插件数据目录计算并限定其内
            var hasDest = p.has("dest") && !p.get("dest").isJsonNull();
            var dest = hasDest ? p.get("dest").getAsString() : (rawPath.startsWith("assets/") ? rawPath : "assets/" + rawPath);
            if (devAssetsDir != null) {
                var stripped = rawPath.startsWith("assets/") ? rawPath.substring("assets/".length()) : rawPath;
                // dev 资产路径防护：规范化后必须仍在 devAssetsDir 内（../ 逃逸检查）
                var devBase = Path.of(devAssetsDir).toAbsolutePath().normalize();
                var fp = devBase.resolve(stripped).normalize();
                if (!fp.startsWith(devBase)) return gson.toJson(Map.of("err", "Invalid asset path: " + rawPath));
                return switch (task) {
                    case "read" -> { if (!Files.exists(fp)) yield "null"; yield gson.toJson(Map.of("data", Files.readString(fp))); }
                    case "readBase64" -> { if (!Files.exists(fp)) yield "null"; yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(Files.readAllBytes(fp)))); }
                    case "extract" -> {
                        if (!hasDest) yield gson.toJson(Map.of("err", "extract requires dest"));
                        if (!Files.exists(fp)) yield gson.toJson(Map.of("err", "Asset not found: " + rawPath));
                        var target = assetsTarget(dest);
                        Files.copy(fp, target, StandardCopyOption.REPLACE_EXISTING);
                        yield gson.toJson(Map.of("path", Path.of("").toAbsolutePath().normalize().relativize(target).toString()));
                    }
                    case "extractDir" -> {
                        if (!Files.isDirectory(fp)) yield gson.toJson(Map.of("err", "Asset directory not found: " + rawPath));
                        var target = assetsTarget(dest);
                        copyDirRecursive(fp, target);
                        yield gson.toJson(Map.of("path", Path.of("").toAbsolutePath().normalize().relativize(target).toString()));
                    }
                    default -> gson.toJson(Map.of("err", "Unknown assets op: " + task));
                };
            }
            // 生产：优先走加载时预解析的内存包（零 open/close）；缓存关闭或加载失败时回退 ZipFile 直读
            if (pkg != null) {
                return switch (task) {
                    case "read" -> { var b = pkg.read(rawPath); if (b == null) yield "null"; yield gson.toJson(Map.of("data", new String(b, StandardCharsets.UTF_8))); }
                    case "readBase64" -> { var b = pkg.read(rawPath); if (b == null) yield "null"; yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(b))); }
                    case "extract" -> {
                        if (!hasDest) yield gson.toJson(Map.of("err", "extract requires dest"));
                        var b = pkg.read(rawPath);
                        if (b == null) yield gson.toJson(Map.of("err", "Asset not found: " + rawPath));
                        var target = assetsTarget(dest);
                        Files.write(target, b, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        yield gson.toJson(Map.of("path", Path.of("").toAbsolutePath().normalize().relativize(target).toString()));
                    }
                    case "extractDir" -> {
                        var prefix = rawPath.endsWith("/") ? rawPath : rawPath + "/";
                        var target = assetsTarget(dest);
                        var found = false;
                        for (var zn : pkg.names()) {
                            if (!zn.startsWith(prefix) || zn.endsWith("/")) continue;
                            found = true;
                            var rel = zn.substring(prefix.length());
                            var dst = target.resolve(rel).normalize();
                            // zip-slip 防护：entry 相对路径含 ../ 时不得逃逸目标目录
                            if (!dst.startsWith(target)) throw new SecurityException("Zip entry escapes target dir: " + zn);
                            assertNotRuntimeDir(dst);
                            Files.createDirectories(dst.getParent());
                            Files.write(dst, pkg.read(zn), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        }
                        if (!found) yield gson.toJson(Map.of("err", "Asset directory not found: " + rawPath));
                        yield gson.toJson(Map.of("path", Path.of("").toAbsolutePath().normalize().relativize(target).toString()));
                    }
                    default -> gson.toJson(Map.of("err", "Unknown assets op: " + task));
                };
            }
            try (var zip = new ZipFile(jarPath)) {
                return switch (task) {
                    case "read" -> { var entry = zip.getEntry(rawPath); if (entry == null) yield "null"; yield gson.toJson(Map.of("data", new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))); }
                    case "readBase64" -> { var entry = zip.getEntry(rawPath); if (entry == null) yield "null"; yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(zip.getInputStream(entry).readAllBytes()))); }
                    case "extract" -> {
                        if (!hasDest) yield gson.toJson(Map.of("err", "extract requires dest"));
                        var entry = zip.getEntry(rawPath);
                        if (entry == null || entry.isDirectory()) yield gson.toJson(Map.of("err", "Asset not found: " + rawPath));
                        var target = assetsTarget(dest);
                        Files.copy(zip.getInputStream(entry), target, StandardCopyOption.REPLACE_EXISTING);
                        yield gson.toJson(Map.of("path", Path.of("").toAbsolutePath().normalize().relativize(target).toString()));
                    }
                    case "extractDir" -> {
                        var prefix = rawPath.endsWith("/") ? rawPath : rawPath + "/";
                        var target = assetsTarget(dest);
                        var found = false;
                        var entries = zip.entries();
                        while (entries.hasMoreElements()) {
                            var ze = entries.nextElement();
                            var zn = ze.getName();
                            if (!zn.startsWith(prefix) || ze.isDirectory()) continue;
                            found = true;
                            var rel = zn.substring(prefix.length());
                            var dst = target.resolve(rel).normalize();
                            // zip-slip 防护：entry 相对路径含 ../ 时不得逃逸目标目录
                            if (!dst.startsWith(target)) throw new SecurityException("Zip entry escapes target dir: " + zn);
                            assertNotRuntimeDir(dst);
                            Files.createDirectories(dst.getParent());
                            Files.copy(zip.getInputStream(ze), dst, StandardCopyOption.REPLACE_EXISTING);
                        }
                        if (!found) yield gson.toJson(Map.of("err", "Asset directory not found: " + rawPath));
                        yield gson.toJson(Map.of("path", Path.of("").toAbsolutePath().normalize().relativize(target).toString()));
                    }
                    default -> gson.toJson(Map.of("err", "Unknown assets op: " + task));
                };
            }
        } catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }

    /**
     * 主插件错误上报：origin = "main"（dev-server 契约：'main' = 主插件 bundle，
     * 其他值 = worker 名，对应各自 source-map 产物）。
     */
    private void handleJSReport(String pld) { handleJSReport(pld, "main"); }

    private void handleJSReport(String pld, String origin) {
        try {
            var json = gson.fromJson(pld, JsonObject.class);
            var msg = json.has("message") ? json.get("message").getAsString() : "unknown";
            var stack = json.has("stack") ? json.get("stack").getAsString() : "";
            var fileName = json.has("fileName") && !json.get("fileName").isJsonNull() ? json.get("fileName").getAsString() : "main.js";
            var line = json.has("lineNumber") ? json.get("lineNumber").getAsInt() : 0;
            var col = json.has("columnNumber") ? json.get("columnNumber").getAsInt() : 0;
            if (devMode) {
                var errPayload = new LinkedHashMap<String,Object>();
                errPayload.put("type", "js-error"); errPayload.put("plugin", name); errPayload.put("message", msg);
                errPayload.put("origin", origin); // main 或 worker 名（dev-server 按 origin 选 source-map）
                if (json.has("context") && !json.get("context").isJsonNull()) errPayload.put("context", json.get("context").getAsString());
                var stackToSend = (stack != null && !stack.isEmpty()) ? stack : msg + (fileName != null && line > 0 ? "\n    at " + fileName + ":" + line + ":" + col : "");
                errPayload.put("stack", stackToSend);
                errPayload.put("fileName", fileName); errPayload.put("lineNumber", line); errPayload.put("columnNumber", col);
                var rt = core;
                if (rt != null) rt.sendDevMessage(gson.toJson(errPayload));
            }
            var loc = fileName != null && line > 0 ? " at " + fileName + ":" + line + ":" + col : "";
            var sb = "[" + name + (origin != null && !origin.equals(name) ? ":" + origin : "") + "] JS Error: " + msg + loc;
            if (stack != null && !stack.isEmpty()) {
                var limit = stack.lines().limit(3).collect(java.util.stream.Collectors.joining("\n[" + name + "]   "));
                sb += "\n[" + name + "]   " + limit;
            }
            log.warning(sb);
        } catch (Exception ex) { log.warning("[" + name + "] JS Error: " + pld); }
    }

    /**
     * util 通道：gzip.compress / gzip.decompress / encode.utf8 / decode.utf8 +
     * gzip 流式压缩/解压（compressor / decompressor）。
     * 字节数据一律以 base64 字符串承载；流式操作分块显式响应（背压基于调用方等待返回值）。
     * 输入/输出上限由 config.yml `util` 段配置（默认 256 MiB）。
     */
    private String handleUtil(JsonObject obj) {
        try {
            var t = obj.get("t").getAsString();
            var p = obj.has("p") ? obj.getAsJsonObject("p") : new JsonObject();
            int maxInput = core.config().utilMaxInputBytes();
            int maxOutput = core.config().utilMaxOutputBytes();
            // 输入上限：二进制操作（data 为 base64 承载）解码后按原始字节校验；
            // encode.utf8 的 data 是**明文文本**（UTF-8 承载，非 base64）——按字节数校验，不得解码
            if (p.has("data")) {
                if ("encode.utf8".equals(t)) {
                    var s = p.get("data").getAsString();
                    if (s.getBytes(StandardCharsets.UTF_8).length > maxInput)
                        throw new IllegalArgumentException("util input exceeds " + maxInput + " bytes");
                } else {
                    var d = p.get("data").getAsString();
                    if (d.length() > (long) maxInput * 2) throw new IllegalArgumentException("util input exceeds " + maxInput + " bytes");
                    var raw = Base64.getDecoder().decode(d);
                    if (raw.length > maxInput) throw new IllegalArgumentException("util input exceeds " + maxInput + " bytes");
                }
            }
            return switch (t) {
                case "gzip.compress" -> {
                    var level = p.has("level") ? p.get("level").getAsInt() : -1;
                    // -1 = Deflater.DEFAULT_COMPRESSION（未指定 level 时的引擎默认级别）
                    if (level < -1 || level > 9) throw new IllegalArgumentException("level must be -1..9 (omit for default)");
                    var raw = Base64.getDecoder().decode(p.get("data").getAsString());
                    // raw=true：原始 deflate（无 GZIP 头/尾/CRC）
                    var out = p.has("raw") && p.get("raw").getAsBoolean()
                        ? yeow.util.UtilCodec.deflate(raw, level)
                        : yeow.util.UtilCodec.gzip(raw, level);
                    yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(out)));
                }
                case "gzip.decompress" -> {
                    var raw = Base64.getDecoder().decode(p.get("data").getAsString());
                    var out = p.has("raw") && p.get("raw").getAsBoolean()
                        ? yeow.util.UtilCodec.inflate(raw, maxOutput)
                        : yeow.util.UtilCodec.gunzip(raw, maxOutput);
                    yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(out)));
                }
                // 字符串 → 字节（b64 承载）
                case "encode.utf8" -> gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(yeow.util.UtilCodec.utf8(p.get("data").getAsString()))));
                // 字节（b64 承载）→ 字符串（非法 UTF-8 序列替换为 U+FFFD）
                case "decode.utf8" -> {
                    var raw = Base64.getDecoder().decode(p.get("data").getAsString());
                    yield gson.toJson(Map.of("data", yeow.util.UtilCodec.utf8(raw)));
                }
                // ── 流式 gzip（分块压缩/解压；句柄生命周期：create → write×n → finish → close）──
                case "gzip.compressor.create" -> {
                    var level = p.has("level") ? p.get("level").getAsInt() : -1;
                    // -1 = Deflater.DEFAULT_COMPRESSION（未指定 level 时的引擎默认级别）
                    if (level < -1 || level > 9) throw new IllegalArgumentException("level must be -1..9 (omit for default)");
                    var raw = p.has("raw") && p.get("raw").getAsBoolean();
                    yield gson.toJson(Map.of("id", newHandle("gc", new yeow.util.GzipCompressor(level, raw))));
                }
                case "gzip.compressor.write" -> {
                    var h = handle("gc", p, yeow.util.GzipCompressor.class);
                    var out = h.write(Base64.getDecoder().decode(p.get("data").getAsString()));
                    yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(out)));
                }
                case "gzip.compressor.finish" -> {
                    var h = handle("gc", p, yeow.util.GzipCompressor.class);
                    var out = h.finish();
                    yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(out)));
                }
                case "gzip.compressor.close" -> { closeHandle(p); yield "true"; }
                case "gzip.decompressor.create" -> {
                    var raw = p.has("raw") && p.get("raw").getAsBoolean();
                    yield gson.toJson(Map.of("id", newHandle("gd", new yeow.util.GzipDecompressor(raw))));
                }
                case "gzip.decompressor.write" -> {
                    var h = handle("gd", p, yeow.util.GzipDecompressor.class);
                    var out = h.write(Base64.getDecoder().decode(p.get("data").getAsString()));
                    yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(out)));
                }
                case "gzip.decompressor.finish" -> {
                    var h = handle("gd", p, yeow.util.GzipDecompressor.class);
                    var out = h.finish();
                    yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(out)));
                }
                case "gzip.decompressor.close" -> { closeHandle(p); yield "true"; }
                default -> throw new IllegalArgumentException("Unknown util op: " + t);
            };
        } catch (Exception e) {
            return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    // ── 流句柄注册表（util gzip 流 + fs 文件流；per-plugin，卸载/热重载时统一关闭）──

    private final ConcurrentHashMap<String, AutoCloseable> streamHandles = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong streamSeq = new java.util.concurrent.atomic.AtomicLong();

    private String newHandle(String prefix, AutoCloseable h) {
        var id = prefix + streamSeq.incrementAndGet();
        streamHandles.put(id, h);
        return id;
    }

    @SuppressWarnings("unchecked")
    private <T> T handle(String prefix, JsonObject p, Class<T> cls) {
        var id = p.get("id").getAsString();
        var h = streamHandles.get(id);
        if (h == null || !id.startsWith(prefix)) throw new IllegalArgumentException("unknown " + prefix + " handle: " + id);
        return (T) h;
    }

    private void closeHandle(JsonObject p) throws Exception {
        var h = streamHandles.remove(p.get("id").getAsString());
        if (h != null) h.close();
    }

    private String handleService(JsonObject obj) {
        try {
            var t = obj.get("t").getAsString();
            var sm = core.serviceManager();
            return switch (t) {
                case "register" -> { var refName = obj.get("refName").getAsString(); var onReq = obj.get("onRequest").getAsString(); var isPublic = obj.has("public") && obj.get("public").getAsBoolean(); yield sm.registerPluginService(refName, name, onReq, isPublic); }
                case "registerNative" -> { var refName = obj.get("refName").getAsString(); var platforms = obj.getAsJsonObject("platforms"); var isPublic = obj.has("public") && obj.get("public").getAsBoolean(); yield sm.registerNativeService(refName, name, platforms, isPublic, pkg, jarPath, devAssetsDir, nativeManifest); }
                case "registerNativeTerminate" -> { var svcId = obj.get("serviceId").getAsString(); var cbId = obj.get("cb").getAsString(); sm.registerTerminateCb(svcId, cbId, name); yield "true"; }
                case "request" -> {
                    var svcId = obj.get("serviceId").getAsString();
                    var path = obj.has("path") ? obj.get("path").getAsString() : "/";
                    var ct = obj.has("contentType") && !obj.get("contentType").isJsonNull() ? obj.get("contentType").getAsString() : null;
                    var headers = obj.has("headers") && obj.get("headers").isJsonObject() ? obj.getAsJsonObject("headers") : new JsonObject();
                    var timeout = obj.has("timeout") ? obj.get("timeout").getAsLong() : core.config().serviceRequestTimeoutMs();
                    var reqId = obj.get("requestId").getAsString();
                    sm.trackRequestConsumer(reqId, name, svcId, timeout);
                    sm.request(svcId, path, headers, ct, yeow.service.ServiceManager.bodyBytes(obj), reqId, name);
                    yield null;
                }
                case "awaitReady" -> { var svcId = obj.get("serviceId").getAsString(); var cbId = obj.get("cb").getAsString(); sm.awaitReady(svcId, cbId, name); yield null; }
                case "response" -> {
                    var reqId = obj.get("requestId").getAsString();
                    var ct = obj.has("contentType") && !obj.get("contentType").isJsonNull() ? obj.get("contentType").getAsString() : null;
                    var headers = obj.has("headers") && obj.get("headers").isJsonObject() ? obj.getAsJsonObject("headers") : new JsonObject();
                    sm.respondBody(reqId, name, headers, ct, yeow.service.ServiceManager.bodyBytes(obj));
                    yield null;
                }
                case "info" -> sm.serviceInfo(obj.get("serviceId").getAsString()).toString();
                case "unregister" -> {
                    var svcId = obj.get("serviceId").getAsString();
                    var token = obj.has("token") && !obj.get("token").isJsonNull() ? obj.get("token").getAsString() : null;
                    yield sm.unregisterService(svcId, token, name);
                }
                case "subscribe" -> { var svcId = obj.get("serviceId").getAsString(); var eventPath = obj.get("eventPath").getAsString(); var cbId = obj.get("cb").getAsString(); sm.subscribe(svcId, eventPath, cbId, name); yield "true"; }
                case "unsubscribe" -> { var svcId = obj.get("serviceId").getAsString(); var eventPath = obj.get("eventPath").getAsString(); sm.unsubscribe(svcId, eventPath, name); yield "true"; }
                case "publish" -> { var token = obj.get("token").getAsString(); var eventPath = obj.get("eventPath").getAsString(); var body = obj.has("body") ? obj.getAsJsonObject("body") : new JsonObject(); sm.publish(token, eventPath, body); yield "true"; }
                default -> gson.toJson(Map.of("err", "Unknown service op: " + t));
            };
        } catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }

    /** 主插件错误上报（origin = "main"，见 {@link #handleJSReport(String)}）。 */
    private void handleJSError(QuickJSException e) { handleJSError(e, "main"); }

    private void handleJSError(QuickJSException e, String origin) {
        try {
            var msgText = e.getMessage();
            String msg, stack, fileName;
            int line, col;
            if (msgText != null) {
                var colonIdx = msgText.indexOf(':');
                if (colonIdx > 0) {
                    var afterColon = msgText.substring(colonIdx + 1).trim();
                    if (afterColon.startsWith("{")) msgText = afterColon;
                }
            }
            if (msgText != null && msgText.startsWith("{")) {
                try {
                    var json = gson.fromJson(msgText, JsonObject.class);
                    msg = json.has("message") ? json.get("message").getAsString() : msgText;
                    stack = json.has("stack") ? json.get("stack").getAsString() : "";
                    fileName = json.has("fileName") && !json.get("fileName").isJsonNull() ? json.get("fileName").getAsString() : "main.js";
                    line = json.has("lineNumber") ? json.get("lineNumber").getAsInt() : 0;
                    col = json.has("columnNumber") ? json.get("columnNumber").getAsInt() : 0;
                } catch (Exception ignored) { msg = msgText; stack = ""; fileName = "main.js"; line = 0; col = 0; }
            } else {
                var lines = msgText != null ? msgText.split("\n") : new String[]{""};
                msg = lines.length > 0 ? lines[0].trim() : msgText;
                stack = msgText != null ? msgText : "";
                fileName = "main.js"; line = 0; col = 0;
                for (var l : lines) {
                    var m = java.util.regex.Pattern.compile("\\(?([^\\s(]+):(\\d+):(\\d+)\\)?$").matcher(l.trim());
                    if (m.find()) { fileName = m.group(1); line = Integer.parseInt(m.group(2)); col = Integer.parseInt(m.group(3)); break; }
                }
            }
            if (devMode) {
                var errPayload = new LinkedHashMap<String,Object>();
                errPayload.put("type", "js-error"); errPayload.put("plugin", name); errPayload.put("message", msg != null ? msg : "");
                errPayload.put("origin", origin);
                var stackToSend = (stack != null && !stack.isEmpty()) ? stack : (msg != null ? msg : "") + (fileName != null && line > 0 ? "\n    at " + fileName + ":" + line + ":" + col : "");
                errPayload.put("stack", stackToSend);
                errPayload.put("fileName", fileName != null ? fileName : "main.js");
                errPayload.put("lineNumber", line); errPayload.put("columnNumber", col);
                var rt = core;
                if (rt != null) rt.sendDevMessage(gson.toJson(errPayload));
            }
            var loc = fileName != null && line > 0 ? " at " + fileName + ":" + line + ":" + col : "";
            var sb = "[" + name + (origin != null && !origin.equals(name) ? ":" + origin : "") + "] JS Error: " + (msg != null ? msg : "unknown") + loc;
            if (stack != null && !stack.isEmpty()) {
                var limit = stack.lines().limit(3).collect(java.util.stream.Collectors.joining("\n[" + name + "]   "));
                sb += "\n[" + name + "]   " + limit;
            }
            log.warning(sb);
        } catch (Exception ignored) { log.warning("[" + name + "] JS Error: " + e.getMessage()); }
    }
}
