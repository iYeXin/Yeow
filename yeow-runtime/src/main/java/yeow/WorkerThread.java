package yeow;

import com.google.gson.*;
import com.whl.quickjs.wrapper.*;
import yeow.task.Tasks;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Worker —— 虚拟插件执行单元（基于 PluginEntity 接入运行时全链路）。
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
    private final Scheduler scheduler;
    private volatile QuickJSContext ctx;
    private volatile boolean running = false;
    private Thread thread;
    private ScheduledExecutorService timer;
    private ExecutorService ioExecutor;
    private final List<ScheduledFuture<?>> timerFutures = Collections.synchronizedList(new ArrayList<>());
    private volatile CompletableFuture<Long> pendingPing;
    private volatile long pendingPingSentAt;
    /** 主插件 → worker 消息回调 id（worker-inject 注册）。 */
    private volatile String messageCbId;
    /** 主插件 JS 侧该 worker 的 onMessage 回调 id（create 时传入；worker → main 投递用）。 */
    private volatile String mainMessageCb;

    public String messageCbId() { return messageCbId; }
    public String mainMessageCb() { return mainMessageCb; }
    public void setMainMessageCb(String id) { this.mainMessageCb = id; }

    public WorkerThread(String name, String workerId, PluginThread main, String initCode, String userCode) {
        this.name = name;
        this.workerId = workerId;
        this.main = main;
        this.entityName = main.name + "." + name;
        this.initCode = initCode;
        this.userCode = userCode;
        this.scheduler = main.getSchedulerRef();
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
        queue.sendJs(message instanceof String s ? s : gson.toJson(message));
    }

    @Override
    public CompletableFuture<Long> ping() {
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

    @Override public void start() { running = true; thread = new Thread(this, "yeow-worker-" + entityName); thread.start(); }
    @Override public void stop() { if (ctx != null) queue.sendJs(gson.toJson(Map.of("t","DISABLE"))); }

    @Override
    public void stopAndWait() {
        stop();
        waitForExit();
        cleanupResources();
    }

    @Override
    public void reload(String newCode) {
        queue.sendJs(gson.toJson(Map.of("t","RELOAD")));
        waitForExit();
        cleanupResources();
        queue.clear();
        this.userCode = newCode;
        start();
    }

    private void waitForExit() {
        long deadline = System.currentTimeMillis() + 5000;
        while (running && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException e) { break; }
        }
        if (running) {
            org.bukkit.Bukkit.getLogger().warning("[" + entityName + "] worker unresponsive 5s — forcing stop");
            running = false;
            thread.interrupt();
            try { thread.join(1000); } catch (InterruptedException ignored) {}
            if (thread.isAlive()) {
                var c = ctx;
                if (c != null) {
                    ctx = null;
                    try { c.destroy(); } catch (Exception ignored) {}
                }
                try { thread.join(1000); } catch (InterruptedException ignored) {}
            }
        } else if (thread != null) {
            try { thread.join(2000); } catch (InterruptedException ignored) {}
        }
    }

    private void cleanupResources() {
        timerFutures.forEach(f -> f.cancel(false));
        timerFutures.clear();
        if (timer != null) timer.shutdownNow();
        if (ioExecutor != null) ioExecutor.shutdownNow();
        scheduler.purgePluginTasks(entityName);
        try { Tasks.execute("command.unregisterAll", new Gson().fromJson("{\"pluginName\":\"" + entityName + "\"}", JsonObject.class)); } catch (Exception ignored) {}
        var rt = YeowRuntime.inst();
        if (rt != null) {
            rt.getEventBridge().unsubscribeAll(entityName);
            if (rt.getServiceManager() != null) rt.getServiceManager().purgePluginServices(entityName);
            if (rt.getProfiler() != null) rt.getProfiler().unregisterPlugin(entityName);
        }
        // http 监听经主插件注册（委托处理），随主插件生命周期清理
    }

    @Override
    public void run() {
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "worker-timer-" + entityName));
        this.ioExecutor = Executors.newCachedThreadPool(r -> new Thread(r, "worker-io-" + entityName));
        try {
            ctx = QuickJSContext.create();
        } catch (Exception e) {
            org.bukkit.Bukkit.getLogger().warning("[" + entityName + "] Failed to create QuickJS context: " + e.getMessage());
            return;
        }
        if (ctx == null) return;
        try {
            inject();
            if (initCode != null) ctx.evaluate(initCode, "init.js");
            if (injectCode != null) ctx.evaluate(injectCode, "worker-inject.js");
            messageCbId = String.valueOf(ctx.evaluate("globalThis.__workerMessageCbId"));
            if (userCode != null) ctx.evaluate(userCode, "main.js");

            var hmObj = ctx.getGlobalObject().getProperty("$hm");
            var hmFunc = hmObj instanceof JSFunction ? (JSFunction) hmObj : null;
            if (hmFunc != null) hmFunc.call(gson.toJson(Map.of("t","INIT")));
            if (hmFunc != null) hmFunc.call(gson.toJson(Map.of("t","LOAD")));
            // 实体注册（plugins map + profiler）由 load 通道的 registerPluginEntity 完成

            while (running) {
                var raw = queue.takeJs();
                while (running) {
                    if (raw == null) break;
                    try {
                        if (hmFunc != null) hmFunc.call(raw);
                        else {
                            var escaped = raw.replace("\\","\\\\").replace("'","\\'");
                            ctx.evaluate("$hm('" + escaped + "')");
                        }
                    } catch (QuickJSException ex) { main.handleJSErrorPublic(ex, name); } catch (Exception ignored) {}
                    try {
                        while (ctx.isJobPending()) ctx.executePendingJob();
                    } catch (QuickJSException ex) {
                        main.handleJSErrorPublic(ex, name);
                    } catch (Exception e) {
                        org.bukkit.Bukkit.getLogger().warning("[" + entityName + "] job error: " + e.getMessage());
                    }
                    raw = queue.pollJs();
                }
            }
        } catch (QuickJSException e) { main.handleJSErrorPublic(e, name); } catch (Exception e) {
            org.bukkit.Bukkit.getLogger().warning("[" + entityName + "] " + e.getMessage());
        } finally {
            var myCtx = ctx;
            if (myCtx != null && myCtx == ctx) {
                ctx = null;
                try { myCtx.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    private void inject() {
        var g = ctx.getGlobalObject();
        ctx.evaluate("globalThis.__plugin = {name:'" + entityName.replace("'","\\'") + "',version:'',author:''};");
        ctx.evaluate("globalThis.$dev = " + main.isDevMode() + ";");

        g.setProperty("$_send", (JSCallFunction) args -> {
            try {
                var channel = String.valueOf(args[0]); var pld = String.valueOf(args.length > 1 ? args[1] : "{}");
                var rt = YeowRuntime.inst();
                if ("worker".equals(channel)) {
                    // Worker 不能创建新的 Worker；仅允许 postToMain（Worker → 主插件消息）
                    var o = gson.fromJson(pld, JsonObject.class);
                    if ("postToMain".equals(o.get("t").getAsString())) {
                        var msg = o.getAsJsonObject("p").get("msg");
                        main.postMessage(gson.toJson(Map.of("t","cb","p",mainMessageCb(),"r", gson.fromJson(msg.toString(), Object.class))));
                        return null;
                    }
                    return gson.toJson(Map.of("err", "workers cannot create workers"));
                }
                if ("task".equals(channel)) {
                    return rt != null ? rt.submitTask(WorkerThread.this, pld) : gson.toJson(Map.of("err", "runtime unavailable"));
                }
                if ("timer".equals(channel)) {
                    var obj = gson.fromJson(pld, JsonObject.class); var type = obj.get("type").getAsString(); var cbId = obj.get("cb").getAsString(); var delay = obj.get("delay").getAsLong();
                    if ("timeout".equals(type)) {
                        var f = timer.schedule(() -> queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",true))), delay, TimeUnit.MILLISECONDS);
                        timerFutures.add(f);
                    } else if ("interval".equals(type)) {
                        var f = timer.scheduleAtFixedRate(() -> queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",true))), delay, delay, TimeUnit.MILLISECONDS);
                        timerFutures.add(f);
                    }
                    return null;
                }
                // fs / assets / http：委托主插件（共享数据目录、权限、资源）
                if ("fs".equals(channel) || "assets".equals(channel) || "http".equals(channel)) {
                    var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
                    var denied = main.checkChannelPermissionPublic(channel, obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) {
                        if (obj.has("cb")) { var cbId = obj.get("cb").getAsString(); queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",Map.of("err", denied)))); return null; }
                        return gson.toJson(Map.of("err", denied));
                    }
                    if (obj.has("cb")) {
                        var cbId = obj.get("cb").getAsString();
                        ioExecutor.submit(() -> {
                            var result = switch (channel) {
                                case "fs" -> main.handleFsPublic(pld);
                                case "assets" -> main.handleAssetsPublic(pld);
                                default -> main.handleHttpPublic(pld);
                            };
                            queue.sendJs(yeow.channel.SyncCallbackHelper.cbMessage(cbId, main.toJsonValuePublic(result)));
                        });
                        return null;
                    }
                    return switch (channel) {
                        case "fs" -> main.handleFsPublic(pld);
                        case "assets" -> main.handleAssetsPublic(pld);
                        default -> main.handleHttpPublic(pld);
                    };
                }
                if ("service".equals(channel)) {
                    var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
                    var denied = main.checkChannelPermissionPublic("service", obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) return gson.toJson(Map.of("err", denied));
                    return handleService(pld);
                }
                if ("debug".equals(channel)) {
                    var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
                    var dt = obj.get("t").getAsString();
                    if ("reportError".equals(dt)) { main.handleJSReportPublic(gson.toJson(obj.get("p")), name); }
                    else if ("pong".equals(dt)) { onPong(); }
                    return null;
                }
                if ("lifecycle".equals(channel)) {
                    var o = gson.fromJson(pld, JsonObject.class);
                    if ("unloadDone".equals(o.get("type").getAsString())) { running = false; return null; }
                    running = false; return null;
                }
                if ("log".equals(channel)) {
                    var o = gson.fromJson(pld, JsonObject.class); org.bukkit.Bukkit.getLogger().info(o.has("message") ? o.get("message").getAsString() : pld); return null;
                }
                if ("env".equals(channel)) { return main.handleEnvPublic(); }
                if ("dir".equals(channel)) { return main.getDataDirPublic(); }
                return null;
            } catch (Exception ex) {
                org.bukkit.Bukkit.getLogger().warning("[" + entityName + "] $_send err: " + ex.getMessage());
                return gson.toJson(Map.of("err", ex.getMessage() != null ? ex.getMessage() : ex.toString()));
            }
        });

        var consoleObj = ctx.getGlobalObject().getProperty("console");
        if (consoleObj instanceof JSObject jsConsole) {
            jsConsole.setProperty("log", (JSCallFunction) a -> null);
        }
    }

    /** service 通道（ownerPlugin = 本 worker 注册名，独立实体语义）。 */
    private String handleService(String pld) {
        try {
            var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
            var t = obj.get("t").getAsString();
            var sm = YeowRuntime.inst().getServiceManager();
            return switch (t) {
                case "register" -> { var refName = obj.get("refName").getAsString(); var onReq = obj.get("onRequest").getAsString(); var isPublic = obj.has("public") && obj.get("public").getAsBoolean(); yield sm.registerPluginService(refName, entityName, onReq, isPublic); }
                case "request" -> { var svcId = obj.get("serviceId").getAsString(); var path = obj.has("path") ? obj.get("path").getAsString() : "/"; var body = obj.has("body") ? obj.getAsJsonObject("body") : new JsonObject(); var reqId = obj.get("requestId").getAsString(); sm.trackRequestConsumer(reqId, entityName, svcId); sm.request(svcId, path, body, reqId, entityName); yield null; }
                case "awaitReady" -> { var svcId = obj.get("serviceId").getAsString(); var cbId = obj.get("cb").getAsString(); sm.awaitReady(svcId, cbId, entityName); yield null; }
                case "response" -> { var reqId = obj.get("requestId").getAsString(); var result = obj.has("body") ? gson.fromJson(obj.get("body").toString(), Object.class) : null; sm.respond(reqId, entityName, result); yield null; }
                case "subscribe" -> { var svcId = obj.get("serviceId").getAsString(); var eventPath = obj.get("eventPath").getAsString(); var cbId = obj.get("cb").getAsString(); sm.subscribe(svcId, eventPath, cbId, entityName); yield "true"; }
                case "unsubscribe" -> { var svcId = obj.get("serviceId").getAsString(); var eventPath = obj.get("eventPath").getAsString(); sm.unsubscribe(svcId, eventPath, entityName); yield "true"; }
                case "publish" -> { var token = obj.get("token").getAsString(); var eventPath = obj.get("eventPath").getAsString(); var body = obj.has("body") ? obj.getAsJsonObject("body") : new JsonObject(); sm.publish(token, eventPath, body); yield "true"; }
                default -> gson.toJson(Map.of("err", "Unknown service op: " + t));
            };
        } catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }
}
