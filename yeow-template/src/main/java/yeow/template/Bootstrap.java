package yeow.template;

import org.bukkit.plugin.java.JavaPlugin;
import yeow.YeowRuntime;

/**
 * Bootstrap for all Yeow plugins.
 * Delegates everything to Yeow-Runtime by passing the JAR path.
 */
public class Bootstrap extends JavaPlugin {

    @Override
    public void onLoad() {
        var runtime = YeowRuntime.inst();
        if (runtime == null) {
            getLogger().severe("Yeow-Runtime not found! Cannot load plugin.");
            getServer().shutdown();
            return;
        }
        runtime.registerPlugin(getFile().getAbsolutePath());
    }

    @Override
    public void onDisable() {}
}
