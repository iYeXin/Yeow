package yeow.task;

import yeow.InstanceRegistry;
import yeow.paper.TextUtil;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inventory 任务（统一三寻址）：
 * - `{uuid}` —— 玩家物品栏
 * - `{world, x, y, z}` —— 容器方块（Chest / Furnace / Hopper / Barrel / Dispenser / Dropper / BrewingStand 等 Container）
 * - `{id}` —— 自定义 Inventory（原 GUI，create 创建；open/close/查看者管理）
 *
 * 2026-08-13 重构：原 gui.* 任务族并入 inventory.*，统一持有者解析。
 */
public class InventoryTasks {
    /** 自定义 Inventory 实例表（id → Inventory）与归属（id → 插件名，purge 用）。 */
    static final Map<String, Inventory> custom = new ConcurrentHashMap<>();
    static final Map<String, String> owners = new ConcurrentHashMap<>();
    /** Inventory → id 反查（事件桥识别自定义 Inventory，inventoryClick/Close 携带 inventoryId）。 */
    public static final Map<Inventory, String> byInv = new ConcurrentHashMap<>();
    /** JS 句柄注册表（id → 释放器，由运行时装配；null = 未装配，跳过注册）。 */
    private static volatile InstanceRegistry instances;

    public static void setInstances(InstanceRegistry r) { instances = r; }

    // ── 统一持有者解析 ───────────────────────────────────────────────

