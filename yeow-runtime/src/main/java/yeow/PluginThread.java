package yeow;

import com.google.gson.*;
import com.whl.quickjs.wrapper.*;
import yeow.task.Tasks;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.zip.*;
import javax.net.ssl.*;
import java.net.*;

public class PluginThread implements Runnable, PluginEntity {
    static final Gson gson = new Gson();

    public final String name;
    public final String jarPath;
    public final MsgQueue queue = new MsgQueue();
    private String initCode;
    private volatile String userCode;
    private final Scheduler scheduler;
    private final Set<String> permissions;
    private final Map<String, String> nativeHashes; // 打包后路径(assets/<id>/...) → SHA-256（yeow.json native 声明）
    private volatile QuickJSContext ctx;
    private volatile boolean running = false;
    private Thread thread;
    private ScheduledExecutorService timer;
    private ExecutorService ioExecutor;
    private final List<ScheduledFuture<?>> timerFutures = Collections.synchronizedList(new ArrayList<>());
    private volatile String devAssetsDir;
    private volatile boolean devMode;
    private final ConcurrentHashMap<String, com.sun.net.httpserver.HttpServer> httpServers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HttpConn> httpPending = new ConcurrentHashMap<>();
    private volatile CompletableFuture<Long> pendingPing;
    private volatile long pendingPingSentAt;

