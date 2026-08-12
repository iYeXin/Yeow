package yeow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原生服务批准存储（`plugins/Yeow/runtime/approve.json`）。
 *
 * 内存是唯一信任源：启动时读取文件进入内存，`/yeow approve` 只修改内存；
 * 服务器关闭（所有 Yeow 插件卸载完成后）才写回文件。运行期间直接修改
 * approve.json 不会生效（下次关闭写回时被覆盖）。文件位于 runtime 目录，
 * 受 fs 写操作保护（插件无法通过 fs API 修改）。
 */
public class ApprovalStore {
    private static final Gson gson = new Gson();
    private final File file;
    /** pluginName → 批准时间戳（ms）。 */
    private final Map<String, Long> approvals = new ConcurrentHashMap<>();
    /** 一次性批准码（code → pluginName）：每次拒绝加载不安全原生服务时生成，仅控制台可见。 */
    private final Map<String, String> pendingCodes = new ConcurrentHashMap<>();

    public ApprovalStore(File dataFolder) {
        this.file = new File(new File(dataFolder, "runtime"), "approve.json");
        load();
    }

    private void load() {
        if (!file.isFile()) return;
        try {
            var text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            var obj = gson.fromJson(text, JsonObject.class);
            if (obj != null) {
                for (var e : obj.entrySet()) {
                    if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
                        approvals.put(e.getKey(), e.getValue().getAsLong());
                    }
                }
            }
        } catch (Exception e) {
            // 读取失败：以空内存为准（唯一信任源）
        }
    }

    public boolean isApproved(String pluginName) {
        return approvals.containsKey(pluginName);
    }

    /** 批准一个插件（内存修改；关闭时写回）。 */
    public void approve(String pluginName) {
        approvals.put(pluginName, System.currentTimeMillis());
    }

    /**
     * 为插件的本次拒绝生成一次性批准码（6 位 36 进制，去重）。
     * 只通过控制台日志告知管理员--插件无法预知，杜绝 dispatchCommand 自动批准。
     */
    public synchronized String requestApprovalCode(String pluginName) {
        String code;
        do {
            code = Integer.toString(Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextInt()), 36);
            while (code.length() < 6) code = "0" + code;
            code = code.substring(0, 6);
        } while (pendingCodes.containsKey(code));
        pendingCodes.put(code, pluginName);
        return code;
    }

    /** 用一次性 code 批准（成功返回插件名并作废该 code；失败返回 null）。 */
    public synchronized String approveByCode(String code) {
        if (code == null) return null;
        var plugin = pendingCodes.remove(code.trim().toLowerCase());
        if (plugin != null) approve(plugin);
        return plugin;
    }

    /** 写回 approve.json（服务器关闭、插件卸载完成后调用）。 */
    public void save() {
        try {
            var out = new JsonObject();
            for (var e : approvals.entrySet()) out.addProperty(e.getKey(), e.getValue());
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), gson.toJson(out), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 写回失败不影响关闭流程
        }
    }
}
