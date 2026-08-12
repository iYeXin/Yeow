package yeow.folia;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Folia 任务执行器（实验性，独立实现，不与 Paper 共享）。
 *
 * **路由与归属：按任务类型家族成对编写**（与执行器 {@link #doExecute} 并列）：
 * <ul>
 *   <li>{@link #targetOf} — 提交期/投递期的**目标 key**（驻留标记、wake 抢占、dispatchAsync 路由）</li>
 *   <li>{@link #ownedHere} — 执行期**当前线程归属判断**（就地执行 / 驻留让出）——直接基于
 *       原始参数判定，不经 key 反推（key 是有损编码，反推会丢失任务类型语义）</li>
 * </ul>
 * 新增任务类型时两个函数必须同步实现对应分支（家族规则见各自注释），
 * 否则要么死锁要么全走投递（GLOBAL）。
 *
 * 执行入口：
 * <ul>
 *   <li>{@link #executeAsync} — 非阻塞入口（调度循环 / 事件自旋泵共用）：归属由调用方
 *       预计算（local）——目标在当前线程 → 就地执行并立即回调；否则经 Folia 调度器
 *       投递（不等待），结果由目标线程回调。</li>
 *   <li>{@link #execute} — 阻塞入口（dispatchAsync 投递后的目标线程用）：调用方已保证
 *       当前线程为目标线程，直接执行，零判断。</li>
 * </ul>
 */
public class FoliaTasks {
    private static FoliaScheduler scheduler;

    /** 运行时装配（FoliaRuntime.onEnable）。 */
    public static void init(FoliaScheduler s) { scheduler = s; }

    /** 直接执行（dispatchAsync 投递后的目标线程使用；调用方已保证归属）。 */
    public static Object execute(String taskType, JsonObject params) throws Exception {
        return doExecute(taskType, params);
    }

    /**
     * 目标调度句柄（按任务家族）：调度器对任务类型一无所知，只消费：
     * <ul>
     *   <li>{@code marker} — 驻留标记（纯字符串，任意线程可计算；驻留抢占/让出/cycle 定位用）</li>
     *   <li>{@code run} — 惰性调度闭包：接收完成回调，在**全局 region 线程**解析目标后经
     *       对应 Folia 调度器投递（实体退役/解析失败 → 回调 err）。构造本身纯计算，无 Bukkit 调用</li>
     * </ul>
     */
    public record DispatchTarget(String marker, java.util.function.Consumer<java.util.function.Consumer<Object>> run) {}

    /**
     * 任务类型 → 目标调度句柄（三函数契约之一：ownedHere / getScheduler / execute）。
     * 家族共享实现；全局类（server/material/command/event.subscribe 等）与未知类型 → GLOBAL。
     */
    public static DispatchTarget getScheduler(String taskType, JsonObject params) {
        if (params == null) return global(taskType, params);
        // 实体家族（player/entity/potion/pdc-uuid）：目标 = 实体/玩家所在 region
        if (taskType.startsWith("player.") || taskType.startsWith("entity.") || taskType.startsWith("potion.")
                || (taskType.startsWith("pdc.") && params.has("uuid") && !params.get("uuid").isJsonNull())) {
            var id = params.has("uuid") ? params.get("uuid").getAsString()
                : (params.has("identifier") ? params.get("identifier").getAsString() : null);
            if (id == null || id.isEmpty()) return global(taskType, params);
            return new DispatchTarget("uuid:" + id, finish -> dispatchEntity(id, taskType, params, finish));
        }
        // 世界家族（world/block/chunk + pdc 方块/世界）：目标 = 区块 region（世界坐标 >>4，区块坐标直用）
        if (taskType.startsWith("pdc.") || taskType.startsWith("world.") || taskType.startsWith("block.") || taskType.startsWith("chunk.")) {
            if (params.has("world") && !params.get("world").isJsonNull()) {
                var name = params.get("world").getAsString();
                if (taskType.equals("world.loadChunk") || taskType.equals("world.unloadChunk") || taskType.equals("world.isChunkLoaded")) {
                    var cx = params.get("x").getAsInt();
                    var cz = params.get("z").getAsInt();
                    return new DispatchTarget("world:" + name + ":c" + cx + ":c" + cz, finish -> dispatchWorld(name, cx, cz, taskType, params, finish));
                }
                if (params.has("x") && params.has("z")) {
                    var cx = (int) params.get("x").getAsDouble() >> 4;
                    var cz = (int) params.get("z").getAsDouble() >> 4;
                    return new DispatchTarget("world:" + name + ":" + (int) params.get("x").getAsDouble() + ":" + (int) params.get("z").getAsDouble(),
                        finish -> dispatchWorld(name, cx, cz, taskType, params, finish));
                }
                return new DispatchTarget("world:" + name, finish -> dispatchWorld(name, 0, 0, taskType, params, finish));
            }
            if (taskType.startsWith("world.get") && params.has("name") && !params.get("name").getAsString().isEmpty()) {
                var name = params.get("name").getAsString();
                return new DispatchTarget("world:" + name, finish -> dispatchWorld(name, 0, 0, taskType, params, finish));
            }
            return global(taskType, params);
        }
        // event.complete：目标 = 事件派发时记录的 key（uuid:/world:）
        if (taskType.equals("event.complete")) {
            var et = params.has("eventId") ? params.get("eventId").getAsString() : "";
            var target = FoliaEventBridge.targetOfEvent(et);
            if (target != null) return new DispatchTarget(target, finish -> dispatchKey(target, taskType, params, finish));
            return global(taskType, params);
        }
        return global(taskType, params);
    }

    /** 实体目标投递：全局 region 解析引用（AsyncCatcher 约束）→ 实体调度器执行；退役/找不到 → err。 */
    private static void dispatchEntity(String id, String taskType, JsonObject params, java.util.function.Consumer<Object> finish) {
        Bukkit.getGlobalRegionScheduler().run(rt(), t -> {
            try {
                var entity = TargetKey.resolveEntity("uuid:" + id);
                if (entity != null) {
                    entity.getScheduler().run(rt(), t2 -> finish.accept(execOrErr(taskType, params)),
                        () -> finish.accept(Map.of("err", "entity retired")));
                } else {
                    finish.accept(Map.of("err", "entity not found: " + id));
                }
            } catch (Exception e) {
                finish.accept(FoliaScheduler.errObject(e, taskType));
            }
        });
    }

    /** 世界/区块目标投递：世界引用来自注册表（任意线程安全），直接投递区块 region。 */
    private static void dispatchWorld(String name, int chunkX, int chunkZ, String taskType, JsonObject params, java.util.function.Consumer<Object> finish) {
        var w = Bukkit.getWorld(name);
        if (w == null) {
            finish.accept(Map.of("err", "world not found: " + name));
            return;
        }
        Bukkit.getRegionScheduler().run(rt(), w, chunkX, chunkZ, t -> finish.accept(execOrErr(taskType, params)));
    }

    /** event.complete 目标投递：目标为已记录的 key（uuid:/world:），按 key 解析。 */
    private static void dispatchKey(String target, String taskType, JsonObject params, java.util.function.Consumer<Object> finish) {
        if (target.startsWith(TargetKey.UUID_PREFIX)) {
            dispatchEntity(target.substring(TargetKey.UUID_PREFIX.length()), taskType, params, finish);
        } else if (target.startsWith(TargetKey.WORLD_PREFIX)) {
            var c = TargetKey.chunkCoords(target);
            dispatchWorld(TargetKey.resolveWorld(target).getName(), c[0], c[1], taskType, params, finish);
        } else {
            finish.accept(Map.of("err", "unknown event target: " + target));
        }
    }

    /** 全局目标投递（携带任务体）：经全局 region 调度器执行。 */
    private static DispatchTarget global(String taskType, JsonObject params) {
        return new DispatchTarget(TargetKey.GLOBAL, finish ->
            Bukkit.getGlobalRegionScheduler().run(rt(), t -> finish.accept(execOrErr(taskType, params))));
    }

    private static Object execOrErr(String taskType, JsonObject params) {
        try {
            return execute(taskType, params);
        } catch (Exception e) {
            return FoliaScheduler.errObject(e, taskType);
        }
    }

    /** 运行时引用（经调度器获取，投递调度任务需要 plugin 参数）。 */
    private static FoliaRuntime rt() { return scheduler.runtime(); }

    /**
     * 当前线程归属判断（**按任务类型家族独立编写**，与 {@link #targetOf} 成对、与执行器并列）。
     * **直接基于原始参数判定**——不经 key 反推（key 是有损编码：坐标截断、c 前缀、
     * name/world 双形态，反推会丢失任务类型语义）。捕获 AsyncCatcher 拦截异常视为
     * "非本 region"；全局类/未知任务恒 false。调度器据连续两次 false 让出驻留。
     */
    public static boolean ownedHere(String taskType, JsonObject params) {
        if (params == null) return false;
        // 实体家族（player/entity/potion/pdc-uuid）：归属 = 目标实体所在 region
        if (taskType.startsWith("player.") || taskType.startsWith("entity.") || taskType.startsWith("potion.")
                || (taskType.startsWith("pdc.") && params.has("uuid") && !params.get("uuid").isJsonNull())) {
            return entityOwnedHere(params);
        }
        // 方块/世界 PDC（非 uuid）：按目标区块/世界判断
        if (taskType.startsWith("pdc.")) {
            if (params.has("world") && params.has("x") && params.has("z")) {
                return worldChunkOwnedHere(params.get("world").getAsString(),
                    (int) params.get("x").getAsDouble() >> 4, (int) params.get("z").getAsDouble() >> 4);
            }
            if (params.has("world")) {
                return worldChunkOwnedHere(params.get("world").getAsString(), 0, 0);
            }
            return false;
        }
        // 世界家族（world/block/chunk）：按目标区块判断（区块坐标任务 x/z 已是区块坐标）
        if (taskType.startsWith("world.") || taskType.startsWith("block.") || taskType.startsWith("chunk.")) {
            return worldOwnedHere(taskType, params);
        }
        // event.complete：归属 = 事件目标（事件桥派发时记录的 key，uuid:/world:）
        if (taskType.equals("event.complete")) {
            var et = params.has("eventId") ? params.get("eventId").getAsString() : "";
            var target = FoliaEventBridge.targetOfEvent(et);
            return target != null && TargetKey.ownedHere(target);
        }
        return false; // 全局类（server/material/command/event.subscribe 等）与未知任务
    }

    /** 实体家族归属：uuid（36 位含连字符）按实体解析，名字按玩家解析。 */
    private static boolean entityOwnedHere(JsonObject p) {
        var id = p.has("uuid") ? p.get("uuid").getAsString() : (p.has("identifier") ? p.get("identifier").getAsString() : null);
        if (id == null || id.isEmpty()) return false;
        try {
            org.bukkit.entity.Entity e = id.contains("-") && id.length() == 36
                ? Bukkit.getEntity(UUID.fromString(id)) : Bukkit.getPlayer(id);
            return e != null && Bukkit.isOwnedByCurrentRegion(e);
        } catch (Exception ignored) {
            return false; // AsyncCatcher 拦截 = 目标属于其他 region
        }
    }

    /** 世界家族归属：区块坐标任务直接用 x/z；世界坐标任务 >>4；无坐标 → 区块 (0,0)。 */
    private static boolean worldOwnedHere(String taskType, JsonObject p) {
        if (p.has("world") && !p.get("world").isJsonNull()) {
            var name = p.get("world").getAsString();
            if (taskType.equals("world.loadChunk") || taskType.equals("world.unloadChunk") || taskType.equals("world.isChunkLoaded")) {
                return worldChunkOwnedHere(name, p.get("x").getAsInt(), p.get("z").getAsInt());
            }
            if (p.has("x") && p.has("z")) {
                return worldChunkOwnedHere(name, (int) p.get("x").getAsDouble() >> 4, (int) p.get("z").getAsDouble() >> 4);
            }
            return worldChunkOwnedHere(name, 0, 0);
        }
        // world.get* 可携带 name 参数（无 world 键，与 targetOf 分支对应）
        if (taskType.startsWith("world.get") && p.has("name") && !p.get("name").getAsString().isEmpty()) {
            return worldChunkOwnedHere(p.get("name").getAsString(), 0, 0);
        }
        return false;
    }

    private static boolean worldChunkOwnedHere(String worldName, int chunkX, int chunkZ) {
        var w = Bukkit.getWorld(worldName);
        return w != null && Bukkit.isOwnedByCurrentRegion(w, chunkX, chunkZ);
    }

    /** 任务本体（调用方已保证当前线程为目标线程）。 */
    private static Object doExecute(String taskType, JsonObject p) throws Exception {
        return switch (taskType) {
            // ── Player ──────────────────────────────────────────────
            case "player.get" -> {
                var pl = playerOrNull(p);
                yield pl != null ? Map.of("uuid", pl.getUniqueId().toString(), "name", pl.getName()) : null;
            }
            case "player.getAll" -> Bukkit.getOnlinePlayers().stream()
                .map(x -> Map.of("uuid", x.getUniqueId().toString(), "name", x.getName())).toList();
            case "player.getHealth" -> player(p).getHealth();
            case "player.setHealth" -> { player(p).setHealth(p.get("value").getAsDouble()); yield true; }
            case "player.getMaxHealth" -> player(p).getMaxHealth();
            case "player.setMaxHealth" -> { player(p).setMaxHealth(p.get("value").getAsDouble()); yield true; }
            case "player.getFood" -> player(p).getFoodLevel();
            case "player.setFood" -> { player(p).setFoodLevel(p.get("value").getAsInt()); yield true; }
            case "player.getSaturation" -> player(p).getSaturation();
            case "player.getLevel" -> player(p).getLevel();
            case "player.setLevel" -> { player(p).setLevel(p.get("value").getAsInt()); yield true; }
            case "player.getExp" -> (double) player(p).getExp();
            case "player.setExp" -> { player(p).setExp(p.get("value").getAsFloat()); yield true; }
            case "player.getTotalExperience" -> player(p).getTotalExperience();
            case "player.giveExp" -> { player(p).giveExp(p.get("amount").getAsInt()); yield true; }
            case "player.getPing" -> player(p).getPing();
            case "player.getGamemode" -> player(p).getGameMode().name();
            case "player.setGamemode" -> { player(p).setGameMode(GameMode.valueOf(p.get("value").getAsString())); yield true; }
            case "player.getDisplayName" -> player(p).getDisplayName();
            case "player.setDisplayName" -> { player(p).setDisplayName(p.has("value") && !p.get("value").isJsonNull() ? p.get("value").getAsString() : null); yield true; }
            case "player.isOp" -> player(p).isOp();
            case "player.getAllowFlight" -> player(p).getAllowFlight();
            case "player.setAllowFlight" -> { player(p).setAllowFlight(p.get("value").getAsBoolean()); yield true; }
            case "player.isFlying" -> player(p).isFlying();
            case "player.setFlying" -> { player(p).setFlying(p.get("value").getAsBoolean()); yield true; }
            case "player.isSneaking" -> player(p).isSneaking();
            case "player.isSprinting" -> player(p).isSprinting();
            case "player.getWalkSpeed" -> (double) player(p).getWalkSpeed();
            case "player.setWalkSpeed" -> { player(p).setWalkSpeed(p.get("value").getAsFloat()); yield true; }
            case "player.getFlySpeed" -> (double) player(p).getFlySpeed();
            case "player.setFlySpeed" -> { player(p).setFlySpeed(p.get("value").getAsFloat()); yield true; }
            case "player.getWorld" -> player(p).getWorld().getName();
            case "player.getLocation" -> {
                var l = player(p).getLocation();
                yield Map.of("x", l.getX(), "y", l.getY(), "z", l.getZ(), "yaw", (double) l.getYaw(), "pitch", (double) l.getPitch(), "world", l.getWorld().getName());
            }
            case "player.teleport" -> {
                player(p).teleport(new Location(Bukkit.getWorld(p.get("world").getAsString()),
                    p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble(),
                    (float) p.get("yaw").getAsDouble(), (float) p.get("pitch").getAsDouble()));
                yield true;
            }
            case "player.sendMessage" -> { player(p).sendMessage(FoliaTextUtil.parse(p.get("message"))); yield true; }
            case "player.sendActionBar" -> { player(p).sendActionBar(FoliaTextUtil.parse(p.get("message"))); yield true; }
            case "player.sendTitle" -> {
                player(p).showTitle(net.kyori.adventure.title.Title.title(
                    p.has("title") ? FoliaTextUtil.parse(p.get("title")) : Component.empty(),
                    p.has("subtitle") ? FoliaTextUtil.parse(p.get("subtitle")) : Component.empty(),
                    net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis((p.has("fadeIn") ? p.get("fadeIn").getAsInt() : 10) * 50L),
                        java.time.Duration.ofMillis((p.has("stay") ? p.get("stay").getAsInt() : 70) * 50L),
                        java.time.Duration.ofMillis((p.has("fadeOut") ? p.get("fadeOut").getAsInt() : 20) * 50L))));
                yield true;
            }
            case "player.kick" -> { player(p).kickPlayer(p.has("reason") ? p.get("reason").getAsString() : null); yield true; }
            case "player.isOnline" -> playerOrNull(p) != null;
            case "player.hasPermission" -> player(p).hasPermission(p.getAsJsonObject("permission").get("node").getAsString());
            case "player.performCommand" -> player(p).performCommand(p.get("command").getAsString());
            case "player.playSound" -> {
                var pl = player(p);
                try {
                    pl.playSound(pl.getLocation(), p.has("sound") ? p.get("sound").getAsString() : "block.note_block.pling",
                        SoundCategory.MASTER,
                        p.has("volume") ? (float) p.get("volume").getAsDouble() : 1.0f,
                        p.has("pitch") ? (float) p.get("pitch").getAsDouble() : 1.0f);
                } catch (Exception ignored) {}
                yield true;
            }
            case "player.getItemInMainHand" -> {
                var item = player(p).getInventory().getItemInMainHand();
                yield item.getType() == Material.AIR ? null
                    : Map.of("type", item.getType().getKey().toString(), "amount", item.getAmount());
            }
            case "player.getItemInOffHand" -> {
                var item = player(p).getInventory().getItemInOffHand();
                yield item.getType() == Material.AIR ? null
                    : Map.of("type", item.getType().getKey().toString(), "amount", item.getAmount());
            }
            // ── Entity ──────────────────────────────────────────────
            case "entity.get" -> {
                var e = Bukkit.getEntity(uuid(p));
                yield e != null ? Map.of("uuid", e.getUniqueId().toString(), "type", e.getType().name()) : null;
            }
            case "entity.getType" -> Bukkit.getEntity(uuid(p)).getType().name();
            case "entity.getName" -> Bukkit.getEntity(uuid(p)).getName();
            case "entity.getCustomName" -> Bukkit.getEntity(uuid(p)).getCustomName();
            case "entity.setCustomName" -> { Bukkit.getEntity(uuid(p)).setCustomName(p.has("value") && !p.get("value").isJsonNull() ? p.get("value").getAsString() : null); yield true; }
            case "entity.getHealth" -> living(p).getHealth();
            case "entity.setHealth" -> { living(p).setHealth(p.get("value").getAsDouble()); yield true; }
            case "entity.getMaxHealth" -> living(p).getMaxHealth();
            case "entity.isDead" -> Bukkit.getEntity(uuid(p)).isDead();
            case "entity.remove" -> { Bukkit.getEntity(uuid(p)).remove(); yield true; }
            case "entity.teleport" -> {
                Bukkit.getEntity(uuid(p)).teleport(new Location(Bukkit.getWorld(p.get("world").getAsString()),
                    p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble()));
                yield true;
            }
            case "entity.getLocation" -> {
                var l = Bukkit.getEntity(uuid(p)).getLocation();
                yield Map.of("x", l.getX(), "y", l.getY(), "z", l.getZ(), "yaw", (double) l.getYaw(), "pitch", (double) l.getPitch(), "world", l.getWorld().getName());
            }
            case "entity.getWorld" -> Bukkit.getEntity(uuid(p)).getWorld().getName();
            case "entity.isGlowing" -> Bukkit.getEntity(uuid(p)).isGlowing();
            case "entity.setGlowing" -> { Bukkit.getEntity(uuid(p)).setGlowing(p.get("value").getAsBoolean()); yield true; }
            case "entity.isInvulnerable" -> Bukkit.getEntity(uuid(p)).isInvulnerable();
            case "entity.setInvulnerable" -> { Bukkit.getEntity(uuid(p)).setInvulnerable(p.get("value").getAsBoolean()); yield true; }
            case "entity.isSilent" -> Bukkit.getEntity(uuid(p)).isSilent();
            case "entity.setSilent" -> { Bukkit.getEntity(uuid(p)).setSilent(p.get("value").getAsBoolean()); yield true; }
            case "entity.getPassengers" -> Bukkit.getEntity(uuid(p)).getPassengers().stream().map(e -> e.getUniqueId().toString()).toList();
            case "entity.getVehicle" -> { var v = Bukkit.getEntity(uuid(p)).getVehicle(); yield v != null ? v.getUniqueId().toString() : null; }
            // ── Potion ──────────────────────────────────────────────
            case "entity.addPotionEffect" -> {
                var effect = new PotionEffect(
                    PotionEffectType.getByName(p.get("type").getAsString().toUpperCase()),
                    p.has("duration") ? p.get("duration").getAsInt() : 200,
                    p.has("amplifier") ? p.get("amplifier").getAsInt() : 0,
                    p.has("ambient") && p.get("ambient").getAsBoolean(),
                    p.has("particles") && p.get("particles").getAsBoolean());
                living(p).addPotionEffect(effect);
                yield true;
            }
            case "entity.removePotionEffect" -> {
                var t = PotionEffectType.getByName(p.get("type").getAsString().toUpperCase());
                if (t != null) living(p).removePotionEffect(t);
                yield true;
            }
            case "entity.clearPotionEffects" -> { living(p).clearActivePotionEffects(); yield true; }
            case "entity.getActivePotionEffects" -> living(p).getActivePotionEffects().stream()
                .map(e -> Map.of("type", e.getType().getName(), "duration", e.getDuration(), "amplifier", e.getAmplifier())).toList();
            // ── PDC（在目标 region 线程上执行，实体/方块引用安全） ─────────
            case "pdc.get" -> { var h = pdcHolder(p); yield h != null ? h.getPersistentDataContainer().get(pdcKey(p), PersistentDataType.STRING) : null; }
            case "pdc.set" -> {
                var h = pdcHolder(p);
                if (h != null) h.getPersistentDataContainer().set(pdcKey(p), PersistentDataType.STRING, p.get("value").getAsString());
                yield h != null;
            }
            case "pdc.has" -> { var h = pdcHolder(p); yield h != null && h.getPersistentDataContainer().has(pdcKey(p), PersistentDataType.STRING); }
            case "pdc.remove" -> { var h = pdcHolder(p); if (h != null) h.getPersistentDataContainer().remove(pdcKey(p)); yield h != null; }
            case "pdc.keys" -> { var h = pdcHolder(p); yield h != null ? h.getPersistentDataContainer().getKeys().stream().map(NamespacedKey::toString).toList() : java.util.List.of(); }
            // ── Material（静态判断，全局） ───────────────────────────
            case "material.isSolid" -> Material.matchMaterial(p.get("type").getAsString()).isSolid();
            case "material.isLiquid" -> {
                var m = Material.matchMaterial(p.get("type").getAsString());
                yield m == Material.WATER || m == Material.LAVA;
            }
            case "material.isAir" -> Material.matchMaterial(p.get("type").getAsString()).isAir();
            case "server.getMaterials" -> Registry.MATERIAL.stream()
                .filter(m -> m.isItem()).map(m -> m.getKey().toString()).toList();
            case "server.getBlocks" -> Registry.MATERIAL.stream()
                .filter(Material::isBlock).map(m -> m.getKey().toString()).toList();
            case "server.getItems" -> Registry.MATERIAL.stream()
                .filter(Material::isItem).map(m -> m.getKey().toString()).toList();
            // ── World ───────────────────────────────────────────────
            case "world.getAll" -> Bukkit.getWorlds().stream().map(w -> Map.of("name", w.getName())).toList();
            case "world.getTime" -> world(p).getTime();
            case "world.setTime" -> { world(p).setTime(p.get("value").getAsLong()); yield true; }
            case "world.getStorm" -> world(p).hasStorm();
            case "world.setStorm" -> { world(p).setStorm(p.get("value").getAsBoolean()); yield true; }
            case "world.getDifficulty" -> world(p).getDifficulty().name();
            case "world.setDifficulty" -> { world(p).setDifficulty(Difficulty.valueOf(p.get("value").getAsString())); yield true; }
            case "world.getSpawnLocation" -> {
                var l = world(p).getSpawnLocation();
                yield Map.of("x", l.getX(), "y", l.getY(), "z", l.getZ(), "yaw", (double) l.getYaw(), "pitch", (double) l.getPitch());
            }
            case "world.setSpawnLocation" -> { world(p).setSpawnLocation(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()); yield true; }
            case "world.getGameRule" -> {
                var r = gameRule(p.get("rule").getAsString());
                yield r != null ? world(p).getGameRuleValue(r) : null;
            }
            case "world.setGameRule" -> {
                var r = gameRule(p.get("rule").getAsString());
                if (r != null) world(p).setGameRule(r, p.get("value"));
                yield true;
            }
            case "world.getBiome" -> world(p).getBiome(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()).getKey().toString();
            case "world.getHighestBlockY" -> world(p).getHighestBlockYAt(p.get("x").getAsInt(), p.get("z").getAsInt());
            case "world.isChunkLoaded" -> world(p).isChunkLoaded(p.get("x").getAsInt(), p.get("z").getAsInt());
            case "world.loadChunk" -> { world(p).loadChunk(p.get("x").getAsInt(), p.get("z").getAsInt()); yield true; }
            case "world.unloadChunk" -> world(p).unloadChunk(p.get("x").getAsInt(), p.get("z").getAsInt());
            case "world.getBlockLightLevel" -> world(p).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()).getLightFromBlocks();
            case "world.getSkyLightLevel" -> world(p).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()).getLightFromSky();
            case "world.getBlock" -> {
                var b = world(p).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
                // BlockData.getAsString() → "minecraft:stone[waterlogged=true]"，拆分 type + state
                var str = b.getBlockData().getAsString();
                var state = new LinkedHashMap<String, String>();
                var lb = str.indexOf('[');
                if (lb > 0) {
                    var inner = str.substring(lb + 1, str.indexOf(']'));
                    for (var kv : inner.split(",")) {
                        var parts = kv.split("=");
                        if (parts.length == 2) state.put(parts[0].trim(), parts[1].trim());
                    }
                    str = str.substring(0, lb);
                }
                yield Map.of("type", str, "state", state);
            }
            case "world.setBlock" -> {
                var mat = Material.matchMaterial(p.get("blockType").getAsString());
                var b = world(p).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
                if (p.has("state") && p.get("state").isJsonObject() && p.getAsJsonObject("state").size() > 0) {
                    var sb = new StringBuilder(mat.getKey().toString()).append('[');
                    var first = true;
                    for (var e : p.getAsJsonObject("state").entrySet()) {
                        if (!first) sb.append(',');
                        sb.append(e.getKey()).append('=').append(e.getValue().getAsString());
                        first = false;
                    }
                    sb.append(']');
                    b.setBlockData(Bukkit.createBlockData(sb.toString()));
                } else {
                    b.setType(mat);
                }
                yield true;
            }
            case "world.getEntities" -> world(p).getEntities().stream().map(e -> e.getUniqueId().toString()).toList();
            case "world.getPlayers" -> world(p).getPlayers().stream().map(e -> e.getUniqueId().toString()).toList();
            case "world.getNearbyEntities" -> {
                var w = world(p);
                var r = p.get("radius").getAsDouble();
                yield w.getNearbyEntities(new Location(w, p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble()), r, r, r)
                    .stream().map(e -> e.getUniqueId().toString()).toList();
            }
            case "world.dropItem" -> { world(p).dropItemNaturally(loc(p), itemOf(p)); yield true; }
            case "world.strikeLightning" -> { world(p).strikeLightning(loc(p)); yield true; }
            case "world.strikeLightningEffect" -> { world(p).strikeLightningEffect(loc(p)); yield true; }
            case "world.createExplosion" -> {
                world(p).createExplosion(loc(p), p.has("power") ? (float) p.get("power").getAsDouble() : 4.0f,
                    p.has("setFire") && p.get("setFire").getAsBoolean(),
                    !p.has("breakBlocks") || p.get("breakBlocks").getAsBoolean());
                yield true;
            }
            case "world.spawnEntity" -> world(p).spawnEntity(loc(p), EntityType.valueOf(p.get("type").getAsString().toUpperCase())).getUniqueId().toString();
            case "world.spawnItem" -> world(p).dropItem(loc(p), itemOf(p)).getUniqueId().toString();
            // ── Server / Event ───────────────────────────────────────
            case "server.broadcast" -> { Bukkit.broadcast(FoliaTextUtil.parse(p.get("message"))); yield true; }
            case "server.getMotd" -> Bukkit.getMotd();
            case "server.getTps" -> {
                // Folia 无全局 TPS 概念：三个值均返回 null，插件侧据此判断不可用
                var tps = new LinkedHashMap<String, Object>();
                tps.put("tps1m", null);
                tps.put("tps5m", null);
                tps.put("tps15m", null);
                yield tps;
            }
            case "event.subscribe" -> { FoliaEventBridge.subscribe(p); yield true; }
            case "event.unsubscribe" -> { FoliaEventBridge.unsubscribe(p); yield true; }
            case "event.complete" -> { FoliaEventBridge.complete(p); yield true; }
            case "command.register" -> { FoliaCommandBridge.register(p); yield true; }
            case "command.dispatch" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), p.get("command").getAsString());
            case "command.unregisterAll" -> { FoliaCommandBridge.unregisterAll(p.get("pluginName").getAsString()); yield true; }
            default -> throw new IllegalArgumentException("Not implemented on folia: " + taskType);
        };
    }

    private static Player player(JsonObject p) {
        var pl = playerOrNull(p);
        if (pl == null) throw new IllegalArgumentException("Player not found");
        return pl;
    }

    private static Player playerOrNull(JsonObject p) {
        var id = p.has("uuid") ? p.get("uuid").getAsString() : (p.has("identifier") ? p.get("identifier").getAsString() : null);
        if (id == null) return null;
        return resolvePlayer(id);
    }

    /** uuid（36 位含连字符）按 UUID 解析，否则按玩家名解析。 */
    private static Player resolvePlayer(String id) {
        return id.contains("-") && id.length() == 36 ? Bukkit.getPlayer(UUID.fromString(id)) : Bukkit.getPlayer(id);
    }

    private static UUID uuid(JsonObject p) {
        var id = p.has("uuid") ? p.get("uuid").getAsString() : p.get("identifier").getAsString();
        return UUID.fromString(id);
    }

    private static LivingEntity living(JsonObject p) {
        var e = Bukkit.getEntity(uuid(p));
        if (!(e instanceof LivingEntity le)) throw new IllegalArgumentException("Not a living entity");
        return le;
    }

    private static World world(JsonObject p) {
        var w = Bukkit.getWorld(p.get("world").getAsString());
        if (w == null) throw new IllegalArgumentException("World not found");
        return w;
    }

    private static Location loc(JsonObject p) {
        return new Location(world(p), p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble());
    }

    private static ItemStack itemOf(JsonObject p) {
        var mat = Material.matchMaterial(p.get("itemType").getAsString());
        if (mat == null) throw new IllegalArgumentException("Unknown item: " + p.get("itemType").getAsString());
        return new ItemStack(mat, p.has("amount") ? p.get("amount").getAsInt() : 1);
    }

    // ── GameRule（注册表，类加载时构建一次，避免逐调用反射） ─────────────

    @SuppressWarnings("rawtypes")
    private static final Map<String, org.bukkit.GameRule> GAME_RULES = new HashMap<>();
    static {
        for (var f : org.bukkit.GameRule.class.getFields()) {
            try { GAME_RULES.put(f.getName(), (org.bukkit.GameRule) f.get(null)); } catch (Exception ignored) {}
        }
    }

    /** 规则名 → GameRule（忽略大小写，raw 类型以支持 setGameRule(r, JsonElement)）；未知规则返回 null。 */
    @SuppressWarnings("rawtypes")
    private static org.bukkit.GameRule gameRule(String name) {
        return name == null ? null : GAME_RULES.get(name.toUpperCase());
    }

    // ── PDC ─────────────────────────────────────────────────────────

    /** 目标 region 线程内解析持久化数据持有者（实体或方块）。 */
    private static org.bukkit.persistence.PersistentDataHolder pdcHolder(JsonObject p) {
        if (p.has("uuid") && !p.get("uuid").isJsonNull()) {
            var e = Bukkit.getEntity(UUID.fromString(p.get("uuid").getAsString()));
            if (e instanceof org.bukkit.persistence.PersistentDataHolder h) return h;
            return null;
        }
        if (p.has("x") && p.has("world")) {
            var b = Bukkit.getWorld(p.get("world").getAsString()).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
            if (b.getState() instanceof org.bukkit.persistence.PersistentDataHolder h) return h;
            return null;
        }
        if (p.has("world")) {
            var w = Bukkit.getWorld(p.get("world").getAsString());
            if (w instanceof org.bukkit.persistence.PersistentDataHolder h) return h;
        }
        return null;
    }

    private static NamespacedKey pdcKey(JsonObject p) {
        var key = p.get("key").getAsString();
        if (key.contains(":")) {
            var parts = key.split(":", 2);
            return new NamespacedKey(parts[0].toLowerCase(), parts[1].toLowerCase());
        }
        return new NamespacedKey("yeow", key.toLowerCase());
    }
}
