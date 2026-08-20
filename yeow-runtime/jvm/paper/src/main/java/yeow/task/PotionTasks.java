package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.util.*;

public class PotionTasks {
    static LivingEntity entity(JsonObject p) {
        var e = Bukkit.getEntity(java.util.UUID.fromString(p.get("uuid").getAsString()));
        if (!(e instanceof LivingEntity le)) throw new IllegalArgumentException("Entity is not a LivingEntity");
        return le;
    }

    /**
     * 药水效果类型解析（值域附录 R1）：协议统一使用 minecraft 注册键（如 `minecraft:speed`，
     * 与 getActivePotionEffects 输出一致）；兼容旧式 Bukkit 枚举名（如 `SPEED`，大小写不敏感）。
     */
    static PotionEffectType potionType(String s) {
        var key = NamespacedKey.fromString(s);
        if (key != null) {
            var t = Registry.EFFECT.get(key);
            if (t != null) return t;
        }
        return PotionEffectType.getByName(s.toUpperCase());
    }

    public static Object addPotionEffect(JsonObject p) {
        var le = entity(p);
        var type = potionType(p.get("type").getAsString());
        if (type == null) throw new IllegalArgumentException("Unknown potion effect type: " + p.get("type"));
        var duration = p.has("duration") ? p.get("duration").getAsInt() : 200;
        var amplifier = p.has("amplifier") ? p.get("amplifier").getAsInt() : 0;
        var ambient = !p.has("ambient") || p.get("ambient").getAsBoolean();
        var particles = !p.has("particles") || p.get("particles").getAsBoolean();
        var icon = !p.has("icon") || p.get("icon").getAsBoolean();
        le.addPotionEffect(new PotionEffect(type, duration, amplifier, ambient, particles, icon));
        return true;
    }

    public static Object removePotionEffect(JsonObject p) {
        var le = entity(p);
        var type = potionType(p.get("type").getAsString());
        if (type != null) le.removePotionEffect(type);
        return true;
    }

    public static Object clearPotionEffects(JsonObject p) {
        for (var e : entity(p).getActivePotionEffects()) entity(p).removePotionEffect(e.getType());
        return true;
    }

    public static Object getActivePotionEffects(JsonObject p) {
        return entity(p).getActivePotionEffects().stream().map(pe -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("type", pe.getType().getKey().toString());
            m.put("duration", pe.getDuration());
            m.put("amplifier", pe.getAmplifier());
            m.put("ambient", pe.isAmbient());
            m.put("particles", pe.hasParticles());
            m.put("icon", pe.hasIcon());
            return m;
        }).toList();
    }
}
