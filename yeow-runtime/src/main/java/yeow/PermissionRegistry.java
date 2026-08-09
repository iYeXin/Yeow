package yeow;

import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Yeow 权限节点注册表。
 *
 * - 权限节点注册进 Bukkit 权限系统（PermissionDefault）——Paper 平台兼容
 *   permissions.yml 静态声明与 LuckPerms 等权限管理插件
 * - 记录节点默认值（all/op/none），供 permissionCheck 事件携带 permission 对象
 * - Yeow 生态权限检查（permissionCheck 事件）优先级高于 Bukkit 权限系统
 */
public class PermissionRegistry {
    /** node → 默认值（all/op/none）。 */
    private final Map<String, String> defaults = new ConcurrentHashMap<>();

    /** 注册权限节点（幂等：同节点重复注册仅更新默认值记录，不重复 addPermission）。 */
    public String register(String node, String def) {
        if (node == null || node.isEmpty()) return node;
        defaults.put(node, def == null ? "none" : def);
        try {
            if (Bukkit.getPluginManager().getPermission(node) == null) {
                Bukkit.getPluginManager().addPermission(new Permission(node, toBukkitDefault(def)));
            }
        } catch (Exception ignored) {}
        return node;
    }

    /** 节点默认值（all/op/none）；未注册返回 null。 */
    public String defaultOf(String node) {
        return defaults.get(node);
    }

    /** Yeow 默认值 → Bukkit PermissionDefault 名。 */
    public static PermissionDefault toBukkitDefault(String def) {
        return switch (def == null ? "none" : def) {
            case "all" -> PermissionDefault.TRUE;
            case "op" -> PermissionDefault.OP;
            default -> PermissionDefault.FALSE;
        };
    }
}
