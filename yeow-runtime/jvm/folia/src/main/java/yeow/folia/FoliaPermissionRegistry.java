package yeow.folia;

import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Yeow 权限节点注册表（Folia 版，对齐 Paper PermissionRegistry）。
 *
 * - 权限节点注册进 Bukkit 权限系统（PermissionDefault）--Folia 平台兼容
 *   permissions.yml 静态声明与 LuckPerms 等权限管理插件
 * - 记录节点默认值（all/op/none），供 permissionCheck 事件携带 permission 对象
 * - Yeow 生态权限检查（permissionCheck 事件）优先级高于 Bukkit 权限系统
 */
public final class FoliaPermissionRegistry {
    /** node → 默认值（all/op/none）。 */
    private static final Map<String, String> defaults = new ConcurrentHashMap<>();

    private FoliaPermissionRegistry() {}

    /** 注册权限节点（幂等：同节点重复注册仅更新默认值记录，不重复 addPermission）。默认默认值为 op。 */
    public static void register(String node, String def) {
        if (node == null || node.isEmpty()) return;
        defaults.put(node, def == null ? "op" : def);
        try {
            if (Bukkit.getPluginManager().getPermission(node) == null) {
                Bukkit.getPluginManager().addPermission(new Permission(node, toBukkitDefault(def)));
            }
        } catch (Exception ignored) {}
    }

    /** 节点默认值（all/op/none）；未注册返回 null。 */
    public static String defaultOf(String node) {
        return defaults.get(node);
    }

    /** Yeow 默认值 → Bukkit PermissionDefault 名。 */
    public static PermissionDefault toBukkitDefault(String def) {
        return switch (def == null ? "op" : def) {
            case "all" -> PermissionDefault.TRUE;
            case "op" -> PermissionDefault.OP;
            default -> PermissionDefault.FALSE;
        };
    }
}
