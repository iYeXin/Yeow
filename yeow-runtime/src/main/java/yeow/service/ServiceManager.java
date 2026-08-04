package yeow.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import yeow.PluginEntity;
import yeow.YeowRuntime;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

public class ServiceManager {
    private static final Logger LOG = Logger.getLogger("Yeow");
    private static final Gson gson = new Gson();

    private final ConcurrentHashMap<String, ServiceEntry> registry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<Subscription>> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tokens = new ConcurrentHashMap<>(); // token → serviceId
    private final ConcurrentHashMap<String, List<PendingReady>> pendingReady = new ConcurrentHashMap<>();

    private ServerSocket tcpServer;
    private int nativePort;
    private final ExecutorService nativeExecutor = Executors.newCachedThreadPool(
        r -> new Thread(r, "yeow-native-io"));

    private static final Path SVC_TEMP = Path.of(System.getProperty("java.io.tmpdir", "/tmp"), "yeow-native-services");

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
                e.nativeOut.write(gson.toJson(Map.of("type", "shutdown", "reason", reason)).getBytes(StandardCharsets.UTF_8));
                e.nativeOut.flush();
                if (e.nativeProc.waitFor(3, TimeUnit.SECONDS)) return;
                e.nativeProc.destroy();
                if (e.nativeProc.waitFor(3, TimeUnit.SECONDS)) return;
            } catch (Exception ignored) { /* 落到底部强制终止 */ }
        }
        if (e.nativeProc.isAlive()) e.nativeProc.destroyForcibly();
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
        requestConsumers.entrySet().removeIf(e -> pluginName.equals(e.getValue().consumerPlugin()));
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

    public String registerNativeService(String refName, String pluginName, JsonObject platforms, boolean isPublic, String jarPath, String devAssetsDir, Map<String, String> nativeHashes) {
        var id = allocateId(refName, isPublic);
        if (isPublic && registry.containsKey(id)) {
            return gson.toJson(Map.of("err", "Service already registered: " + id, "serviceId", id));
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

        try {
            var svcDir = SVC_TEMP.resolve(id);
            cleanDir(svcDir);
            Files.createDirectories(svcDir);

            var execFile = extractNativeBinary(platformEl, svcDir, jarPath, devAssetsDir);
            if (execFile == null) return gson.toJson(Map.of("err", "Failed to extract native binary"));

            // 可信性校验：打包后路径（getAssetsPath 结果，assets/<id>/...）→ yeow.json native 声明的 SHA-256。
            // 仅单文件模式（string / {file}）参与；目录模式（{dir, entry}）暂不支持可信性声明。
            var packagedPath = packagedPathFor(platformEl);
            var expected = (packagedPath != null && nativeHashes != null) ? nativeHashes.get(packagedPath) : null;
            if (expected != null) {
                var actual = sha256(execFile.toFile());
                if (!expected.equalsIgnoreCase(actual)) {
                    LOG.severe("Native service " + id + " refused: SHA-256 mismatch for " + packagedPath
                        + " (declared " + expected + ", actual " + actual + ")");
                    cleanDir(svcDir);
                    return gson.toJson(Map.of("err", "Native service hash mismatch for " + packagedPath
                        + " — refused to load (plugin '" + pluginName + "' declares a different SHA-256;"
                        + " the executable may have been tampered with)"));
                }
                LOG.info("Native service " + id + ": SHA-256 verified (" + packagedPath + ")");
            } else if (packagedPath == null) {
                LOG.warning("Native service " + id + " (" + pluginName + "): directory-mode native services"
                    + " do not support trust declarations yet — treat as untrusted.");
            } else {
                LOG.warning("Native service " + id + " (" + pluginName + "): no trusted SHA-256 declaration for "
                    + packagedPath + " — treat as untrusted. Declare 'native' in yeow.config.json to pin hashes.");
            }

            // 批准检查：默认情况下不安全原生服务需要 /yeow approve <code>（内存唯一信任源）。
            // 一次性 code 只打印在服务器控制台日志——错误返回不含 code，插件无法预知并自动批准。
            var rt = yeow.YeowRuntime.inst();
            if (rt != null && rt.requireNativeApproval() && !rt.isNativeApproved(pluginName)) {
                var code = rt.requestApprovalCode(pluginName);
                LOG.warning("Native service " + id + " (" + pluginName + ") not approved —"
                    + " run /yeow approve " + code + " on the console, then /yeow reload " + pluginName
                    + " (one-time code, visible to admins only)");
                cleanDir(svcDir);
                return gson.toJson(Map.of("err",
                    "Native service " + id + " not approved — an admin must run /yeow approve <code>"
                        + " on the console (code printed in server log), then /yeow reload " + pluginName
                        + " to load it"));
            }

            execFile.toFile().setExecutable(true);

            var pb = new ProcessBuilder(execFile.toAbsolutePath().toString(), String.valueOf(nativePort), id);
            pb.directory(svcDir.toFile());
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
     * string / {file} → 值本身；{dir, entry} → 返回 null（目录模式暂不支持可信性声明）。
     * 与构建器 native manifest 中的 key 对齐。
     */
    private static String packagedPathFor(JsonElement platformEl) {
        if (platformEl.isJsonPrimitive()) return platformEl.getAsString();
        var cfg = platformEl.getAsJsonObject();
        if (cfg.has("file")) return cfg.get("file").getAsString();
        return null; // {dir, entry}：目录模式暂不支持
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

    private static Path extractNativeBinary(JsonElement platformEl, Path svcDir, String jarPath, String devAssetsDir) throws Exception {
        String assetDir = null, entryFile = null, extractFile = null;

        if (platformEl.isJsonPrimitive()) {
            extractFile = platformEl.getAsString();
        } else {
            var cfg = platformEl.getAsJsonObject();
            if (cfg.has("file")) {
                extractFile = cfg.get("file").getAsString();
            } else if (cfg.has("dir") && cfg.has("entry")) {
                assetDir = cfg.get("dir").getAsString();
                entryFile = cfg.get("entry").getAsString();
            } else {
                return null;
            }
        }

        if (assetDir != null) {
            var relAssetDir = assetDir.startsWith("assets/") ? assetDir.substring("assets/".length()) : assetDir;
            if (devAssetsDir != null) {
                var srcDir = Path.of(devAssetsDir, relAssetDir);
                if (Files.exists(srcDir)) {
                    try (var walk = Files.walk(srcDir)) {
                        walk.forEach(s -> {
                            try {
                                var rel = srcDir.relativize(s);
                                var dst = svcDir.resolve(rel);
                                if (Files.isDirectory(s)) Files.createDirectories(dst);
                                else Files.copy(s, dst, StandardCopyOption.REPLACE_EXISTING);
                            } catch (Exception ignored) {}
                        });
                    }
                    return svcDir.resolve(entryFile);
                }
            }
            try (var zip = new ZipFile(jarPath)) {
                var prefix = assetDir.startsWith("assets/") ? assetDir : "assets/" + assetDir;
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var ze = entries.nextElement();
                    var name = ze.getName();
                    if (!name.startsWith(prefix)) continue;
                    var rel = name.substring(prefix.length());
                    if (rel.isEmpty() || ze.isDirectory()) continue;
                    var dst = svcDir.resolve(rel);
                    Files.createDirectories(dst.getParent());
                    Files.copy(zip.getInputStream(ze), dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return svcDir.resolve(entryFile);
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

    public void request(String serviceId, String path, JsonObject body, String requestId, String consumerPlugin) {
        var entry = registry.get(serviceId);
        if (entry == null) {
            respondError(requestId, consumerPlugin, "Service not found: " + serviceId);
            return;
        }
        if (entry.type == Type.PLUGIN) {
            requestPlugin(entry, path, body, requestId, consumerPlugin);
        } else {
            requestNative(entry, path, body, requestId, consumerPlugin);
        }
    }

    private void requestPlugin(ServiceEntry entry, String path, JsonObject body, String requestId, String consumerPlugin) {
        var rt = YeowRuntime.inst();
        var pt = rt.getPlugin(entry.ownerPlugin);
        if (pt == null) {
            respondError(requestId, consumerPlugin, "Service owner plugin not running: " + entry.ownerPlugin);
            return;
        }
        var reqPayload = new LinkedHashMap<String, Object>();
        reqPayload.put("path", path);
        reqPayload.put("body", body != null ? gson.fromJson(body.toString(), Object.class) : null);
        var reqJson = gson.toJson(reqPayload);
        // Send to service owner plugin via JS callback
        // The owner plugin's onRequestCb receives the request and should respond via service.response
        pt.postMessage(gson.toJson(Map.of(
            "t", "cb", "p", entry.onRequestCb,
            "r", Map.of("_svc", "request", "requestId", requestId, "consumer", consumerPlugin, "path", path, "body", reqJson)
        )));
    }

    private void requestNative(ServiceEntry entry, String path, JsonObject body, String requestId, String consumerPlugin) {
        if (entry.nativeSocket == null || entry.nativeSocket.isClosed()) {
            respondError(requestId, consumerPlugin, "Native service not ready: " + entry.serviceId);
            return;
        }
        try {
            var msg = gson.toJson(Map.of(
                "type", "request",
                "requestId", requestId,
                "path", path,
                "body", body != null ? gson.fromJson(body.toString(), Object.class) : null
            )) + "\n";
            entry.nativeOut.write(msg.getBytes(StandardCharsets.UTF_8));
            entry.nativeOut.flush();
        } catch (Exception e) {
            respondError(requestId, consumerPlugin, e.getMessage());
        }
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
        var rt = YeowRuntime.inst();
        var pt = rt.getPlugin(consumerPlugin);
        if (pt != null) {
            pt.postMessage(yeow.channel.SyncCallbackHelper.cbMessage(requestId, result));
        }
    }

    private void respondError(String requestId, String consumerPlugin, String msg) {
        respond(requestId, consumerPlugin, Map.of("err", msg));
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
        var rt = YeowRuntime.inst();
        for (var sub : set) {
            if (!eventPath.equals(sub.eventPath)) continue;
            var pt = rt.getPlugin(sub.subscriberPlugin);
            if (pt != null) {
                var payload = Map.of("serviceId", serviceId, "eventPath", eventPath,
                    "body", (Object)(body != null ? gson.fromJson(body.toString(), Object.class) : null));
                pt.postMessage(gson.toJson(Map.of("t", "cb", "p", sub.subscriberCb, "r", payload)));
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
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var out = socket.getOutputStream();

            // First message MUST be ready
            var readyLine = in.readLine();
            if (readyLine == null) { socket.close(); return; }
            var ready = gson.fromJson(readyLine, JsonObject.class);
            var serviceId = ready.get("serviceId").getAsString();
            var servicePort = ready.get("servicePort").getAsInt();

            entry = registry.get(serviceId);
            if (entry == null || entry.type != Type.NATIVE) { socket.close(); return; }
            entry.nativeSocket = socket;
            entry.nativeOut = out;
            socket.setSoTimeout(0); // remove timeout for idle message loop

            LOG.info("Native service ready: " + serviceId + " on port " + servicePort);

            var pendings = pendingReady.remove(serviceId);
            if (pendings != null) {
                for (var p : pendings) respond(p.cbId(), p.pluginName(), Map.of("ok", true));
            }

            // Read subsequent messages
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    var msg = gson.fromJson(line, JsonObject.class);
                    var type = msg.get("type").getAsString();
                    if ("response".equals(type)) {
                        var reqId = msg.get("requestId").getAsString();
                        var body = msg.get("body");
                        // Find which consumer plugin sent this request
                        // The requestId encodes the consumer — we need to look it up
                        var pr = findConsumerForRequest(reqId);
                        if (pr != null) respond(reqId, pr.consumerPlugin(), body != null ? gson.fromJson(body.toString(), Object.class) : null);
                    } else if ("publish".equals(type)) {
                        var eventPath = msg.get("eventPath").getAsString();
                        var body = msg.has("body") ? msg.getAsJsonObject("body") : new JsonObject();
                        publishByService(entry.serviceId, eventPath, body);
                    }
                } catch (Exception ignored) {}
            }
            // Connection closed — mark service disconnected and fail pending requests.
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

    public void trackRequestConsumer(String requestId, String consumerPlugin, String serviceId) {
        requestConsumers.put(requestId, new PendingRequest(consumerPlugin, serviceId));
    }

    private PendingRequest findConsumerForRequest(String requestId) {
        return requestConsumers.remove(requestId);
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
        var rt = YeowRuntime.inst();
        if (rt == null) return;
        var pt = rt.getPlugin(e.ownerPlugin);
        if (pt == null) return;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("serviceId", e.serviceId);
        payload.put("reason", reason);
        if (e.nativeProc != null) {
            try { if (!e.nativeProc.isAlive()) payload.put("exitCode", e.nativeProc.exitValue()); } catch (Exception ignored) {}
        }
        if (e.outputLog != null && e.outputLog.length() > 0) payload.put("output", e.outputLog.toString().trim());
        pt.postMessage(gson.toJson(Map.of("t", "cb", "p", cb, "r", payload)));
    }

    private void failPendingRequests(String serviceId, String reason) {
        requestConsumers.forEach((reqId, pr) -> {
            if (serviceId.equals(pr.serviceId()) && requestConsumers.remove(reqId, pr)) {
                respond(reqId, pr.consumerPlugin(), Map.of("err", "Native service " + serviceId + " terminated (" + reason + ")"));
            }
        });
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
        volatile String terminateCb; // NATIVE only — onTerminate hook
        final java.util.concurrent.atomic.AtomicBoolean terminated = new java.util.concurrent.atomic.AtomicBoolean(false);
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
    record PendingRequest(String consumerPlugin, String serviceId) {}
}
