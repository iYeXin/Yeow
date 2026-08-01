package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.Material;
import java.util.*;

public class MaterialTasks {
    public static Object getMaterials(JsonObject p) {
        var list = new ArrayList<Map<String, Object>>();
        for (var mat : Registry.MATERIAL) {
            var m = new LinkedHashMap<String, Object>();
            m.put("key", mat.getKey().toString());
            m.put("isBlock", mat.isBlock());
            m.put("isItem", mat.isItem());
            list.add(m);
        }
        return list;
    }

    public static Object getBlocks(JsonObject p) {
        var list = new ArrayList<String>();
        for (var mat : Registry.MATERIAL) {
            if (mat.isBlock()) list.add(mat.getKey().toString());
        }
        return list;
    }

    public static Object getItems(JsonObject p) {
        var list = new ArrayList<String>();
        for (var mat : Registry.MATERIAL) {
            if (mat.isItem()) list.add(mat.getKey().toString());
        }
        return list;
    }
}
