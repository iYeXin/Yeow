package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import java.util.*;

public class AdvancementTasks {
    static org.bukkit.entity.Player player(JsonObject p) {
        var pl = Bukkit.getPlayer(java.util.UUID.fromString(p.get("uuid").getAsString()));
        if (pl == null) throw new IllegalArgumentException("Player not found");
        return pl;
    }

    static NamespacedKey key(String k) {
        if (k.contains(":")) {
            var parts = k.split(":", 2);
            return new NamespacedKey(parts[0], parts[1]);
        }
        return new NamespacedKey("minecraft", k);
    }

    public static Object grant(JsonObject p) {
        var pl = player(p);
        var adv = Bukkit.getAdvancement(key(p.get("key").getAsString()));
        if (adv == null) return false;
        for (var crit : adv.getCriteria()) pl.getAdvancementProgress(adv).awardCriteria(crit);
        return true;
    }

    public static Object revoke(JsonObject p) {
        var pl = player(p);
        var adv = Bukkit.getAdvancement(key(p.get("key").getAsString()));
        if (adv == null) return false;
        for (var crit : adv.getCriteria()) pl.getAdvancementProgress(adv).revokeCriteria(crit);
        return true;
    }

    public static Object getProgress(JsonObject p) {
        var pl = player(p);
        var adv = Bukkit.getAdvancement(key(p.get("key").getAsString()));
        if (adv == null) return null;
        var prog = pl.getAdvancementProgress(adv);
        var awarded = new ArrayList<String>();
        var remaining = new ArrayList<String>();
        for (var crit : adv.getCriteria()) {
            if (prog.getAwardedCriteria().contains(crit)) awarded.add(crit);
            else remaining.add(crit);
        }
        return Map.of("awardedCriteria", awarded, "remainingCriteria", remaining);
    }

    public static Object awardCriteria(JsonObject p) {
        var pl = player(p);
        var adv = Bukkit.getAdvancement(key(p.get("key").getAsString()));
        if (adv == null) return false;
        var prog = pl.getAdvancementProgress(adv);
        prog.awardCriteria(p.get("criteria").getAsString());
        return true;
    }

    public static Object revokeCriteria(JsonObject p) {
        var pl = player(p);
        var adv = Bukkit.getAdvancement(key(p.get("key").getAsString()));
        if (adv == null) return false;
        var prog = pl.getAdvancementProgress(adv);
        prog.revokeCriteria(p.get("criteria").getAsString());
        return true;
    }
}
