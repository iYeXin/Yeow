package yeow;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

/**
 * 统一消息节点权限门控（**主插件与 Worker 共用**）。
 *
 * <p>节点格式 {@code <channel>:<op>}，如 {@code fs:server.readFile}、
 * {@code http:requestAsync}、{@code service:registerNative}、{@code task:player.get}。
 *
 * <p>模式匹配：
 * <ul>
 *   <li>{@code *} 匹配一切</li>
 *   <li>{@code channel:*} 匹配该通道全部节点（如 {@code fs:*}）</li>
 *   <li>{@code channel:x.*} 匹配 {@code channel:x.} 前缀（如 {@code fs:server.*}、{@code task:player.*}）</li>
 *   <li>精确节点</li>
 * </ul>
 *
 * <p>判定顺序（{@code deny} 优先级最高）：
 * <ol>
 *   <li>{@code deny} 命中 → 拒绝</li>
 *   <li>启用 {@code allow} 白名单且未命中 → 拒绝</li>
 *   <li>权限集（主插件 computedPermissions / Worker 继承的主插件权限）命中 → 允许</li>
 *   <li>命中默认拒绝模式 → 拒绝</li>
 *   <li>默认允许（含 {@code task:*}——任务通道默认拥有）</li>
 * </ol>
 */
public final class PermissionGate {
    private PermissionGate() {}

    /**
     * 默认拒绝模式（未声明即拒绝；声明命中即放行）。
     * 使用模式而非纯前缀：{@code service:registerNative} 只覆盖该精确节点，
     * 不误伤 {@code service:registerNativeTerminate}（仅注册终止回调，默认允许）。
     */
    static final String[] DEFAULT_DENIED = {
        "fs:server.*", "fs:outer.*", "http:*", "service:registerNative",
    };

    /** 单条模式是否命中节点。 */
    public static boolean matches(String pattern, String node) {
        if (pattern == null || node == null) return false;
        if (pattern.equals("*") || pattern.equals(node)) return true;
        if (pattern.endsWith(":*")) return node.startsWith(pattern.substring(0, pattern.length() - 1));
        if (pattern.endsWith(".*")) return node.startsWith(pattern.substring(0, pattern.length() - 2) + ".");
        return false;
    }

    private static boolean anyMatch(Iterable<String> patterns, String node) {
        if (patterns == null) return false;
        for (var p : patterns) if (matches(p, node)) return true;
        return false;
    }

    /**
     * @param grants 授予的权限集（主插件 = computedPermissions；Worker = 继承的主插件权限集）
     * @param allow  Worker 白名单（null/空 = 不启用白名单）
     * @param deny   Worker 黑名单（优先级最高）
     * @param node   权限节点（{@code channel:op}）
     * @return null = 允许；否则返回拒绝原因
     */
    public static String check(Set<String> grants, List<String> allow, List<String> deny, String node) {
        if (node == null) return null;
        if (anyMatch(deny, node)) return "Permission denied: " + node;
        if (allow != null && !allow.isEmpty() && !anyMatch(allow, node)) return "Permission denied: " + node;
        if (anyMatch(grants, node)) return null;
        for (var p : DEFAULT_DENIED) if (matches(p, node)) return "Permission denied: " + node;
        return null;
    }

    /**
     * 由通道与载荷解析权限节点；返回 null 表示该通道不参与门控
     * （内部基础设施：{@code worker} / {@code debug} / {@code lifecycle}）。
     *
     * <p>{@code task} 批量（{@code tasks} 数组）需调用方逐个校验，见
     * {@link #taskNodes(JsonObject)}。
     */
    public static String nodeFor(String channel, JsonObject obj) {
        if (channel == null) return null;
        return switch (channel) {
            case "task" -> obj != null && obj.has("type") ? "task:" + obj.get("type").getAsString() : null;
            case "timer" -> obj != null && obj.has("type") ? "timer:" + obj.get("type").getAsString() : null;
            case "fs", "http", "service", "assets", "util" ->
                obj != null && obj.has("t") ? channel + ":" + obj.get("t").getAsString() : null;
            case "log" -> obj != null && obj.has("level") ? "log:" + obj.get("level").getAsString() : "log:INFO";
            case "env" -> "env:";
            default -> null; // worker / debug / lifecycle 等内部通道
        };
    }

    /**
     * task 通道的全部节点（单个 {@code type} 或批量 {@code tasks[].type}）。
     * 返回 null 表示无 type（按默认策略放行）。
     */
    public static String[] taskNodes(JsonObject obj) {
        if (obj == null) return null;
        if (obj.has("tasks") && obj.get("tasks").isJsonArray()) {
            var arr = obj.getAsJsonArray("tasks");
            var out = new java.util.ArrayList<String>(arr.size());
            for (var el : arr) {
                if (!el.isJsonObject()) continue;
                var t = el.getAsJsonObject();
                if (t.has("type")) out.add("task:" + t.get("type").getAsString());
            }
            return out.isEmpty() ? null : out.toArray(new String[0]);
        }
        return obj.has("type") ? new String[] { "task:" + obj.get("type").getAsString() } : null;
    }
}
