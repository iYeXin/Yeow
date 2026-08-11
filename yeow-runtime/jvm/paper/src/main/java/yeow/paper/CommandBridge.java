package yeow.paper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.entity.Player;
import yeow.channel.SyncCallbackHelper;
import yeow.profile.instrumentation.CommandMetric;
import yeow.profile.instrumentation.ProfileSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令桥（平台侧）：Bukkit 命令注册/注销/Tab 补全机制的全部实现。
 * 协议层的 command.register / command.dispatch / command.unregisterAll 经
 * {@code yeow.task.CommandTasks} 胶水委托到本类。
 */
public class CommandBridge {
    static final Gson gson = new Gson();
    private final YeowRuntime runtime;
    private final Map<String, List<String>> pluginCommands = new ConcurrentHashMap<>();
    private volatile ProfileSink sink;
    private volatile long timeoutMs = 1000;

    public CommandBridge(YeowRuntime runtime) {
        this.runtime = runtime;
    }

    public void setProfileSink(ProfileSink s) { sink = s; }
    public void setTimeoutMs(long ms) { timeoutMs = Math.max(100, ms); }

    /**
     * 注册命令（协议 command.register）。
     *
     * @return true 注册成功
     */
    public Object register(JsonObject p) throws Exception {
        var pluginName = p.get("pluginName").getAsString();
        var cmdName = p.get("commandName").getAsString();
        var cbId = p.has("callbackId") ? p.get("callbackId").getAsString() : null;
        var compCbId = p.has("completerCbId") ? p.get("completerCbId").getAsString() : null;
        // permission：对象 { node, default }（default 默认 op）——字符串包装在 JS 侧完成，Java 不做兼容
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

        var map = getBukkitCommandMap();
        if (map == null) return false;

        var cmd = new BukkitCommand(cmdName) {
            public boolean execute(CommandSender s, String l, String[] a) {
                // Yeow 生态权限检查：permissionCheck 事件结果优先，无处理时回退 Bukkit hasPermission。
                // 命令不设 Bukkit setPermission（不拦截）——执行时检查，保证 permissionCheck 可拦截。
                if (perm != null && !perm.isEmpty()) {
                    String target = s instanceof Player pl ? pl.getUniqueId().toString() : "CONSOLE";
                    var r = runtime.getEventBridge().checkPermission(target, perm);
                    boolean ok = r != null ? r : s.hasPermission(perm);
                    if (!ok) { s.sendMessage("No permission."); return true; }
                }
                var payload = Map.of("sender",Map.of("name",s.getName(),"uuid",s instanceof Player pl?pl.getUniqueId().toString():"CONSOLE","isPlayer",s instanceof Player),"args",List.of(a),"label",l);
                if (cbId != null && !cbId.isEmpty()) {
                    var pt = runtime.getPlugin(pluginName);
                    if (pt != null) pt.postMessage(gson.toJson(Map.of("t","cb","p",cbId,"r",payload)));
                }
                return true;
            }

            @Override public java.util.List<String> tabComplete(CommandSender s, String l, String[] a) {
                if (compCbId == null || compCbId.isEmpty()) return super.tabComplete(s, l, a);
                var pt = runtime.getPlugin(pluginName);
                if (pt == null) return super.tabComplete(s, l, a);
                long t0 = System.nanoTime();
                var pend = SyncCallbackHelper.register(compCbId);
                pt.postMessage(gson.toJson(Map.of("t","cb","p",compCbId,"r",Map.of(
                    "sender", Map.of("name",s.getName(),"uuid",s instanceof Player p?p.getUniqueId().toString():"CONSOLE","isPlayer",s instanceof Player),
                    "args", List.of(a)))));
                long timeout = timeoutMs;
                var deadline = System.nanoTime() + timeout * 1_000_000;
                while (System.nanoTime() < deadline && !pend.isDone()) {
                    // 与事件派发同款无预算排空：等待期间消费调度器队列，保证
                    // command.tabComplete 完成任务能在补全超时前执行。
                    if (Bukkit.isPrimaryThread()) {
                        var rt = YeowRuntime.inst();
                        if (rt != null) rt.getScheduler().drainAll();
                    }
                    Thread.onSpinWait();
                }
                long elapsedNs = System.nanoTime() - t0;
                boolean timedOut = !pend.isDone();
                ProfileSink ps = sink;
                if (ps != null) ps.onCommand(new CommandMetric(pluginName, cmdName, elapsedNs, timedOut));
                var result = (pend.isDone() && pend.getResult() instanceof java.util.List<?> list)
                    ? list.stream().map(Object::toString).toList() : null;
                SyncCallbackHelper.remove(compCbId);
                if (result != null) return result;
                return super.tabComplete(s, l, a);
            }
        };
        cmd.setDescription(p.has("description") ? p.get("description").getAsString() : "");
        cmd.setUsage("/" + cmdName);
        if (p.has("aliases")) { var as = new ArrayList<String>(); for(var e:p.getAsJsonArray("aliases")) as.add(e.getAsString()); cmd.setAliases(as); }
        // 权限节点注册进 Bukkit 权限系统（传统 Java 插件/权限插件可管理）；
        // 命令本身不设 setPermission——执行时的权限检查在 executor 内（permissionCheck 优先）。
        if (perm != null && !perm.isEmpty()) {
            try {
                runtime.getPermissionRegistry().register(perm, permDefault);
            } catch (Exception ignored) {}
        }
        map.register(pluginName.toLowerCase(), cmd);
        pluginCommands.computeIfAbsent(pluginName, k -> new ArrayList<>()).add(cmdName);
        return true;
    }

    public Object unregisterAll(String pluginName) {
        var names = pluginCommands.remove(pluginName);
        if (names == null || names.isEmpty()) return true;
        var map = getBukkitCommandMap();
        if (map == null) return true;
        var known = map.getKnownCommands();
        known.values().removeIf(cmd -> cmd instanceof BukkitCommand && names.contains(cmd.getName()));
        return true;
    }

    /** Sync commands to all clients (call after hot-reload to refresh client tab-completion list). */
    public void syncCommands() {
        try { var map = getBukkitCommandMap(); if (map != null) { var m = map.getClass().getMethod("syncCommands"); m.invoke(map); } } catch (Exception ignored) {}
    }

    public static CommandMap getBukkitCommandMap() {
        try { var f = Bukkit.getServer().getClass().getDeclaredField("commandMap"); f.setAccessible(true); return (CommandMap)f.get(Bukkit.getServer()); }
        catch (Exception ignored) { return null; }
    }
}
