package yeow.task;

import yeow.PluginThread;
import yeow.YeowRuntime;

/** Bridge to avoid circular dependencies — gives CommandTasks access to PluginThread. */
public class YeowRef {
    public static PluginThread getPluginThread(String name) {
        var inst = YeowRuntime.inst();
        return inst != null ? inst.getPlugin(name) : null;
    }
}
