package yeow.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import yeow.PluginEntity;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

public class ServiceManager {
    private static final Logger LOG = Logger.getLogger("Yeow");
    private static final Gson gson = new Gson();

    /** 插件注册表查找（注入，避免依赖运行时静态单例）。 */
    private final Function<String, PluginEntity> pluginLookup;

    private final ConcurrentHashMap<String, ServiceEntry> registry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<Subscription>> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tokens = new ConcurrentHashMap<>(); // token → serviceId
    private final ConcurrentHashMap<String, List<PendingReady>> pendingReady = new ConcurrentHashMap<>();

    private ServerSocket tcpServer;
    private int nativePort;
    private final ExecutorService nativeExecutor = Executors.newCachedThreadPool(
        r -> new Thread(r, "yeow-native-io"));
    /** service 请求超时调度（每个挂起请求一个定时器）。 */
    private final java.util.concurrent.ScheduledExecutorService timeoutScheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "yeow-service-timeout"); t.setDaemon(true); return t;
        });

    private static final Path SVC_TEMP = Path.of(System.getProperty("java.io.tmpdir", "/tmp"), "yeow-native-services");

    public ServiceManager(Function<String, PluginEntity> pluginLookup) {
        this.pluginLookup = pluginLookup;
    }

    // ── Init / Shutdown ───────────────────────────────────────────

    public int start() throws IOException {
        tcpServer = new ServerSocket(0);
        nativePort = tcpServer.getLocalPort();
        var t = new Thread(this::acceptNativeConnections, "yeow-native-accept");
        t.setDaemon(true);
        t.start();
        LOG.info("Native service TCP server on port " + nativePort);
        // Clean temp dir from previous runs
        try { if (Files.exists(SVC_TEMP)) { try (var walk = java.nio.file.Files.walk(SVC_TEMP).sorted(java.util.Comparator.reverseOrder())) { walk.forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} }); } } } catch (Exception ignored) {}
        return nativePort;
    }

    public void shutdown() {
        try { if (tcpServer != null) tcpServer.close(); } catch (Exception ignored) {}
        nativeExecutor.shutdownNow();
        timeoutScheduler.shutdownNow();
        pendingReady.forEach((svcId, list) -> {
            for (var p : list) respond(p.cbId(), p.pluginName(), Map.of("err", "Yeow runtime shutting down"));
        });
        pendingReady.clear();
        registry.values().forEach(e -> {
            if (e.type == Type.NATIVE) {
                stopNative(e, "shutdown");
                notifyTerminated(e, "shutdown");
                deleteServiceDir(e.serviceId);
            }
        });
        registry.clear();
        subscriptions.clear();
        tokens.clear();
    }

    /**
     * 优雅停止原生服务：通过 TCP 推送 shutdown 消息，等待子进程自行清理资源并退出；
     * 超时（3s + 3s）后 destroy → destroyForcibly 兜底。
     */
    private void stopNative(ServiceEntry e, String reason) {
        if (e.nativeProc == null) return;
        if (e.nativeSocket != null && !e.nativeSocket.isClosed() && e.nativeOut != null) {
            try {
                writeNative(e, gson.toJson(Map.of("type", "shutdown", "reason", reason)), new byte[0]);
                if (e.nativeProc.waitFor(3, TimeUnit.SECONDS)) return;
                e.nativeProc.destroy();
                if (e.nativeProc.waitFor(3, TimeUnit.SECONDS)) return;
            } catch (Exception ignored) { /* 落到底部强制终止 */ }
        }
        if (e.nativeProc.isAlive()) e.nativeProc.destroyForcibly();
    }

    /** 向原生服务写入一条帧消息（每连接单写锁：杜绝并发请求交错损坏帧）。 */
    private static void writeNative(ServiceEntry e, String header, byte[] body) throws IOException {
        synchronized (e.nativeWriteLock) {
            NativeProtocol.write(e.nativeOut, header, body);
        }
    }

    public void purgePluginServices(String pluginName) {
        var toRemove = new ArrayList<String>();
        registry.forEach((id, e) -> {
            if (pluginName.equals(e.ownerPlugin)) toRemove.add(id);
        });
        for (var id : toRemove) {
            var e = registry.remove(id);
            if (e != null) {
                tokens.remove(e.token);
                if (e.type == Type.NATIVE) {
                    var pendings = pendingReady.remove(id);
                    if (pendings != null) {
                        for (var p : pendings) respond(p.cbId(), p.pluginName(), Map.of("err", "Service " + id + " unregistered"));
                    }
                    stopNative(e, "unregistered");
                    notifyTerminated(e, "unregistered");
                    deleteServiceDir(id);
                }
            }
            // Drop stale subscriptions of other plugins to the removed service.
            subscriptions.remove(id);
        }
        // Drop subscriptions where the unloaded plugin is the subscriber.
        subscriptions.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(s -> pluginName.equals(s.subscriberPlugin));
            return entry.getValue().isEmpty();
        });
        // Drop the plugin's own pending native-service requests (as consumer) and ready waits.
        requestConsumers.entrySet().removeIf(e -> {
            if (pluginName.equals(e.getValue().consumerPlugin())) {
                if (e.getValue().timeout != null) e.getValue().timeout.cancel(false);
                return true;
            }
            return false;
        });
        pendingReady.forEach((svcId, list) -> list.removeIf(p -> pluginName.equals(p.pluginName())));
    }

    public int getNativePort() { return nativePort; }

    // ── Registration ──────────────────────────────────────────────

    public String registerPluginService(String refName, String pluginName, String onRequestCb, boolean isPublic) {
        var id = allocateId(refName, isPublic);
        if (isPublic && registry.containsKey(id)) {
            return gson.toJson(Map.of("err", "Service already registered: " + id, "serviceId", id));
        }
        var token = "tok_" + randomHex(8);
        var entry = new ServiceEntry(id, token, Type.PLUGIN, pluginName, onRequestCb);
        registry.put(id, entry);
        tokens.put(token, id);
        LOG.info("Plugin service registered: " + id + " (" + pluginName + ")");
        return gson.toJson(Map.of("serviceId", id, "token", token));
    }

    public String registerNativeService(String refName, String pluginName, JsonObject platforms, boolean isPublic, yeow.PluginPackage pkg, String jarPath, String devAssetsDir, yeow.NativeManifest natives) {
        var id = allocateId(refName, isPublic);
        if (isPublic && registry.containsKey(id)) {
            return gson.toJson(Map.of("err", "Service already registered: " + id, "serviceId", id));
        }

        // 强制声明（1）：serviceId 必须在插件包的 native 清单中（构建时由 yeow.config.json 声明）。
        if (natives == null || !natives.declaresService(refName)) {
            return gson.toJson(Map.of("err", "Native service '" + refName + "' is not declared in the package's `native` manifest"
                + " - declare it in yeow.config.json and rebuild"));
        }

        var osKey = System.getProperty("os.name").toLowerCase();
        String os;
        if (osKey.contains("win")) os = "windows";
        else if (osKey.contains("mac")) os = "macos";
        else os = "linux";

        var archRaw = System.getProperty("os.arch").toLowerCase();
        String arch;
        if (archRaw.contains("aarch64") || archRaw.contains("arm64")) arch = "arm64";
        else if (archRaw.contains("x86_64") || archRaw.contains("amd64")) arch = "x64";
        else if (archRaw.contains("arm")) arch = "armv7";
        else arch = archRaw;

        // 精确匹配 <os>-<arch>，回退到 <os>
        var platformEl = platforms.get(os + "-" + arch);
        if (platformEl == null) platformEl = platforms.get(os);
        if (platformEl == null) return gson.toJson(Map.of("err", "No binary for platform: " + os + " (" + os + "-" + arch + ")"));

        // 强制声明（2）：目录模式（{dir, entry}）无法按单文件固定哈希 → 拒绝。
        var packagedPath = packagedPathFor(platformEl);
        if (packagedPath == null) {
            return gson.toJson(Map.of("err", "Unsupported native platform config (directory mode {dir, entry} was removed)"
                + " - use single-file mode (string / {file}) so the binary is pinned by SHA-256"));
        }
        // 强制声明（3）：二进制打包路径必须在 native 清单中（否则运行时无从校验）。
        var expected = natives.hashFor(packagedPath);
        if (expected == null) {
            return gson.toJson(Map.of("err", "Native binary '" + packagedPath + "' is not declared in the package's `native` manifest"
                + " - declare it in yeow.config.json and rebuild"));
        }

        try {
            var svcDir = SVC_TEMP.resolve(id);
            cleanDir(svcDir);
            Files.createDirectories(svcDir);

            var execFile = extractNativeBinary(platformEl, svcDir, pkg, jarPath, devAssetsDir);
            if (execFile == null) return gson.toJson(Map.of("err", "Failed to extract native binary"));

            // 可信性校验：声明（serviceId + 打包路径，见方法开头）已强制；此处校验 SHA-256 必须匹配。
            var actual = sha256(execFile.toFile());
            if (!expected.equalsIgnoreCase(actual)) {
                LOG.severe("Native service " + id + " refused: SHA-256 mismatch for " + packagedPath
                    + " (declared " + expected + ", actual " + actual + ")");
                cleanDir(svcDir);
                return gson.toJson(Map.of("err", "Native service hash mismatch for " + packagedPath
                    + " - refused to load (plugin '" + pluginName + "' declares a different SHA-256;"
                    + " the executable may have been tampered with)"));
            }
            LOG.info("Native service " + id + ": SHA-256 verified (" + packagedPath + ")");

            execFile.toFile().setExecutable(true);

            var pb = new ProcessBuilder(execFile.toAbsolutePath().toString(), String.valueOf(nativePort), id);
            // 工作目录默认位于服务器根目录（Java 进程工作目录），便于子进程使用相对路径读写服务器文件
            pb.directory(new File(System.getProperty("user.dir", ".")));
            pb.redirectErrorStream(true);
            var proc = pb.start();

            var entry2 = new ServiceEntry(id, "", Type.NATIVE, pluginName, null);
            entry2.nativeProc = proc;
            entry2.binaryPath = execFile;
            registry.put(id, entry2);

            var outputSb = new StringBuilder();
            entry2.outputLog = outputSb;
            nativeExecutor.submit(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) outputSb.append(line).append("\n");
                } catch (Exception ignored) {}
                try { proc.waitFor(); } catch (InterruptedException ignored) {}
                notifyTerminated(entry2, "exited");
                if (entry2.nativeSocket == null) {
                    var pendings = pendingReady.remove(id);
                    if (pendings != null) {
                        var exitCode = proc.exitValue();
                        var output = outputSb.length() > 0 ? outputSb.toString().trim() : "";
                        var errObj = new LinkedHashMap<String, Object>();
                        errObj.put("message", "Native service " + id + " exited with code " + exitCode);
                        errObj.put("output", output);
                        errObj.put("exitCode", exitCode);
                        for (var p : pendings) respond(p.cbId(), p.pluginName(), Map.of("err", errObj));
                    }
                }
                if (proc.exitValue() != 0) {
                    var errMsg = "Native service " + id + " exited with code " + proc.exitValue();
                    if (outputSb.length() > 0) errMsg += "\n  output: " + outputSb.toString().trim().replace("\n", "\n  ");
                    LOG.warning(errMsg);
                }
            });

            LOG.info("Native service spawned: " + id + " (pid " + proc.pid() + ")");

            return gson.toJson(Map.of("serviceId", id));
        } catch (Exception e) {
            return gson.toJson(Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /**
     * 计算 platform 配置对应的打包后路径（getAssetsPath 结果，`assets/<id>/...`）：
     * `string` / `{file}` → 值本身；其他（目录模式已移除）→ null。
     * 与构建器 native manifest 中的 key 对齐。
     */
    private static String packagedPathFor(JsonElement platformEl) {
        if (platformEl.isJsonPrimitive()) return platformEl.getAsString();
        if (platformEl.isJsonObject() && platformEl.getAsJsonObject().has("file")) return platformEl.getAsJsonObject().get("file").getAsString();
        return null; // 其他配置形态：拒绝（见 registerNativeService）
    }

    /** 文件 SHA-256（hex）。 */
    private static String sha256(File f) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            try (var in = new java.io.FileInputStream(f)) {
                var buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            var sb = new StringBuilder();
            for (var b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 提取单文件原生二进制（`string` / `{file}`）到服务临时目录；目录模式（`{dir, entry}`）已移除。
     * 平台配置非法或文件缺失返回 null。
     */
    private static Path extractNativeBinary(JsonElement platformEl, Path svcDir, yeow.PluginPackage pkg, String jarPath, String devAssetsDir) throws Exception {
        String extractFile;
        if (platformEl.isJsonPrimitive()) {
            extractFile = platformEl.getAsString();
        } else if (platformEl.isJsonObject() && platformEl.getAsJsonObject().has("file")) {
            extractFile = platformEl.getAsJsonObject().get("file").getAsString();
        } else {
            return null; // 目录模式等已不再支持（见 packagedPathFor / registerNativeService）
        }

        var fileName = new java.io.File(extractFile).getName();
        if (devAssetsDir != null) {
            var rel = extractFile.startsWith("assets/") ? extractFile.substring("assets/".length()) : extractFile;
            var devPath = Path.of(devAssetsDir, rel);
            if (Files.exists(devPath)) {
                Files.copy(devPath, svcDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                return svcDir.resolve(fileName);
            }
        }
        // 优先走加载时预解析的内存包；缓存关闭/加载失败时回退 ZipFile 直读
        if (pkg != null) {
            var b = pkg.read(extractFile);
            if (b == null) return null;
            Files.write(svcDir.resolve(fileName), b, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return svcDir.resolve(fileName);
        }
        try (var zip = new ZipFile(jarPath)) {
            var ze = zip.getEntry(extractFile);
            if (ze == null) return null;
            Files.copy(zip.getInputStream(ze), svcDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        }
        return svcDir.resolve(fileName);
    }

    private static void cleanDir(Path dir) {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir).sorted(Comparator.reverseOrder())) {
                walk.forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            } catch (Exception ignored) {}
        }
    }

    /** Delete the extracted binary directory of a native service. */
    private void deleteServiceDir(String serviceId) {
        try { cleanDir(SVC_TEMP.resolve(serviceId)); } catch (Exception ignored) {}
    }

    // ── Request ───────────────────────────────────────────────────

    /**
     * 向服务发起请求。headers 为 app 级键值对（`content-type` 为典型项），
     * body 为 raw 字节（JS 侧 JSON 已序列化、二进制 base64 解码）。
     */
    public void request(String serviceId, String path, JsonObject headers, String contentType, byte[] body, String requestId, String consumerPlugin) {
        var entry = registry.get(serviceId);
        if (entry == null) {
            respondError(requestId, consumerPlugin, "Service not found: " + serviceId);
            return;
        }
        var h = normalizeHeaders(headers, contentType);
        var ct = h.get("content-type").getAsString();
        if (entry.type == Type.PLUGIN) {
            requestPlugin(entry, path, h, ct, body, requestId, consumerPlugin);
        } else {
            requestNative(entry, path, h, ct, body, requestId, consumerPlugin);
        }
    }

    private void requestPlugin(ServiceEntry entry, String path, JsonObject headers, String contentType, byte[] body, String requestId, String consumerPlugin) {
        var pt = pluginLookup.apply(entry.ownerPlugin);
        if (pt == null) {
            respondError(requestId, consumerPlugin, "Service owner plugin not running: " + entry.ownerPlugin);
            return;
        }
        // 插件服务是 JS：JSON body 以原生值投递；二进制以 {contentType, base64} 承载（JS 桥为 JSON）。
        var r = new JsonObject();
        r.addProperty("_svc", "request");
        r.addProperty("requestId", requestId);
        r.addProperty("consumer", consumerPlugin);
        r.addProperty("path", path);
        r.add("headers", headers);
        if (isJson(contentType)) {
            if (body != null && body.length > 0) {
                try { r.add("body", com.google.gson.JsonParser.parseString(new String(body, StandardCharsets.UTF_8))); }
                catch (Exception e) { r.add("body", com.google.gson.JsonNull.INSTANCE); }
            } else {
                r.add("body", com.google.gson.JsonNull.INSTANCE);
            }
        } else {
            var b = new JsonObject();
            b.addProperty("contentType", contentType);
            b.addProperty("base64", java.util.Base64.getEncoder().encodeToString(body == null ? new byte[0] : body));
            r.add("body", b);
        }
        // 所有者插件的 onRequestCb 收到请求，经 service.response 回投（原始对象 → JS 线程编码）
        pt.postMessage(yeow.channel.SyncCallbackHelper.cbMessageObject(entry.onRequestCb, r));
    }

    private void requestNative(ServiceEntry entry, String path, JsonObject headers, String contentType, byte[] body, String requestId, String consumerPlugin) {
        if (entry.nativeSocket == null || entry.nativeSocket.isClosed()) {
            respondError(requestId, consumerPlugin, "Native service not ready: " + entry.serviceId);
            return;
        }
        try {
            var hdr = new JsonObject();
            hdr.addProperty("type", "request");
            hdr.addProperty("id", requestId);
            hdr.addProperty("path", path);
            hdr.add("headers", headers);
            hdr.addProperty("contentType", contentType);
            writeNative(entry, gson.toJson(hdr), body);
        } catch (Exception e) {
            respondError(requestId, consumerPlugin, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /** 归一化 headers：确保 `content-type` 存在（contentType 作为便捷别名）。 */
    private static JsonObject normalizeHeaders(JsonObject headers, String contentType) {
        var h = headers != null ? headers.deepCopy() : new JsonObject();
        if (!h.has("content-type") && contentType != null && !contentType.isEmpty()) h.addProperty("content-type", contentType);
        if (!h.has("content-type")) h.addProperty("content-type", "application/json");
        return h;
    }

    private static boolean isJson(String contentType) {
        return contentType == null || contentType.isEmpty() || contentType.startsWith("application/json");
    }

    // ── Await Ready ──────────────────────────────────────────────

    public void awaitReady(String serviceId, String cbId, String pluginName) {
        var entry = registry.get(serviceId);
        if (entry == null) {
            respond(cbId, pluginName, Map.of("err", "Service not found: " + serviceId));
            return;
        }
        if (entry.type != Type.NATIVE) {
            respond(cbId, pluginName, Map.of("err", "Not a native service: " + serviceId));
            return;
        }
        if (entry.nativeSocket != null && !entry.nativeSocket.isClosed()) {
            respond(cbId, pluginName, Map.of("ok", true));
            return;
        }
        if (entry.nativeSocket != null) {
            respond(cbId, pluginName, Map.of("err", "Service " + serviceId + " disconnected"));
            return;
        }
        if (entry.nativeProc != null && !entry.nativeProc.isAlive()) {
            var errObj = new LinkedHashMap<String, Object>();
            errObj.put("message", "Native service " + serviceId + " exited with code " + entry.nativeProc.exitValue());
            var output = entry.outputLog != null && entry.outputLog.length() > 0
                ? entry.outputLog.toString().trim() : "";
            errObj.put("output", output);
            errObj.put("exitCode", entry.nativeProc.exitValue());
            respond(cbId, pluginName, Map.of("err", errObj));
            return;
        }
        pendingReady.computeIfAbsent(serviceId, k -> new ArrayList<>())
            .add(new PendingReady(cbId, pluginName));
    }

    // ── Response (called by plugin service owner via $_send service.response) ──

    public void respond(String requestId, String consumerPlugin, Object result) {
        if (consumerPlugin == null) return;
        var pt = pluginLookup.apply(consumerPlugin);
        if (pt != null) {
            pt.postMessage(yeow.channel.SyncCallbackHelper.cbMessageObject(requestId, result));
        }
    }

    private void respondError(String requestId, String consumerPlugin, String msg) {
        respond(requestId, consumerPlugin, Map.of("err", msg));
    }

    /**
     * 投递**请求响应体**给 JS 消费者：body 以 base64 承载（JS 桥为 JSON），
     * 由 JS 侧构造 fetch 风格 Response（json()/text()/bytes()/...）。
     */
    public void respondBody(String requestId, String consumerPlugin, JsonObject headers, String contentType, byte[] body) {
        if (consumerPlugin == null) return;
        var pt = pluginLookup.apply(consumerPlugin);
        if (pt == null) return;
        var h = normalizeHeaders(headers, contentType);
        var r = new JsonObject();
        r.add("headers", h);
        r.addProperty("contentType", h.get("content-type").getAsString());
        r.addProperty("base64", java.util.Base64.getEncoder().encodeToString(body == null ? new byte[0] : body));
        pt.postMessage(yeow.channel.SyncCallbackHelper.cbMessageObject(requestId, r));
    }

    /**
     * 解析 JS service 载荷中的 body 为原始字节：`bodyEncoding: "base64"` → 解码；
     * 否则把 JSON 值序列化为 UTF-8 文本；缺省 → 空。
     */
    public static byte[] bodyBytes(JsonObject p) {
        if (p == null || !p.has("body") || p.get("body").isJsonNull()) return new byte[0];
        if (p.has("bodyEncoding") && "base64".equals(p.get("bodyEncoding").getAsString())) {
            try { return java.util.Base64.getDecoder().decode(p.get("body").getAsString()); }
            catch (Exception e) { return new byte[0]; }
        }
        return gson.toJson(p.get("body")).getBytes(StandardCharsets.UTF_8);
    }

    // ── Subscribe / Unsubscribe ────────────────────────────────────

    public void subscribe(String serviceId, String eventPath, String cbId, String pluginName) {
        subscriptions.computeIfAbsent(serviceId, k -> ConcurrentHashMap.newKeySet())
            .add(new Subscription(pluginName, eventPath, cbId));
    }

    public void unsubscribe(String serviceId, String eventPath, String pluginName) {
        var set = subscriptions.get(serviceId);
        if (set != null) {
            set.removeIf(s -> s.subscriberPlugin.equals(pluginName) && s.eventPath.equals(eventPath));
        }
    }

    public void unsubscribeAll(String pluginName) {
        subscriptions.forEach((svcId, set) -> set.removeIf(s -> s.subscriberPlugin.equals(pluginName)));
    }

    // ── Publish ────────────────────────────────────────────────────

    public void publish(String token, String eventPath, JsonObject body) {
        var serviceId = tokens.get(token);
        if (serviceId == null) return;
        publishByService(serviceId, eventPath, body);
    }

    private void publishByService(String serviceId, String eventPath, JsonObject body) {
        var set = subscriptions.get(serviceId);
        if (set == null) return;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("serviceId", serviceId);
        payload.put("eventPath", eventPath);
        payload.put("body", body != null ? gson.fromJson(body.toString(), Object.class) : null);
        for (var sub : set) {
            if (!eventPath.equals(sub.eventPath)) continue;
            var pt = pluginLookup.apply(sub.subscriberPlugin);
            if (pt != null) {
                pt.postMessage(yeow.channel.SyncCallbackHelper.cbMessageObject(sub.subscriberCb, payload));
            }
        }
    }

    // ── Native TCP Server ──────────────────────────────────────────

    private void acceptNativeConnections() {
        while (!tcpServer.isClosed()) {
            try {
                var socket = tcpServer.accept();
                nativeExecutor.submit(() -> handleNativeSocket(socket));
            } catch (Exception e) {
                if (!tcpServer.isClosed()) LOG.warning("Native accept: " + e.getMessage());
            }
        }
    }

    private void handleNativeSocket(Socket socket) {
        ServiceEntry entry = null;
        try {
            socket.setSoTimeout(5000);
            var in = socket.getInputStream();
            var out = socket.getOutputStream();

            // 首条消息必须是 ready（同一帧协议）
            var first = NativeProtocol.read(in);
            if (first == null) { socket.close(); return; }
            var ready = gson.fromJson(first.header(), JsonObject.class);
            if (ready == null || !ready.has("serviceId")) { socket.close(); return; }
            var serviceId = ready.get("serviceId").getAsString();
            var servicePort = ready.has("servicePort") ? ready.get("servicePort").getAsInt() : 0;

            entry = registry.get(serviceId);
            if (entry == null || entry.type != Type.NATIVE) { socket.close(); return; }
            entry.nativeSocket = socket;
            entry.nativeOut = out;
            socket.setSoTimeout(0); // 空闲消息循环

            LOG.info("Native service ready: " + serviceId + " on port " + servicePort);

            var pendings = pendingReady.remove(serviceId);
            if (pendings != null) {
                for (var p : pendings) respond(p.cbId(), p.pluginName(), Map.of("ok", true));
            }

            // 后续消息循环（帧协议：header JSON + raw/分块 body）
            NativeProtocol.Message msg;
            while ((msg = NativeProtocol.read(in)) != null) {
                try {
                    var header = gson.fromJson(msg.header(), JsonObject.class);
                    var type = header.get("type").getAsString();
                    if ("response".equals(type)) {
                        var id = header.get("id").getAsString();
                        var headers = header.has("headers") && header.get("headers").isJsonObject() ? header.getAsJsonObject("headers") : new JsonObject();
                        var ct = header.has("contentType") ? header.get("contentType").getAsString()
                            : (headers.has("content-type") ? headers.get("content-type").getAsString() : "application/octet-stream");
                        var pr = findConsumerForRequest(id);
                        if (pr != null) respondBody(id, pr.consumerPlugin(), headers, ct, msg.body());
                    } else if ("publish".equals(type)) {
                        var eventPath = header.get("eventPath").getAsString();
                        JsonObject body;
                        try { body = gson.fromJson(new String(msg.body(), StandardCharsets.UTF_8), JsonObject.class); }
                        catch (Exception e) { body = new JsonObject(); }
                        publishByService(entry.serviceId, eventPath, body);
                    }
                } catch (Exception ignored) {}
            }
            // Connection closed - mark service disconnected and fail pending requests.
            markDisconnected(entry, socket);
        } catch (Exception e) {
            LOG.warning("Native socket: " + e.getMessage());
            markDisconnected(entry, socket);
        }
    }

    private void markDisconnected(ServiceEntry entry, Socket socket) {
        if (entry == null || entry.type != Type.NATIVE || entry.nativeSocket != socket) return;
        entry.nativeSocket = null;
        entry.nativeOut = null;
        notifyTerminated(entry, "disconnected");
    }

    // ── Helpers ────────────────────────────────────────────────────

    private final ConcurrentHashMap<String, PendingRequest> requestConsumers = new ConcurrentHashMap<>();

    public void trackRequestConsumer(String requestId, String consumerPlugin, String serviceId, long timeoutMs) {
        var pr = new PendingRequest(consumerPlugin, serviceId);
        if (timeoutMs > 0) {
            pr.timeout = timeoutScheduler.schedule(() -> {
                if (requestConsumers.remove(requestId, pr)) {
                    respondError(requestId, consumerPlugin, "Service request timed out after " + timeoutMs + "ms: " + serviceId);
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
        }
        requestConsumers.put(requestId, pr);
    }

    private PendingRequest findConsumerForRequest(String requestId) {
        var pr = requestConsumers.remove(requestId);
        if (pr != null && pr.timeout != null) pr.timeout.cancel(false);
        return pr;
    }

    /**
     * Fail all in-flight requests of a service and notify the owner plugin's onTerminate hook (once).
     * Called on native socket disconnect, process exit, service unregister and runtime shutdown.
     */
    private void notifyTerminated(ServiceEntry e, String reason) {
        if (!e.terminated.compareAndSet(false, true)) return;
        failPendingRequests(e.serviceId, reason);
        var cb = e.terminateCb;
        if (cb == null || cb.isEmpty()) return;
        var pt = pluginLookup.apply(e.ownerPlugin);
        if (pt == null) return;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("serviceId", e.serviceId);
        payload.put("reason", reason);
        if (e.nativeProc != null) {
            try { if (!e.nativeProc.isAlive()) payload.put("exitCode", e.nativeProc.exitValue()); } catch (Exception ignored) {}
        }
        if (e.outputLog != null && e.outputLog.length() > 0) payload.put("output", e.outputLog.toString().trim());
        pt.postMessage(yeow.channel.SyncCallbackHelper.cbMessageObject(cb, payload));
    }

    private void failPendingRequests(String serviceId, String reason) {
        requestConsumers.forEach((reqId, pr) -> {
            if (serviceId.equals(pr.serviceId()) && requestConsumers.remove(reqId, pr)) {
                if (pr.timeout != null) pr.timeout.cancel(false);
                respond(reqId, pr.consumerPlugin(), Map.of("err", "Native service " + serviceId + " terminated (" + reason + ")"));
            }
        });
    }

    /** 查询服务是否存在及其类型：`{exists, kind}`（kind 为 `"plugin"`/`"native"`/null）。 */
    public JsonObject serviceInfo(String serviceId) {
        var e = registry.get(serviceId);
        var out = new JsonObject();
        out.addProperty("exists", e != null);
        out.addProperty("kind", e == null ? null : (e.type == Type.NATIVE ? "native" : "plugin"));
        return out;
    }

    /**
     * 卸载单个服务：Plugin Service 校验 owner 的 token；Native Service 校验调用方为属主。
     * 返回 `{"ok":true}` 或 `{"err":...}`。
     */
    public String unregisterService(String serviceId, String token, String callerPlugin) {
        var entry = registry.get(serviceId);
        if (entry == null) return gson.toJson(Map.of("err", "Service not found: " + serviceId));
        if (entry.type == Type.PLUGIN) {
            if (token == null || !token.equals(entry.token)) {
                return gson.toJson(Map.of("err", "Permission denied: unregister requires the owner token for plugin service " + serviceId));
            }
        } else if (callerPlugin == null || !callerPlugin.equals(entry.ownerPlugin)) {
            return gson.toJson(Map.of("err", "Permission denied: only the owner may unregister native service " + serviceId));
        }
        removeService(entry);
        return gson.toJson(Map.of("ok", true));
    }

    private void removeService(ServiceEntry e) {
        registry.remove(e.serviceId, e);
        tokens.remove(e.token);
        subscriptions.remove(e.serviceId);
        if (e.type == Type.NATIVE) {
            var pendings = pendingReady.remove(e.serviceId);
            if (pendings != null) {
                for (var p : pendings) respond(p.cbId(), p.pluginName(), Map.of("err", "Service " + e.serviceId + " unregistered"));
            }
            stopNative(e, "unregistered");
            notifyTerminated(e, "unregistered");
            deleteServiceDir(e.serviceId);
        } else {
            failPendingRequests(e.serviceId, "unregistered");
        }
    }

    public void registerTerminateCb(String serviceId, String cbId, String pluginName) {
        var e = registry.get(serviceId);
        if (e != null && e.type == Type.NATIVE && pluginName.equals(e.ownerPlugin)) e.terminateCb = cbId;
    }

    private String allocateId(String refName, boolean isPublic) {
        if (isPublic) return refName;
        return refName + "_" + randomHex(4);
    }

    private static String randomHex(int len) {
        var sb = new StringBuilder();
        var r = ThreadLocalRandom.current();
        for (int i = 0; i < len; i++) sb.append(Integer.toHexString(r.nextInt(16)));
        return sb.toString();
    }

    // ── Inner types ────────────────────────────────────────────────

    enum Type { PLUGIN, NATIVE }

    static class ServiceEntry {
        final String serviceId;
        final String token;
        final Type type;
        final String ownerPlugin;
        final String onRequestCb; // PLUGIN only
        volatile String terminateCb; // NATIVE only - onTerminate hook
        final java.util.concurrent.atomic.AtomicBoolean terminated = new java.util.concurrent.atomic.AtomicBoolean(false);
        /** 每连接单写锁：所有出站帧消息串行写出，杜绝并发交错损坏帧。 */
        final Object nativeWriteLock = new Object();
        Process nativeProc;
        Socket nativeSocket;
        OutputStream nativeOut;
        java.nio.file.Path binaryPath;
        StringBuilder outputLog;

        ServiceEntry(String id, String tok, Type t, String owner, String cb) {
            this.serviceId = id; this.token = tok; this.type = t; this.ownerPlugin = owner; this.onRequestCb = cb;
        }
    }

    record Subscription(String subscriberPlugin, String eventPath, String subscriberCb) {}
    record PendingReady(String cbId, String pluginName) {}
    static final class PendingRequest {
        private final String consumerPlugin;
        private final String serviceId;
        volatile ScheduledFuture<?> timeout;
        PendingRequest(String consumerPlugin, String serviceId) { this.consumerPlugin = consumerPlugin; this.serviceId = serviceId; }
        String consumerPlugin() { return consumerPlugin; }
        String serviceId() { return serviceId; }
    }
}
