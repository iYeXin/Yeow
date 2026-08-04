package yeow.task;

import yeow.PluginEntity;
import yeow.YeowRuntime;

/** Bridge to avoid circular dependencies — gives CommandTasks access to plugin entities. */
public class YeowRef {
    public static PluginEntity getPlugin(String name) {
        var inst = YeowRuntime.inst();
        return inst != null ? inst.getPlugin(name) : null;
    }
}