    record HttpConn(String serverId, com.sun.net.httpserver.HttpExchange exchange) {}

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
        // JS 适配器需要 JSON 字符串：POJO 由运行时序列化，String 原样投递
        queue.sendJs(message instanceof String s ? s : gson.toJson(message));
    }

    @Override
    public CompletableFuture<Long> ping() {
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

    public PluginThread(String name, String jarPath, String initCode, String userCode, Scheduler scheduler, Set<String> permissions, Map<String, String> nativeHashes) {
        this.name = name; this.jarPath = jarPath; this.initCode = initCode; this.userCode = userCode; this.scheduler = scheduler;
        this.permissions = permissions != null ? Set.copyOf(permissions) : Set.of();
        this.nativeHashes = nativeHashes != null ? Map.copyOf(nativeHashes) : Map.of();
    }

    public void start() { running = true; thread = new Thread(this, "yeow-" + name); thread.start(); }
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
            org.bukkit.Bukkit.getLogger().warning("[" + name + "] JS thread unresponsive for 5s — forcing stop");
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
        scheduler.purgePluginTasks(name);
        var dir = "plugins/" + name;
        httpServers.entrySet().removeIf(e -> { if (e.getKey().startsWith(dir)) { try { e.getValue().stop(0); } catch (Exception ignored) {} return true; } return false; });
        httpPending.entrySet().removeIf(e -> { if (e.getValue().serverId().startsWith(dir)) { try { e.getValue().exchange().close(); } catch (Exception ignored) {} return true; } return false; });
        yeow.task.GuiTasks.purgePlugin(name);
        yeow.task.BossBarTasks.purgePlugin(name);
    }

    @Override
    public void run() {
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "timer-" + name));
        this.ioExecutor = Executors.newCachedThreadPool(r -> new Thread(r, "io-" + name));
        try {
            ctx = QuickJSContext.create();
        } catch (Exception e) {
            org.bukkit.Bukkit.getLogger().warning("[" + name + "] Failed to create QuickJS context: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            return;
        }
        if (ctx == null) { org.bukkit.Bukkit.getLogger().warning("[" + name + "] ctx is null"); return; }
        try {
            inject();
            if (initCode == null) { org.bukkit.Bukkit.getLogger().warning("[" + name + "] initCode is null"); return; }
            ctx.evaluate(initCode, "init.js");
            if (userCode == null) { org.bukkit.Bukkit.getLogger().warning("[" + name + "] userCode is null"); return; }
            ctx.evaluate(userCode, "main.js");

            var hmObj = ctx.getGlobalObject().getProperty("$hm");
            var hmFunc = hmObj instanceof JSFunction ? (JSFunction)hmObj : null;
            if (hmFunc == null) org.bukkit.Bukkit.getLogger().warning("[" + name + "] $hm not found");

            if (hmFunc != null) hmFunc.call(gson.toJson(Map.of("t","INIT")));

            var prof = yeow.YeowRuntime.inst().getProfiler();
            if (prof != null) prof.registerPlugin(PluginThread.this);

            while (running) {
                var raw = queue.pollJs(50);
                if (raw != null) {
                    try {
                        if (hmFunc != null) {
                            hmFunc.call(raw);
                        } else {
                            var escaped = raw.replace("\\","\\\\").replace("'","\\'");
                            ctx.evaluate("$hm('" + escaped + "')");
                        }
                    } catch (QuickJSException ex) { handleJSError(ex); } catch (Exception ignored) {}
                }
                try {
                    while (ctx.isJobPending()) ctx.executePendingJob();
                } catch (QuickJSException ex) {
                    // A pending job threw (e.g. an async error surfaced by the native wrapper).
                    // Report it but keep the message loop alive — the plugin must not die here.
                    handleJSError(ex);
                } catch (Exception e) {
                    org.bukkit.Bukkit.getLogger().warning("[" + name + "] job error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
                }
            }
        } catch (QuickJSException e) { handleJSError(e); } catch (Exception e) {
            org.bukkit.Bukkit.getLogger().warning("[" + name + "] " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            // Only destroy the context this thread created. If reload() force-destroyed it
            // (ctx set to null) or a new thread already created a new one, leave it alone.
            var myCtx = ctx;
            if (myCtx != null && myCtx == ctx) {
                ctx = null;
                try { myCtx.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    private void inject() {
        var g = ctx.getGlobalObject();
        var dir = "plugins/" + name;
        ctx.evaluate("globalThis.__plugin = {name:'" + name.replace("'","\\'") + "',version:'',author:''};");
        ctx.evaluate("globalThis.$dev = " + devMode + ";");

        g.setProperty("$_send", (JSCallFunction) args -> {
            try {
                var channel = String.valueOf(args[0]); var pld = String.valueOf(args.length > 1 ? args[1] : "{}");
                var rt = yeow.YeowRuntime.inst();
                if ("task".equals(channel)) {
                    // task 通道为共有接口（适配器同一入口：YeowRuntime.submitTask）
                    return rt != null ? rt.submitTask(PluginThread.this, pld) : gson.toJson(Map.of("err", "runtime unavailable"));
                } else if ("timer".equals(channel)) {
                    var obj = gson.fromJson(pld, JsonObject.class); var type = obj.get("type").getAsString(); var cbId = obj.get("cb").getAsString(); var delay = obj.get("delay").getAsLong();
                    if ("timeout".equals(type)) {
                        var f = timer.schedule(() -> queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",true))), delay, TimeUnit.MILLISECONDS);
                        timerFutures.add(f);
                    } else if ("interval".equals(type)) {
                        var f = timer.scheduleAtFixedRate(() -> queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",true))), delay, delay, TimeUnit.MILLISECONDS);
                        timerFutures.add(f);
                    }
                    return null;
                } else if ("fs".equals(channel)) {
                    var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
                    var denied = checkChannelPermission("fs", obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) {
                        if (obj.has("cb")) { var cbId = obj.get("cb").getAsString(); queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",Map.of("err", denied)))); return null; }
                        return gson.toJson(Map.of("err", denied));
                    }
                    if (obj.has("cb")) {
                        var cbId = obj.get("cb").getAsString();
                        ioExecutor.submit(() -> { var result = handleFs(pld); queue.sendJs(yeow.channel.SyncCallbackHelper.cbMessage(cbId, result)); });
                        return null;
                    }
                    return handleFs(pld);
                } else if ("http".equals(channel)) {
                    var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
                    var denied = checkChannelPermission("http", obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) {
                        if (obj.has("cb")) { var cbId = obj.get("cb").getAsString(); queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",Map.of("err", denied)))); return null; }
                        return gson.toJson(Map.of("err", denied));
                    }
                    return handleHttp(pld);
                } else if ("assets".equals(channel)) {
                    var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
                    var denied = checkChannelPermission("assets", obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) {
                        if (obj.has("cb")) { var cbId = obj.get("cb").getAsString(); queue.sendJs(gson.toJson(Map.of("t","cb","p",cbId,"r",Map.of("err", denied)))); return null; }
                        return gson.toJson(Map.of("err", denied));
                    }
                    if (obj.has("cb")) {
                        var cbId = obj.get("cb").getAsString();
                        ioExecutor.submit(() -> { var result = handleAssets(pld); queue.sendJs(yeow.channel.SyncCallbackHelper.cbMessage(cbId, result)); });
                        return null;
                    }
                    return handleAssets(pld);
                } else if ("debug".equals(channel)) {
                    var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
                    var dt = obj.get("t").getAsString();
                    if ("reportError".equals(dt)) { handleJSReport(gson.toJson(obj.get("p"))); }
                    else if ("pong".equals(dt)) { onPong(); }
                    return null;
                } else if ("lifecycle".equals(channel)) {
                    var o = gson.fromJson(pld, JsonObject.class); var lt = o.has("type") ? o.get("type").getAsString() : "";
                    if ("gc-collect".equals(lt)) {
                        var ids = o.getAsJsonArray("ids");
                        for (var el : ids) { var id = el.getAsString();
                            if (id.startsWith("gui_")) yeow.task.GuiTasks.remove(id);
                            else if (id.startsWith("boss_")) yeow.task.BossBarTasks.remove(id); }
                        return null;
                    }
                    if ("unloadDone".equals(lt)) { running = false; return null; }
                    running = false; return null;
                } else if ("log".equals(channel)) {
                    var o = gson.fromJson(pld, JsonObject.class); org.bukkit.Bukkit.getLogger().info(o.has("message") ? o.get("message").getAsString() : pld); return null;
                } else if ("now".equals(channel)) { return String.valueOf(System.nanoTime()); }
                else if ("service".equals(channel)) {
                    var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
                    var denied = checkChannelPermission("service", obj.has("t") ? obj.get("t").getAsString() : "");
                    if (denied != null) return gson.toJson(Map.of("err", denied));
                    return handleService(pld);
                }
                else if ("dir".equals(channel)) { return dir; }
                else { return null; }
            } catch (Exception ex) {
                org.bukkit.Bukkit.getLogger().warning("[" + name + "] $_send err: " + ex.getMessage());
                return gson.toJson(Map.of("err", ex.getMessage() != null ? ex.getMessage() : ex.toString()));
            }
        });

        var consoleObj = ctx.getGlobalObject().getProperty("console");
        if (consoleObj instanceof JSObject jsConsole) {
            jsConsole.setProperty("log", (JSCallFunction) a -> null);
        }
    }

    /**
     * Sensitive-permission check for message channels（JS 插件特有的权限模型）。
     * 权限只按消息节点（channel:node）考虑；节点名中的段是业务/访问范围命名，非层级。
     * 策略：声明命中（精确节点 / channel:* / channel:段.*）→ 允许；否则命中默认拒绝
     * 前缀 → 拒绝；否则默认允许。
     */
    private static final String[] DEFAULT_DENIED_NODES = {
        "fs:server.", "fs:outer.", "http:", "service:registerNative", "assets:extract",
    };

    /** 运行时配置目录（plugins/Yeow/runtime/）：fs 写操作一律禁止修改（读取不受限）。 */
    private static final Path RUNTIME_DIR = Path.of("plugins", "Yeow", "runtime").toAbsolutePath().normalize();

    private String checkChannelPermission(String channel, String op) {
        var node = channel + ":" + op;
        if (permissions.contains(node) || permissions.contains(channel + ":*")) return null;
        var dot = op.indexOf('.');
        if (dot > 0 && permissions.contains(channel + ":" + op.substring(0, dot) + ".*")) return null;
        for (var denied : DEFAULT_DENIED_NODES) {
            if (node.startsWith(denied)) return "Permission denied: " + node;
        }
        return null;
    }

    /** 禁止对 Yeow 运行时配置目录（含 approve.json / config.yml）的修改——fs 写操作（全部级别）一律拦截。 */
    private void assertNotRuntimeDir(Path path) throws SecurityException {
        if (path.startsWith(RUNTIME_DIR)) {
            throw new SecurityException("Cannot modify Yeow runtime directory (plugins/Yeow/runtime): " + path);
        }
    }

    private String handleFs(String pld) {
        try {
            var obj = gson.fromJson(pld, JsonObject.class); var task = obj.get("t").getAsString(); var p = obj.get("p").getAsJsonObject();
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
                case "readFile" -> { var path = resolvePath(base, p.get("path").getAsString()); yield gson.toJson(Map.of("data", Files.readString(path))); }
                case "writeFile" -> { var path = resolvePath(base, p.get("path").getAsString()); assertNotRuntimeDir(path); Files.writeString(path, p.get("data").getAsString()); yield "true"; }
                case "appendFile" -> { var path = resolvePath(base, p.get("path").getAsString()); assertNotRuntimeDir(path); Files.writeString(path, p.get("data").getAsString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); yield "true"; }
                case "exists" -> { var path = resolvePath(base, p.get("path").getAsString()); yield String.valueOf(Files.exists(path)); }
                case "isDirectory" -> { var path = resolvePath(base, p.get("path").getAsString()); yield String.valueOf(Files.isDirectory(path)); }
                case "delete" -> { var path = resolvePath(base, p.get("path").getAsString()); assertNotRuntimeDir(path); yield String.valueOf(Files.deleteIfExists(path)); }
                case "mkdir" -> { var path = resolvePath(base, p.get("path").getAsString()); assertNotRuntimeDir(path); Files.createDirectories(path); yield "true"; }
                case "list" -> { var path = resolvePath(base, p.get("path").getAsString()); try (var s = Files.list(path)) { yield gson.toJson(s.map(Path::toString).toList()); } }
                case "readBase64" -> { var path = resolvePath(base, p.get("path").getAsString()); yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(Files.readAllBytes(path)))); }
                case "writeBase64" -> { var path = resolvePath(base, p.get("path").getAsString()); assertNotRuntimeDir(path); Files.write(path, Base64.getDecoder().decode(p.get("data").getAsString())); yield "true"; }
                case "systemPaths" -> {
                    // 仅 outer 级：返回常用系统路径（桌面/临时目录/用户主目录）
                    if (!"outer".equals(level)) throw new IllegalArgumentException("systemPaths is outer-level only");
                    var home = System.getProperty("user.home", "");
                    yield gson.toJson(Map.of(
                        "home", home,
                        "desktop", Path.of(home, "Desktop").toString(),
                        "temp", System.getProperty("java.io.tmpdir", "")));
                }
                default -> throw new IllegalArgumentException("Unknown fs: " + task);
            };
        } catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }

    /** base 为 null（outer 级）时不限制范围；相对路径基于服务器根（工作目录）解析。 */
    private Path resolvePath(Path base, String userPath) throws IOException {
        var p = (base != null ? base.resolve(userPath) : Path.of(userPath)).normalize();
        if (base != null && !p.startsWith(base)) throw new SecurityException("Path traversal: " + userPath);
        Files.createDirectories(p.getParent()); return p;
    }

    private String handleHttp(String pld) {
        try {
            var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class); var t = obj.get("t").getAsString(); var p = obj.get("p").getAsJsonObject();
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
                            req.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                            var headers = new LinkedHashMap<String, String>();
                            exchange.getRequestHeaders().forEach((k,v) -> headers.put(k.toLowerCase(), String.join(", ", v)));
                            req.put("headers", headers);
                            httpPending.put(connId, new HttpConn(id, exchange));
                            queue.sendJs(gson.toJson(Map.of("t","cb","p",callbackId,"r",req)));
                        } catch (Exception ignored) {}
                    });
                    server.setExecutor(ioExecutor); server.start();
                    httpServers.put(id, server);
                    yield gson.toJson(Map.of("serverId", id, "port", server.getAddress().getPort()));
                }
                case "respond" -> {
                    var connId = p.get("connId").getAsString();
                    var conn = httpPending.remove(connId);
                    if (conn != null) {
                        try {
                            var status = p.has("status") ? p.get("status").getAsInt() : 200;
                            var body = p.has("body") ? p.get("body").getAsString() : "";
                            if (p.has("headers") && !p.get("headers").isJsonNull()) {
                                var hs = p.getAsJsonObject("headers");
                                hs.entrySet().forEach(e2 -> conn.exchange().getResponseHeaders().add(e2.getKey(), e2.getValue().getAsString()));
                            }
                            if (body.isEmpty()) {
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
                    var body = p.has("body") ? p.get("body").getAsString() : null;
                    var headers = p.has("headers") ? p.getAsJsonObject("headers") : new JsonObject();
                    var responseType = p.has("responseType") ? p.get("responseType").getAsString() : "text";
                    yield handleHttpRequest(url, method, body, headers, responseType);
                }
                case "requestAsync" -> {
                    var url = p.get("url").getAsString(); var method = p.has("method") ? p.get("method").getAsString() : "GET";
                    var body = p.has("body") ? p.get("body").getAsString() : null;
                    var headers = p.has("headers") ? p.getAsJsonObject("headers") : new JsonObject();
                    var responseType = p.has("responseType") ? p.get("responseType").getAsString() : "text";
                    var cb = p.get("cb").getAsString();
                    ioExecutor.submit(() -> { var result = handleHttpRequest(url, method, body, headers, responseType); queue.sendJs(yeow.channel.SyncCallbackHelper.cbMessage(cb, result)); });
                    yield null;
                }
                default -> gson.toJson(Map.of("err", "Unknown http op: " + t));
            };
        } catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }

    private String handleHttpRequest(String url, String method, String body, JsonObject headers, String responseType) {
        try {
            var uri = URI.create(url); var conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod(method.toUpperCase()); conn.setConnectTimeout(5000); conn.setReadTimeout(10000);
            if (headers != null) headers.entrySet().forEach(e -> conn.setRequestProperty(e.getKey(), e.getValue().getAsString()));
            if (body != null && ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))) {
                conn.setDoOutput(true); conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
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
        } catch (Exception e) { return gson.toJson(Map.of("error", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }

    private String handleAssets(String pld) {
        try {
            var obj = gson.fromJson(pld, JsonObject.class); var task = obj.get("t").getAsString(); var p = obj.get("p").getAsJsonObject();
            var rawPath = p.get("path").getAsString();
            var dest = p.has("dest") ? p.get("dest").getAsString() : (rawPath.startsWith("assets/") ? rawPath : "assets/" + rawPath);
            if (devAssetsDir != null) {
                var stripped = rawPath.startsWith("assets/") ? rawPath.substring("assets/".length()) : rawPath;
                var fp = Path.of(devAssetsDir, stripped);
                return switch (task) {
                    case "read" -> { if (!Files.exists(fp)) yield "null"; yield gson.toJson(Map.of("data", Files.readString(fp))); }
                    case "readBase64" -> { if (!Files.exists(fp)) yield "null"; yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(Files.readAllBytes(fp)))); }
                    case "extract" -> {
                        if (!Files.exists(fp)) yield gson.toJson(Map.of("err", "Asset not found: " + rawPath));
                        var target = resolvePath(Path.of("plugins", name), dest);
                        Files.copy(fp, target, StandardCopyOption.REPLACE_EXISTING);
                        yield gson.toJson(Map.of("path", target.toString()));
                    }
                    default -> gson.toJson(Map.of("err", "Unknown assets op: " + task));
                };
            }
            try (var zip = new ZipFile(jarPath)) {
                return switch (task) {
                    case "read" -> { var entry = zip.getEntry(rawPath); if (entry == null) yield "null"; yield gson.toJson(Map.of("data", new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))); }
                    case "readBase64" -> { var entry = zip.getEntry(rawPath); if (entry == null) yield "null"; yield gson.toJson(Map.of("data", Base64.getEncoder().encodeToString(zip.getInputStream(entry).readAllBytes()))); }
                    case "extract" -> {
                        var entry = zip.getEntry(rawPath);
                        if (entry == null || entry.isDirectory()) yield gson.toJson(Map.of("err", "Asset not found: " + rawPath));
                        var target = resolvePath(Path.of("plugins", name), dest);
                        Files.copy(zip.getInputStream(entry), target, StandardCopyOption.REPLACE_EXISTING);
                        yield gson.toJson(Map.of("path", target.toString()));
                    }
                    default -> gson.toJson(Map.of("err", "Unknown assets op: " + task));
                };
            }
        } catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }

    private void handleJSReport(String pld) {
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
                if (json.has("context") && !json.get("context").isJsonNull()) errPayload.put("context", json.get("context").getAsString());
                var stackToSend = (stack != null && !stack.isEmpty()) ? stack : msg + (fileName != null && line > 0 ? "\n    at " + fileName + ":" + line + ":" + col : "");
                errPayload.put("stack", stackToSend);
                errPayload.put("fileName", fileName); errPayload.put("lineNumber", line); errPayload.put("columnNumber", col);
                var rt = yeow.YeowRuntime.inst();
                if (rt != null) rt.sendDevMessage(gson.toJson(errPayload));
            }
            var log = org.bukkit.Bukkit.getLogger();
            var loc = fileName != null && line > 0 ? " at " + fileName + ":" + line + ":" + col : "";
            var sb = "[" + name + "] JS Error: " + msg + loc;
            if (stack != null && !stack.isEmpty()) {
                var limit = stack.lines().limit(3).collect(java.util.stream.Collectors.joining("\n[" + name + "]   "));
                sb += "\n[" + name + "]   " + limit;
            }
            log.warning(sb);
        } catch (Exception ex) { org.bukkit.Bukkit.getLogger().warning("[" + name + "] JS Error: " + pld); }
    }

    private String handleService(String pld) {
        try {
            var obj = gson.fromJson(pld.isEmpty() ? "{}" : pld, JsonObject.class);
            var t = obj.get("t").getAsString();
            var sm = yeow.YeowRuntime.inst().getServiceManager();
            return switch (t) {
                case "register" -> { var refName = obj.get("refName").getAsString(); var onReq = obj.get("onRequest").getAsString(); var isPublic = obj.has("public") && obj.get("public").getAsBoolean(); yield sm.registerPluginService(refName, name, onReq, isPublic); }
                case "registerNative" -> { var refName = obj.get("refName").getAsString(); var platforms = obj.getAsJsonObject("platforms"); var isPublic = obj.has("public") && obj.get("public").getAsBoolean(); yield sm.registerNativeService(refName, name, platforms, isPublic, jarPath, devAssetsDir, nativeHashes); }
                case "registerNativeTerminate" -> { var svcId = obj.get("serviceId").getAsString(); var cbId = obj.get("cb").getAsString(); sm.registerTerminateCb(svcId, cbId, name); yield "true"; }
                case "request" -> { var svcId = obj.get("serviceId").getAsString(); var path = obj.has("path") ? obj.get("path").getAsString() : "/"; var body = obj.has("body") ? obj.getAsJsonObject("body") : new JsonObject(); var reqId = obj.get("requestId").getAsString(); sm.trackRequestConsumer(reqId, name, svcId); sm.request(svcId, path, body, reqId, name); yield null; }
                case "awaitReady" -> { var svcId = obj.get("serviceId").getAsString(); var cbId = obj.get("cb").getAsString(); sm.awaitReady(svcId, cbId, name); yield null; }
                case "response" -> { var reqId = obj.get("requestId").getAsString(); var result = obj.has("body") ? gson.fromJson(obj.get("body").toString(), Object.class) : null; sm.respond(reqId, name, result); yield null; }
                case "subscribe" -> { var svcId = obj.get("serviceId").getAsString(); var eventPath = obj.get("eventPath").getAsString(); var cbId = obj.get("cb").getAsString(); sm.subscribe(svcId, eventPath, cbId, name); yield "true"; }
                case "unsubscribe" -> { var svcId = obj.get("serviceId").getAsString(); var eventPath = obj.get("eventPath").getAsString(); sm.unsubscribe(svcId, eventPath, name); yield "true"; }
                case "publish" -> { var token = obj.get("token").getAsString(); var eventPath = obj.get("eventPath").getAsString(); var body = obj.has("body") ? obj.getAsJsonObject("body") : new JsonObject(); sm.publish(token, eventPath, body); yield "true"; }
                default -> gson.toJson(Map.of("err", "Unknown service op: " + t));
            };
        } catch (Exception e) { return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString())); }
    }

    private void handleJSError(QuickJSException e) {
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
                var stackToSend = (stack != null && !stack.isEmpty()) ? stack : (msg != null ? msg : "") + (fileName != null && line > 0 ? "\n    at " + fileName + ":" + line + ":" + col : "");
                errPayload.put("stack", stackToSend);
                errPayload.put("fileName", fileName != null ? fileName : "main.js");
                errPayload.put("lineNumber", line); errPayload.put("columnNumber", col);
                var rt = yeow.YeowRuntime.inst();
                if (rt != null) rt.sendDevMessage(gson.toJson(errPayload));
            }
            var log = org.bukkit.Bukkit.getLogger();
            var loc = fileName != null && line > 0 ? " at " + fileName + ":" + line + ":" + col : "";
            var sb = "[" + name + "] JS Error: " + (msg != null ? msg : "unknown") + loc;
            if (stack != null && !stack.isEmpty()) {
                var limit = stack.lines().limit(3).collect(java.util.stream.Collectors.joining("\n[" + name + "]   "));
                sb += "\n[" + name + "]   " + limit;
            }
            log.warning(sb);
        } catch (Exception ignored) { org.bukkit.Bukkit.getLogger().warning("[" + name + "] JS Error: " + e.getMessage()); }
    }
}
