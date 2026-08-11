package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import yeow.paper.YeowRuntime;

/**
 * 命令任务胶水：仅解析协议参数并委托 {@link yeow.paper.CommandBridge}。
 * 实际命令注册/执行/Tab 补全机制在平台侧 CommandBridge。
 */
public class CommandTasks {

    public static Object register(JsonObject p) throws Exception {
        var rt = YeowRuntime.inst();
        return rt != null ? rt.getCommandBridge().register(p) : false;
    }

    public static Object dispatch(JsonObject p) throws Exception {
        var cmd = p.get("command").getAsString();
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    public static Object unregisterAll(String pluginName) {
        var rt = YeowRuntime.inst();
        return rt != null ? rt.getCommandBridge().unregisterAll(pluginName) : true;
    }
}