    /** 三寻址统一解析：id（自定义）/ uuid（玩家）/ world+x+y+z（容器方块）。 */
    static Inventory resolve(JsonObject p) {
        if (p.has("id") && !p.get("id").isJsonNull()) {
            var inv = custom.get(p.get("id").getAsString());
            if (inv == null) throw new IllegalArgumentException("Inventory not found: " + p.get("id").getAsString());
            return inv;
        }
        if (p.has("uuid") && !p.get("uuid").isJsonNull()) {
            var pl = Bukkit.getPlayer(UUID.fromString(p.get("uuid").getAsString()));
            if (pl == null) throw new IllegalArgumentException("Player not found");
            return pl.getInventory();
        }
        if (p.has("world") && p.has("x") && p.has("y") && p.has("z")) {
            var b = Bukkit.getWorld(p.get("world").getAsString()).getBlockAt(
                p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
            var st = b.getState();
            if (st instanceof Container c) return c.getInventory();
            throw new IllegalArgumentException("Not a container block: " + b.getType().getKey());
        }
        throw new IllegalArgumentException("Missing inventory address (id / uuid / world+x+y+z)");
    }

    /** 持有者类型（协议 `inventory.getType`）。 */
    static String typeOf(JsonObject p) {
        if (p.has("id") && !p.get("id").isJsonNull()) return "CUSTOM";
        if (p.has("uuid") && !p.get("uuid").isJsonNull()) return "PLAYER";
        if (p.has("world") && p.has("x") && p.has("y") && p.has("z")) {
            var b = Bukkit.getWorld(p.get("world").getAsString()).getBlockAt(
                p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
            var st = b.getState();
            if (st instanceof Container c) return c.getType().name();
            throw new IllegalArgumentException("Not a container block: " + b.getType().getKey());
        }
        throw new IllegalArgumentException("Missing inventory address (id / uuid / world+x+y+z)");
    }

    // ── 自定义 Inventory 生命周期 ───────────────────────────────────

    public static Object create(JsonObject p) {
        var inv = Bukkit.createInventory(null, p.get("size").getAsInt(), TextUtil.toLegacy(TextUtil.parse(p.get("title").getAsString())));
        var id = p.get("id").getAsString();
        custom.put(id, inv);
        owners.put(id, p.has("_plugin") ? p.get("_plugin").getAsString() : "");
        byInv.put(inv, id);
        var reg = instances;
        if (reg != null) reg.register(id, () -> removeCustom(id));
        return id;
    }

    /** 释放自定义 Inventory：移除注册/归属，关闭所有查看者，同步注销句柄释放器。 */
    static void removeCustom(String id) {
        var inv = custom.remove(id);
        owners.remove(id);
        if (inv != null) byInv.remove(inv);
        var reg = instances;
        if (reg != null) reg.release(id);
        if (inv != null) {
            inv.getViewers().forEach(v -> { if (v instanceof Player pl) pl.closeInventory(); });
        }
    }

    /** Close and drop every custom Inventory owned by a plugin (called on unload/reload). */
    public static void purgePlugin(String pluginName) {
        owners.entrySet().removeIf(e -> {
            if (!pluginName.equals(e.getValue())) return false;
            removeCustom(e.getKey());
            return true;
        });
    }

    public static Object open(JsonObject p) {
        var inv = resolve(p);
        var pl = Bukkit.getPlayer(UUID.fromString(p.get("uuid").getAsString()));
        if (pl != null) pl.openInventory(inv);
        return true;
    }

    public static Object close(JsonObject p) {
        var inv = resolve(p);
        inv.getViewers().forEach(v -> { if (v instanceof Player pl) pl.closeInventory(); });
        return true;
    }

    public static Object closePlayer(JsonObject p) {
        var inv = resolve(p);
        var pl = Bukkit.getPlayer(UUID.fromString(p.get("uuid").getAsString()));
        if (pl != null) pl.closeInventory();
        return true;
    }

    public static Object getViewers(JsonObject p) {
        return resolve(p).getViewers().stream()
            .filter(v -> v instanceof Player)
            .map(v -> ((Player) v).getUniqueId().toString()).toList();
    }

    public static Object destroy(JsonObject p) {
        removeCustom(p.get("id").getAsString());
        return true;
    }

    // ── 内容操作（三寻址通用） ───────────────────────────────────────

    public static Object getSize(JsonObject p) {
        return resolve(p).getSize();
    }

    /** 全槽位快照数组（空槽为 null，长度 = 容器槽位数）。 */
    public static Object getContents(JsonObject p) {
        var inv = resolve(p);
        var out = new ArrayList<Object>();
        for (int i = 0; i < inv.getSize(); i++) {
            var item = inv.getItem(i);
            out.add(item != null && item.getType() != Material.AIR ? serializeItem(item) : null);
        }
        return out;
    }

    /** 整容器写入（items 长度可与容器不匹配：短数组只写前段，长数组忽略超出）。 */
    public static Object setContents(JsonObject p) {
        var inv = resolve(p);
        var items = p.getAsJsonArray("items");
        for (int i = 0; i < Math.min(items.size(), inv.getSize()); i++) {
            var el = items.get(i);
            inv.setItem(i, el.isJsonNull() ? null : buildItem(el.getAsJsonObject()));
        }
        return true;
    }

    public static Object getItem(JsonObject p) {
        var item = resolve(p).getItem(p.get("slot").getAsInt());
        return item != null && item.getType() != Material.AIR ? serializeItem(item) : null;
    }

    public static Object setItem(JsonObject p) {
        var inv = resolve(p);
        if (p.has("item") && !p.get("item").isJsonNull()) {
            inv.setItem(p.get("slot").getAsInt(), buildItem(p.getAsJsonObject("item")));
        } else {
            inv.setItem(p.get("slot").getAsInt(), null);
        }
        return true;
    }

    public static Object setItems(JsonObject p) {
        var inv = resolve(p);
        var item = p.has("item") && !p.get("item").isJsonNull() ? buildItem(p.getAsJsonObject("item")) : new ItemStack(Material.AIR);
        for (var el : p.getAsJsonArray("slots")) {
            inv.setItem(el.getAsInt(), item.clone());
        }
        return true;
    }

    public static Object fill(JsonObject p) {
        var inv = resolve(p);
        var item = buildItem(p.getAsJsonObject("item"));
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, item.clone());
        return true;
    }

    public static Object clear(JsonObject p) {
        var inv = resolve(p);
        if (p.has("slot")) inv.setItem(p.get("slot").getAsInt(), null);
        else inv.clear();
        return true;
    }

    /** 添加物品到空位：返回**未放入数量**（玩家物品栏溢出部分掉落在地上，仍返回 0）。 */
    public static Object addItem(JsonObject p) {
        var inv = resolve(p);
        var item = buildItem(p.getAsJsonObject("item"));
        var left = inv.addItem(item);
        if (left.isEmpty()) return 0;
        // 玩家物品栏：溢出掉落（对齐历史行为）；自定义/方块容器：返回未放入数量
        if (p.has("uuid") && !p.get("uuid").isJsonNull()) {
            var pl = Bukkit.getPlayer(UUID.fromString(p.get("uuid").getAsString()));
            if (pl != null) {
                left.values().forEach(rest -> pl.getWorld().dropItem(pl.getLocation(), rest));
                return 0;
            }
        }
        return left.values().stream().mapToInt(ItemStack::getAmount).sum();
    }

    /** 移除指定物品（按类型 + meta 匹配，amount 默认 1）。返回**未移除数量**（0 = 全部移除）。 */
    public static Object removeItem(JsonObject p) {
        var inv = resolve(p);
        var left = inv.removeItem(buildItem(p.getAsJsonObject("item")));
        return left.values().stream().mapToInt(ItemStack::getAmount).sum();
    }

    // ── ItemStack 工具（原 GuiTasks 迁入；buildItem/serializeItem/colorOf） ──

    /**
     * 属性类型解析（值域附录 R1）：协议统一使用 minecraft 注册键（如 `minecraft:attack_damage`，
     * 与 serializeItem 输出一致）；兼容旧式 Bukkit 枚举名（如 `ATTACK_DAMAGE`，大小写不敏感）。
     */
    static org.bukkit.attribute.Attribute attribute(String s) {
        var key = org.bukkit.NamespacedKey.fromString(s);
        if (key != null) {
            var t = org.bukkit.Registry.ATTRIBUTE.get(key);
            if (t != null) return t;
        }
        try { return org.bukkit.attribute.Attribute.valueOf(s.toUpperCase()); } catch (Exception e) { return null; }
    }

    static ItemStack buildItem(JsonObject p) {
        if (p == null || !p.has("type")) return new ItemStack(Material.AIR);
        var mat = Material.matchMaterial(p.get("type").getAsString());
        if (mat == null) return new ItemStack(Material.AIR);
        var amount = p.has("amount") ? p.get("amount").getAsInt() : 1;
        var item = new ItemStack(mat, amount);
        if (p.has("meta")) {
            var meta = item.getItemMeta();
            var m = p.getAsJsonObject("meta");
            if (m.has("displayName")) meta.displayName(TextUtil.parse(m.get("displayName").getAsString()));
            if (m.has("lore")) {
                var lore = new ArrayList<net.kyori.adventure.text.Component>();
                for (var el : m.getAsJsonArray("lore")) lore.add(TextUtil.parse(el.getAsString()));
                meta.lore(lore);
            }
            if (m.has("customModelData")) meta.setCustomModelData(m.get("customModelData").getAsInt());
            if (m.has("unbreakable")) meta.setUnbreakable(m.get("unbreakable").getAsBoolean());
            if (m.has("hideTooltip")) meta.setHideTooltip(m.get("hideTooltip").getAsBoolean());
            if (m.has("enchantments")) {
                var enchs = m.getAsJsonObject("enchantments");
                for (var k : enchs.keySet()) {
                    var ench = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.fromString(k));
                    if (ench != null) meta.addEnchant(ench, enchs.get(k).getAsInt(), true);
                }
            }
            if (m.has("itemFlags")) {
                for (var el : m.getAsJsonArray("itemFlags")) {
                    try { meta.addItemFlags(org.bukkit.inventory.ItemFlag.valueOf(el.getAsString())); } catch (Exception ignored) {}
                }
            }
            // ── 扩展 meta（2026-08-13，全部 try/catch 兜底：跨版本不兼容时静默忽略） ──
            if (m.has("damage") && meta instanceof org.bukkit.inventory.meta.Damageable d) {
                try { d.setDamage(m.get("damage").getAsInt()); } catch (Exception ignored) {}
            }
            if (m.has("color")) {
                var color = colorOf(m.get("color"));
                if (meta instanceof org.bukkit.inventory.meta.LeatherArmorMeta lam) {
                    try { lam.setColor(color); } catch (Exception ignored) {}
                }
                if (meta instanceof org.bukkit.inventory.meta.PotionMeta pm) {
                    try { pm.setColor(color); } catch (Exception ignored) {}
                }
            }
            if (m.has("potionEffects") && meta instanceof org.bukkit.inventory.meta.PotionMeta pm) {
                for (var el : m.getAsJsonArray("potionEffects")) {
                    try {
                        var eff = el.getAsJsonObject();
                        var type = PotionTasks.potionType(eff.get("type").getAsString());
                        if (type == null) continue;
                        pm.addCustomEffect(new org.bukkit.potion.PotionEffect(type,
                            eff.has("duration") ? eff.get("duration").getAsInt() : 200,
                            eff.has("amplifier") ? eff.get("amplifier").getAsInt() : 0,
                            eff.has("ambient") && eff.get("ambient").getAsBoolean(),
                            eff.has("particles") && eff.get("particles").getAsBoolean()), true);
                    } catch (Exception ignored) {}
                }
            }
            if (m.has("skullOwner") && meta instanceof org.bukkit.inventory.meta.SkullMeta sm) {
                var owner = m.get("skullOwner").getAsString();
                try {
                    if (owner.contains("==")) {
                        var profile = Bukkit.createProfile(UUID.randomUUID());
                        profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", owner));
                        sm.setPlayerProfile(profile);
                    } else if (owner.contains("-") && owner.length() == 36) {
                        sm.setPlayerProfile(Bukkit.getOfflinePlayer(UUID.fromString(owner)).getPlayerProfile());
                    } else {
                        sm.setPlayerProfile(Bukkit.getOfflinePlayer(owner).getPlayerProfile());
                    }
                } catch (Exception ignored) {}
            }
            if (m.has("attributeModifiers")) {
                int seq = 0;
                for (var el : m.getAsJsonArray("attributeModifiers")) {
                    try {
                        var am = el.getAsJsonObject();
                        var attr = attribute(am.get("attribute").getAsString());
                        if (attr == null) continue;
                        var amt = am.get("amount").getAsDouble();
                        var op = org.bukkit.attribute.AttributeModifier.Operation.valueOf(am.get("operation").getAsString().toUpperCase());
                        var slot = am.has("slot") ? org.bukkit.inventory.EquipmentSlotGroup.getByName(am.get("slot").getAsString()) : null;
                        if (slot == null) slot = org.bukkit.inventory.EquipmentSlotGroup.ANY;
                        var mod = new org.bukkit.attribute.AttributeModifier(new NamespacedKey("yeow", "mod" + (++seq)), amt, op, slot);
                        meta.addAttributeModifier(attr, mod);
                    } catch (Exception ignored) {}
                }
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** color 解析：`"#RRGGBB"` 或 `{r,g,b}` → Bukkit Color（失败返回白色）。 */
    static org.bukkit.Color colorOf(com.google.gson.JsonElement el) {
        try {
            if (el.isJsonPrimitive()) {
                var s = el.getAsString().replace("#", "");
                if (s.length() == 6) {
                    return org.bukkit.Color.fromRGB(Integer.parseInt(s, 16));
                }
            } else if (el.isJsonObject()) {
                var o = el.getAsJsonObject();
                var r = o.has("r") ? o.get("r").getAsInt() : 255;
                var g = o.has("g") ? o.get("g").getAsInt() : 255;
                var b = o.has("b") ? o.get("b").getAsInt() : 255;
                return org.bukkit.Color.fromRGB(r, g, b);
            }
        } catch (Exception ignored) {}
        return org.bukkit.Color.WHITE;
    }

    /** ItemStack → 协议快照（读回：inventory.getItem / player.getItemInMainHand 共用）。 */
    static Object serializeItem(ItemStack item) {
        var m = new LinkedHashMap<String, Object>();
        m.put("type", item.getType().getKey().toString());
        m.put("amount", item.getAmount());
        if (item.hasItemMeta()) {
            var meta = item.getItemMeta();
            var metaMap = new LinkedHashMap<String, Object>();
            if (meta.hasDisplayName()) metaMap.put("displayName", TextUtil.toLegacy(meta.displayName()));
            if (meta.hasLore()) metaMap.put("lore", meta.lore().stream().map(TextUtil::toLegacy).toList());
            if (meta.hasCustomModelData()) metaMap.put("customModelData", meta.getCustomModelData());
            if (meta.isUnbreakable()) metaMap.put("unbreakable", true);
            if (meta.hasEnchants()) {
                var enchs = new LinkedHashMap<String, Object>();
                meta.getEnchants().forEach((ench, lvl) -> enchs.put(ench.getKey().toString(), lvl));
                metaMap.put("enchantments", enchs);
            }
            if (meta.isHideTooltip()) metaMap.put("hideTooltip", true);
            if (!meta.getItemFlags().isEmpty()) {
                metaMap.put("itemFlags", meta.getItemFlags().stream().map(Enum::name).toList());
            }
            if (meta instanceof org.bukkit.inventory.meta.Damageable d && d.hasDamage()) {
                metaMap.put("damage", d.getDamage());
            }
            // 扩展 meta 回读（与 buildItem 写侧对称，2026-08-13 审计修复）：
            // color / potionEffects / skullOwner / attributeModifiers
            if (meta instanceof org.bukkit.inventory.meta.LeatherArmorMeta lam && lam.getColor() != null) {
                metaMap.put("color", "#" + Integer.toHexString(lam.getColor().asRGB()));
            }
            if (meta instanceof org.bukkit.inventory.meta.PotionMeta pm) {
                if (pm.hasColor()) metaMap.put("color", "#" + Integer.toHexString(pm.getColor().asRGB()));
                if (pm.hasCustomEffects()) {
                    var effs = new ArrayList<Object>();
                    for (var pe : pm.getCustomEffects()) {
                        var em = new LinkedHashMap<String, Object>();
                        em.put("type", pe.getType().getKey().toString());
                        em.put("duration", pe.getDuration());
                        em.put("amplifier", pe.getAmplifier());
                        em.put("ambient", pe.isAmbient());
                        em.put("particles", pe.hasParticles());
                        effs.add(em);
                    }
                    metaMap.put("potionEffects", effs);
                }
            }
            if (meta instanceof org.bukkit.inventory.meta.SkullMeta sm && sm.getPlayerProfile() != null) {
                var profile = sm.getPlayerProfile();
                String textures = null;
                for (var pr : profile.getProperties()) {
                    if ("textures".equals(pr.getName())) { textures = pr.getValue(); break; }
                }
                if (textures != null) {
                    metaMap.put("skullOwner", textures);
                } else if (profile.getName() != null) {
                    metaMap.put("skullOwner", profile.getName());
                }
            }
            if (meta instanceof org.bukkit.inventory.meta.ItemMeta im) {
                // getAttributeModifiers() 在无属性修饰时可返回 null——须判空（否则 NPE）
                var attrMods = im.getAttributeModifiers();
                if (attrMods != null && !attrMods.isEmpty()) {
                    var mods = new ArrayList<Object>();
                    attrMods.forEach((attr, mod) -> {
                        var am = new LinkedHashMap<String, Object>();
                        am.put("attribute", attr.getKey().toString());
                        am.put("amount", mod.getAmount());
                        am.put("operation", mod.getOperation().name().toLowerCase());
                        if (mod.getSlotGroup() != null) am.put("slot", mod.getSlotGroup().toString());
                        mods.add(am);
                    });
                    metaMap.put("attributeModifiers", mods);
                }
            }
            m.put("meta", metaMap);
        }
        return m;
    }
}
