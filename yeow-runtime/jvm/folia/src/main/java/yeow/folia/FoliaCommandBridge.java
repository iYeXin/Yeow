package yeow.folia;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.entity.Player;
import yeow.channel.SyncCallbackHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Folia 命令桥（实验性）：注册/注销/执行/Tab 补全。
 *
 * 执行与补全发生在**执行者所属 region 线程**；等待 JS 结果期间对执行者目标
 * 进入自旋 pump（与事件桥同机制），路由到该目标的游戏任务由 pump 就地执行。
 */
public class FoliaCommandBridge {
    static final Gson gson = new Gson();
    private static FoliaRuntime runtime;
    private static FoliaScheduler scheduler;
    private static final Map<String, List<String>> pluginCommands = new ConcurrentHashMap<>();
    private static volatile long timeoutMs = 1000;

    public static void init(FoliaRuntime rt, FoliaScheduler sched) {
        runtime = rt;
        scheduler = sched;
    }

    public static void setTimeoutMs(long ms) { timeoutMs = Math.max(100, ms); }

    public static Object register(JsonObject p) throws Exception {
        var pluginName = p.get("pluginName").getAsString();
        var cmdName = p.get("commandName").getAsString();
        var cbId = p.has("callbackId") ? p.get("callbackId").getAsString() : null;
        var compCbId = p.has("completerCbId") ? p.get("completerCbId").getAsString() : null;
        final String perm;
        final String permDefault;
        if (p.has("permission") && !p.get("permission").isJsonNull()) {
            var po = p.getAsJsonObject("permission");
            perm = po.get("node").getAsString();
            permDefault = po.has("default") && !po.get("default").isJsonNull() ? po.get("default").getAsString() : "op";
        } else {
            perm = null;
            permDefault = "op";
        }

        var map = Bukkit.getCommandMap();
        if (map == null) return false;

        var cmd = new BukkitCommand(cmdName) {
            public boolean execute(CommandSender s, String l, String[] a) {
                // 骨架：直接 Bukkit 权限检查（permissionCheck 生态钩子后续补充）
                if (perm != null && !perm.isEmpty() && !s.hasPermission(perm)) {
                    s.sendMessage("No permission.");
                    return true;
                }
                var payload = Map.of("sender", Map.of("name", s.getName(),
                    "uuid", s instanceof Player pl ? pl.getUniqueId().toString() : "CONSOLE",
                    "isPlayer", s instanceof Player),
                    "args", List.of(a), "label", l);
                if (cbId != null && !cbId.isEmpty()) {
                    var pt = runtime.core().getPlugin(pluginName);
                    if (pt != null) pt.postMessage(gson.toJson(Map.of("t", "cb", "p", cbId, "r", payload)));
                }
                return true;
            }

            @Override public java.util.List<String> tabComplete(CommandSender s, String l, String[] a) {
                if (compCbId == null || compCbId.isEmpty()) return super.tabComplete(s, l, a);
                var pt = runtime.core().getPlugin(pluginName);
                if (pt == null) return super.tabComplete(s, l, a);
                var pend = SyncCallbackHelper.register(compCbId);
                pt.postMessage(gson.toJson(Map.of("t", "cb", "p", compCbId, "r", Map.of(
                    "sender", Map.of("name", s.getName(),
                        "uuid", s instanceof Player p ? p.getUniqueId().toString() : "CONSOLE",
                        "isPlayer", s instanceof Player),
                    "args", List.of(a)))));
                // 进入补全模式（与通用执行器互斥；与事件/其他补全可并发），只取本插件任务
                SpinPump.spin(scheduler, java.util.Set.of(pluginName), pend::isDone, timeoutMs);
                var result = (pend.isDone() && pend.getResult() instanceof java.util.List<?> list)
                    ? list.stream().map(Object::toString).toList() : null;
                SyncCallbackHelper.remove(compCbId);
                if (result != null) return result;
                return super.tabComplete(s, l, a);
            }
        };
        cmd.setDescription(p.has("description") ? p.get("description").getAsString() : "");
        cmd.setUsage("/" + cmdName);
        if (p.has("aliases")) {
            var as = new ArrayList<String>();
            for (var e : p.getAsJsonArray("aliases")) as.add(e.getAsString());
            cmd.setAliases(as);
        }
        // 权限节点注册进 Bukkit 权限系统（PermissionDefault 语义：all/op/none）
        if (perm != null && !perm.isEmpty()) {
            try {
                var pd = org.bukkit.permissions.PermissionDefault.getByName(permDefault.toUpperCase());
                Bukkit.getPluginManager().addPermission(new org.bukkit.permissions.Permission(perm, pd));
            } catch (Exception ignored) {}
        }
        map.register(pluginName.toLowerCase(), cmd);
        pluginCommands.computeIfAbsent(pluginName, k -> new ArrayList<>()).add(cmdName);
        return true;
    }

    public static Object unregisterAll(String pluginName) {
        var names = pluginCommands.remove(pluginName);
        if (names == null || names.isEmpty()) return true;
        var map = Bukkit.getCommandMap();
        if (map == null) return true;
        var known = map.getKnownCommands();
        known.values().removeIf(c -> c instanceof BukkitCommand && names.contains(c.getName()));
        return true;
    }

    /** 同步命令到所有客户端（热重载后调用，刷新客户端 Tab 补全列表；与 Paper 同机制）。 */
    public static void syncCommands() {
        try {
            var map = Bukkit.getCommandMap();
            if (map != null) {
                var m = map.getClass().getMethod("syncCommands");
                m.invoke(map);
            }
        } catch (Exception ignored) {}
    }
}
