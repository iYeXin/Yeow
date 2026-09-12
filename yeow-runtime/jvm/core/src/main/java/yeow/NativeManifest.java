package yeow;

import com.google.gson.JsonElement;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 插件包的 native 可信性声明（构建时由 {@code yeow.config.json} 的 {@code native}
 * 计算并写入 {@code yeow.json}），加载插件时解析并作为插件元数据保存。
 *
 * <p>注册原生服务时据此强制校验（缺一不可）：
 * <ul>
 *   <li>{@code serviceId} 必须在声明清单中（未声明 → 拒绝）</li>
 *   <li>二进制打包路径必须有 SHA-256（未声明 → 拒绝）</li>
 *   <li>SHA-256 必须匹配（不符 → 拒绝）</li>
 * </ul>
 *
 * <p><b>声明 ≠ 可信</b>：声明只固定二进制内容；原生服务在认证/在线校验机制落地前
 * 仍统一按不可信处理（见 {@code native-service-allow-untrusted}）。
 *
 * @param files      打包后路径（{@code assets/<id>/...}） → SHA-256（hex）
 * @param serviceIds 声明的 serviceId 集合
 */
public record NativeManifest(Map<String, String> files, Set<String> serviceIds) {

    public static final NativeManifest EMPTY = new NativeManifest(Map.of(), Set.of());

    public NativeManifest {
        files = files == null ? Map.of() : Map.copyOf(files);
        serviceIds = serviceIds == null ? Set.of() : Set.copyOf(serviceIds);
    }

    public boolean isEmpty() { return files.isEmpty() && serviceIds.isEmpty(); }

    public boolean declaresService(String serviceId) {
        return serviceId != null && serviceIds.contains(serviceId);
    }

    /** 打包后路径对应的 SHA-256；未声明返回 null。 */
    public String hashFor(String packagedPath) {
        return packagedPath == null ? null : files.get(packagedPath);
    }

    /**
     * 解析 {@code yeow.json} 的 {@code native} 数组：
     * {@code [{ "serviceId": "...", "files": [{ "<打包后路径>": "<sha256>" }, ...] }, ...]}。
     * 缺省/非法 → {@link #EMPTY}。
     */
    public static NativeManifest parse(JsonElement nativeEl) {
        if (nativeEl == null || !nativeEl.isJsonArray()) return EMPTY;
        Map<String, String> files = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        for (var el : nativeEl.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            var e = el.getAsJsonObject();
            if (e.has("serviceId") && e.get("serviceId").isJsonPrimitive()) {
                ids.add(e.get("serviceId").getAsString());
            }
            if (e.has("files") && e.get("files").isJsonArray()) {
                for (var f : e.getAsJsonArray("files")) {
                    if (!f.isJsonObject()) continue;
                    for (var entry : f.getAsJsonObject().entrySet()) {
                        if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                            files.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    }
                }
            }
        }
        return new NativeManifest(files, ids);
    }
}
