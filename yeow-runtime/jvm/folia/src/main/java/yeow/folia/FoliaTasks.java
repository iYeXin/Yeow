package yeow.folia;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.Base64;
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
    static final com.google.gson.Gson gson = new com.google.gson.Gson();

    /** GUI 实例注册表（id → Inventory）与归属（id → 插件名，purge 用）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, org.bukkit.inventory.Inventory> guis = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, String> owners = new java.util.concurrent.ConcurrentHashMap<>();
    /** Inventory → id 反查（事件桥识别自定义 Inventory，inventoryClick/Close 携带 inventoryId）。 */
    public static final java.util.concurrent.ConcurrentHashMap<org.bukkit.inventory.Inventory, String> byInv = new java.util.concurrent.ConcurrentHashMap<>();
    /** BossBar 实例注册表（id → BossBar）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, org.bukkit.boss.BossBar> bars = new java.util.concurrent.ConcurrentHashMap<>();
    /** Scoreboard 实例注册表（id → Scoreboard；未指定 board 时用主计分板）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, org.bukkit.scoreboard.Scoreboard> boards = new java.util.concurrent.ConcurrentHashMap<>();

    /** 运行时装配（FoliaRuntime.onEnable）。 */
    public static void init(FoliaScheduler s) { scheduler = s; }

    /** 卸载清理：关闭并移除插件拥有的 GUI/BossBar/Scoreboard（热重载、卸载时调用）。 */
    public static void purgePlugin(String pluginName) {
        for (var e : new java.util.ArrayList<>(owners.entrySet())) {
            if (!pluginName.equals(e.getValue())) continue;
            var id = e.getKey();
            var inv = guis.remove(id);
            owners.remove(id);
            if (inv != null) {
                for (var v : new java.util.ArrayList<>(inv.getViewers())) {
                    if (v instanceof Player pl) { try { pl.closeInventory(); } catch (Exception ignored) {} }
                }
            }
        }
        for (var e : new java.util.ArrayList<>(owners.entrySet())) {
            if (!pluginName.equals(e.getValue())) continue;
            var bb = bars.remove(e.getKey());
            owners.remove(e.getKey());
            if (bb != null) { bb.removeAll(); bb.setVisible(false); }
        }
    }

    /** 直接执行（dispatchAsync 投递后的目标线程使用；调用方已保证归属）。 */
    public static Object execute(String taskType, JsonObject params) throws Exception {
        return doExecute(taskType, params);
    }

    /**
     * 目标调度句柄（按任务家族）：调度器对任务类型一无所知，只消费：
     * <ul>
     *   <li>{@code marker} — 驻留标记（纯字符串，任意线程可计算；驻留抢占/让出/cycle 定位用）</li>
     *   <li>{@code run} — 惰性调度闭包：接收完成回调，在**全局 region 线程**解析目标后经
     *       对应 Folia 调度器投递（实体退役/解析失败 → 回调 err）。构造本身纯计算，无 Bukkit 调用。
     *       **返回取消动作**（Runnable）：取消当前生效的调度任务——调度器超时回收时调用，
     *       防止"调用方已收到超时错误但任务稍后仍执行"的幽灵执行。</li>
     * </ul>
     */
    public record DispatchTarget(String marker, java.util.function.Function<java.util.function.Consumer<Object>, Runnable> run) {}

    /**
     * 任务类型 → 目标调度句柄（三函数契约之一：ownedHere / getScheduler / execute）。
     * 家族共享实现；全局类（server/material/command/event.subscribe 等）与未知类型 → GLOBAL。
     */
    public static DispatchTarget getScheduler(String taskType, JsonObject params) {
        if (params == null) return global(taskType, params);
        // inventory 家族（统一三寻址）：uuid → 玩家 region；world+x+y+z → 容器方块 region；其余（id 寻址/create）→ GLOBAL
        if (taskType.startsWith("inventory.") && params != null
                && params.has("uuid") && !params.get("uuid").isJsonNull()) {
            var id = params.get("uuid").getAsString();
            if (id == null || id.isEmpty()) return global(taskType, params);
            return new DispatchTarget("uuid:" + id, finish -> dispatchEntity(id, taskType, params, finish));
        }
        // 实体家族（player/entity/potion/pdc-uuid/advancement/bossbar 玩家操作）：
        // 目标 = 实体/玩家所在 region
        if (taskType.startsWith("player.") || taskType.startsWith("entity.") || taskType.startsWith("potion.")
                || taskType.startsWith("advancement.")
                || taskType.equals("bossbar.addPlayer") || taskType.equals("bossbar.removePlayer")
                || (taskType.startsWith("pdc.") && params.has("uuid") && !params.get("uuid").isJsonNull())) {
            var id = params.has("uuid") ? params.get("uuid").getAsString()
                : (params.has("identifier") ? params.get("identifier").getAsString() : null);
            if (id == null || id.isEmpty()) return global(taskType, params);
            return new DispatchTarget("uuid:" + id, finish -> dispatchEntity(id, taskType, params, finish));
        }
        // 世界家族（world/block/chunk/inventory坐标 + pdc 方块/世界）：目标 = 区块 region（世界坐标 >>4，区块坐标直用）
        if (taskType.startsWith("pdc.") || taskType.startsWith("world.") || taskType.startsWith("block.")
                || taskType.startsWith("chunk.") || taskType.startsWith("inventory.")) {
            // Folia 约束：天气/时间/难度/出生点/规则等**全局状态写入**只能全局 region 线程修改
            // （AsyncCatcher 拦截）——必须 GLOBAL 目标（getTime 等读取不受限）
            if (taskType.equals("world.setTime") || taskType.equals("world.setStorm")
                    || taskType.equals("world.setThundering") || taskType.equals("world.setDifficulty")
                    || taskType.equals("world.setSpawnLocation") || taskType.equals("world.setGameRule")) {
                return global(taskType, params);
            }
            if (params.has("world") && !params.get("world").isJsonNull()) {
                var name = params.get("world").getAsString();
                // 区块坐标任务（chunk.* 快照 / loadChunk 类 / getChunkAt）：x/z 已是区块坐标，c 前缀标记
                if (taskType.startsWith("chunk.") || taskType.equals("world.loadChunk") || taskType.equals("world.unloadChunk") || taskType.equals("world.isChunkLoaded") || taskType.equals("world.getChunkAt")) {
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

    /**
     * 实体目标投递：全局 region 解析引用（AsyncCatcher 约束）→ 实体调度器执行；退役/找不到 → err。
     * 返回取消动作：取消当前生效的调度任务（超时回收防幽灵执行；已完成/退役后为 no-op）。
     */
    private static Runnable dispatchEntity(String id, String taskType, JsonObject params, java.util.function.Consumer<Object> finish) {
        var holder = new java.util.concurrent.atomic.AtomicReference<ScheduledTask>();
        Bukkit.getGlobalRegionScheduler().run(rt(), t -> {
            try {
                var entity = TargetKey.resolveEntity("uuid:" + id);
                if (entity != null) {
                    holder.set(entity.getScheduler().run(rt(), t2 -> finish.accept(execOrErr(taskType, params)),
                        () -> finish.accept(Map.of("err", "entity retired"))));
                } else {
                    finish.accept(Map.of("err", "entity not found: " + id));
                }
            } catch (Exception e) {
                finish.accept(FoliaScheduler.errObject(e, taskType));
            }
        });
        return () -> { var st = holder.get(); if (st != null) { try { st.cancel(); } catch (Exception ignored) {} } };
    }

    /**
     * 世界/区块目标投递：世界引用来自注册表（任意线程安全），直接投递区块 region。
     * 返回取消动作（同 {@link #dispatchEntity}）。
     */
    private static Runnable dispatchWorld(String name, int chunkX, int chunkZ, String taskType, JsonObject params, java.util.function.Consumer<Object> finish) {
        var w = Bukkit.getWorld(name);
        if (w == null) {
            finish.accept(Map.of("err", "world not found: " + name));
            return () -> {};
        }
        var holder = new java.util.concurrent.atomic.AtomicReference<ScheduledTask>();
        holder.set(Bukkit.getRegionScheduler().run(rt(), w, chunkX, chunkZ, t -> finish.accept(execOrErr(taskType, params))));
        return () -> { var st = holder.get(); if (st != null) { try { st.cancel(); } catch (Exception ignored) {} } };
    }

    /** event.complete 目标投递：目标为已记录的 key（uuid:/world:），按 key 解析。 */
    private static Runnable dispatchKey(String target, String taskType, JsonObject params, java.util.function.Consumer<Object> finish) {
        if (target.startsWith(TargetKey.UUID_PREFIX)) {
            return dispatchEntity(target.substring(TargetKey.UUID_PREFIX.length()), taskType, params, finish);
        } else if (target.startsWith(TargetKey.WORLD_PREFIX)) {
            var c = TargetKey.chunkCoords(target);
            return dispatchWorld(TargetKey.resolveWorld(target).getName(), c[0], c[1], taskType, params, finish);
        } else {
            finish.accept(Map.of("err", "unknown event target: " + target));
            return () -> {};
        }
    }

    /** 全局目标投递（携带任务体）：经全局 region 调度器执行。 */
    private static DispatchTarget global(String taskType, JsonObject params) {
        var holder = new java.util.concurrent.atomic.AtomicReference<ScheduledTask>();
        return new DispatchTarget(TargetKey.GLOBAL, finish -> {
            holder.set(Bukkit.getGlobalRegionScheduler().run(rt(), t -> finish.accept(execOrErr(taskType, params))));
            return () -> { var st = holder.get(); if (st != null) { try { st.cancel(); } catch (Exception ignored) {} } };
        });
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
        // inventory 家族（统一三寻址）：uuid → 实体归属；world+x+y+z → 方块归属；id 寻址恒 false（GLOBAL）
        if (taskType.startsWith("inventory.")) {
            if (params != null && params.has("uuid") && !params.get("uuid").isJsonNull()) return entityOwnedHere(params);
            if (params != null && params.has("world") && params.has("x") && params.has("z")) return worldOwnedHere(taskType, params);
            return false;
        }
        // 实体家族（player/entity/potion/pdc-uuid/advancement/bossbar 玩家操作）：
        // 归属 = 目标实体所在 region
        if (taskType.startsWith("player.") || taskType.startsWith("entity.") || taskType.startsWith("potion.")
                || taskType.startsWith("advancement.")
                || taskType.equals("bossbar.addPlayer") || taskType.equals("bossbar.removePlayer")
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
            // 全局状态写入（weather/time/difficulty 等）：归属恒 false → GLOBAL 执行
            if (taskType.equals("world.setTime") || taskType.equals("world.setStorm")
                    || taskType.equals("world.setThundering") || taskType.equals("world.setDifficulty")
                    || taskType.equals("world.setSpawnLocation") || taskType.equals("world.setGameRule")) {
                return false;
            }
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

    /** 实体家族归属：uuid（36 位含连字符）按实体解析（getEntity 不含在线玩家时回退玩家表），名字按玩家解析。 */
    private static boolean entityOwnedHere(JsonObject p) {
        var id = p.has("uuid") ? p.get("uuid").getAsString() : (p.has("identifier") ? p.get("identifier").getAsString() : null);
        if (id == null || id.isEmpty()) return false;
        try {
            org.bukkit.entity.Entity e = null;
            if (id.contains("-") && id.length() == 36) {
                var uuid = UUID.fromString(id);
                e = Bukkit.getEntity(uuid);
                if (e == null) e = Bukkit.getPlayer(uuid); // Folia：实体列表不含在线玩家
            } else {
                e = Bukkit.getPlayer(id);
            }
            return e != null && Bukkit.isOwnedByCurrentRegion(e);
        } catch (Exception ignored) {
            return false; // AsyncCatcher 拦截 = 目标属于其他 region
        }
    }

    /** 世界家族归属：区块坐标任务直接用 x/z；世界坐标任务 >>4；无坐标 → 区块 (0,0)。 */
    private static boolean worldOwnedHere(String taskType, JsonObject p) {
        if (p.has("world") && !p.get("world").isJsonNull()) {
            var name = p.get("world").getAsString();
            // 区块坐标任务（chunk.* 快照 / loadChunk 类 / getChunkAt）：x/z 已是区块坐标
            if (taskType.startsWith("chunk.") || taskType.equals("world.loadChunk") || taskType.equals("world.unloadChunk") || taskType.equals("world.isChunkLoaded") || taskType.equals("world.getChunkAt")) {
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
            // 玩家最大生命值统一走 entity.getMaxHealth/setMaxHealth（LivingEntity 语义，Player 也是实体）——
            // 不设 player.getMaxHealth/setMaxHealth，与 Paper 任务集严格一致（2026-08-13 移除历史遗留 extras）
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
            case "player.hasPermission" -> {
                var pl = player(p);
                var node = p.getAsJsonObject("permission").get("node").getAsString();
                // Yeow 生态权限检查：permissionCheck 事件结果优先，无处理时回退 Bukkit
                var r = FoliaEventBridge.checkPermission(pl.getUniqueId().toString(), node);
                yield r != null ? r : pl.hasPermission(node);
            }
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
            case "player.setItemInMainHand" -> {
                player(p).getInventory().setItemInMainHand(p.has("item") && !p.get("item").isJsonNull() ? itemFromObject(p.getAsJsonObject("item")) : new ItemStack(Material.AIR));
                yield true;
            }
            case "player.setItemInOffHand" -> {
                player(p).getInventory().setItemInOffHand(p.has("item") && !p.get("item").isJsonNull() ? itemFromObject(p.getAsJsonObject("item")) : new ItemStack(Material.AIR));
                yield true;
            }
            case "player.sendTabHeader" -> {
                var pl = player(p);
                var header = p.has("header") && !p.get("header").isJsonNull() ? FoliaTextUtil.toLegacy(FoliaTextUtil.parse(p.get("header"))) : "";
                var footer = p.has("footer") && !p.get("footer").isJsonNull() ? FoliaTextUtil.toLegacy(FoliaTextUtil.parse(p.get("footer"))) : "";
                pl.setPlayerListHeaderFooter(header, footer);
                yield true;
            }
            case "player.setPlayerListName" -> {
                player(p).setPlayerListName(p.has("name") && !p.get("name").isJsonNull() ? p.get("name").getAsString() : null);
                yield true;
            }
            case "player.setBorder" -> {
                var pl = player(p);
                try {
                    if (p.has("size") && !p.get("size").isJsonNull()) {
                        var border = Bukkit.createWorldBorder();
                        border.setCenter(0, 0);
                        border.setSize(p.get("size").getAsDouble());
                        if (p.has("centerX")) border.setCenter(p.get("centerX").getAsDouble(), p.has("centerZ") ? p.get("centerZ").getAsDouble() : 0);
                        pl.setWorldBorder(border);
                    } else {
                        pl.setWorldBorder(null);
                    }
                } catch (Exception ignored) {}
                yield true;
            }
            case "player.getBedLocation" -> {
                var l = player(p).getBedSpawnLocation();
                yield l != null ? Map.of("x", l.getX(), "y", l.getY(), "z", l.getZ(), "yaw", (double) l.getYaw(), "pitch", (double) l.getPitch(), "world", l.getWorld().getName()) : null;
            }
            case "player.stopSound" -> {
                var pl = player(p);
                try { pl.stopSound(Sound.valueOf(p.get("sound").getAsString().toUpperCase())); }
                catch (Exception e) { pl.stopAllSounds(); }
                yield true;
            }
            case "player.stopAllSounds" -> { player(p).stopAllSounds(); yield true; }
            case "player.sendResourcePack" -> {
                var pl = player(p);
                var url = p.get("url").getAsString();
                var hash = p.has("hash") && !p.get("hash").isJsonNull() ? p.get("hash").getAsString() : "";
                var prompt = p.has("prompt") && !p.get("prompt").isJsonNull() ? FoliaTextUtil.parse(p.get("prompt")) : Component.empty();
                var force = p.has("force") && p.get("force").getAsBoolean();
                try {
                    // 反射：Component 签名不可用（旧构建）时回退 String 签名
                    var method = Player.class.getMethod("setResourcePack", String.class, String.class, Component.class, boolean.class);
                    method.invoke(pl, url, hash, prompt, force);
                } catch (Exception e) {
                    pl.setResourcePack(url, hash);
                }
                yield true;
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
            case "entity.getVelocity" -> { var v = Bukkit.getEntity(uuid(p)).getVelocity(); yield Map.of("x", v.getX(), "y", v.getY(), "z", v.getZ()); }
            case "entity.setVelocity" -> { Bukkit.getEntity(uuid(p)).setVelocity(new org.bukkit.util.Vector(p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble())); yield true; }
            case "entity.getFireTicks" -> Bukkit.getEntity(uuid(p)).getFireTicks();
            case "entity.setFireTicks" -> { Bukkit.getEntity(uuid(p)).setFireTicks(p.get("value").getAsInt()); yield true; }
            case "entity.getTicksLived" -> Bukkit.getEntity(uuid(p)).getTicksLived();
            case "entity.setTicksLived" -> { Bukkit.getEntity(uuid(p)).setTicksLived(p.get("value").getAsInt()); yield true; }
            case "entity.isOnGround" -> Bukkit.getEntity(uuid(p)).isOnGround();
            case "entity.damage" -> {
                var e = living(p);
                var amount = p.get("amount").getAsDouble();
                if (p.has("damager") && !p.get("damager").isJsonNull()) {
                    var d = Bukkit.getEntity(UUID.fromString(p.get("damager").getAsString()));
                    if (d != null) { e.damage(amount, d); yield true; }
                }
                e.damage(amount);
                yield true;
            }
            // 设置目标（AI，不保证必然生效）：操作实体 = uuid；目标 = targetUuid（实体）或 world+x+y+z（位置，可带 speed）
            case "entity.setTarget" -> {
                var e = living(p);
                if (p.has("targetUuid") && !p.get("targetUuid").isJsonNull()) {
                    var t = Bukkit.getEntity(UUID.fromString(p.get("targetUuid").getAsString()));
                    if (t instanceof LivingEntity le && e instanceof org.bukkit.entity.Mob mob) mob.setTarget(le);
                    yield true;
                }
                if (p.has("world") && p.has("x") && p.has("y") && p.has("z") && e instanceof org.bukkit.entity.Mob mob) {
                    try {
                        mob.getPathfinder().moveTo(new Location(Bukkit.getWorld(p.get("world").getAsString()),
                            p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble()),
                            p.has("speed") ? p.get("speed").getAsDouble() : 1.0);
                    } catch (Exception ignored) {}
                    yield true;
                }
                yield false;
            }
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
            case "entity.setCustomNameVisible" -> { Bukkit.getEntity(uuid(p)).setCustomNameVisible(p.get("value").getAsBoolean()); yield true; }
            case "entity.hasGravity" -> Bukkit.getEntity(uuid(p)).hasGravity();
            case "entity.setGravity" -> { Bukkit.getEntity(uuid(p)).setGravity(p.get("value").getAsBoolean()); yield true; }
            case "entity.getBoundingBox" -> {
                var b = Bukkit.getEntity(uuid(p)).getBoundingBox();
                yield Map.of("minX", b.getMinX(), "minY", b.getMinY(), "minZ", b.getMinZ(), "maxX", b.getMaxX(), "maxY", b.getMaxY(), "maxZ", b.getMaxZ());
            }
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
            // ── Inventory（统一三寻址：uuid 玩家 / world+xyz 容器方块 / id 自定义） ──
            case "inventory.create" -> {
                var inv = Bukkit.createInventory(null, p.get("size").getAsInt(), FoliaTextUtil.toLegacy(FoliaTextUtil.parse(p.get("title"))));
                var id = p.get("id").getAsString();
                guis.put(id, inv);
                owners.put(id, p.has("_plugin") ? p.get("_plugin").getAsString() : "");
                byInv.put(inv, id);
                yield id;
            }
            case "inventory.destroy" -> {
                var inv = guis.remove(p.get("id").getAsString());
                owners.remove(p.get("id").getAsString());
                if (inv != null) {
                    byInv.remove(inv);
                    for (var v : new java.util.ArrayList<>(inv.getViewers())) {
                        if (v instanceof Player pl) { try { pl.closeInventory(); } catch (Exception ignored) {} }
                    }
                }
                yield true;
            }
            case "inventory.open" -> {
                var inv = guis.get(p.get("id").getAsString());
                if (inv == null) yield false;
                var pl = player(p);
                pl.openInventory(inv);
                yield true;
            }
            case "inventory.close" -> {
                var inv = guis.get(p.get("id").getAsString());
                if (inv == null) yield false;
                for (var v : new java.util.ArrayList<>(inv.getViewers())) {
                    if (v instanceof Player pl) { try { pl.closeInventory(); } catch (Exception ignored) {} }
                }
                yield true;
            }
            case "inventory.closePlayer" -> {
                var inv = guis.get(p.get("id").getAsString());
                if (inv == null) yield false;
                var pl = playerOrNull(p);
                if (pl == null) yield false;
                try { pl.closeInventory(); } catch (Exception ignored) {}
                yield true;
            }
            case "inventory.getViewers" -> {
                var inv = guis.get(p.get("id").getAsString());
                if (inv == null) yield java.util.List.of();
                yield inv.getViewers().stream().filter(v -> v instanceof Player)
                    .map(v -> ((Player) v).getUniqueId().toString()).toList();
            }
            case "inventory.getSize" -> invOf(p).getSize();
            case "inventory.getContents" -> {
                var inv = invOf(p);
                var out = new java.util.ArrayList<Object>();
                for (int i = 0; i < inv.getSize(); i++) {
                    var item = inv.getItem(i);
                    out.add(item != null && item.getType() != Material.AIR ? serializeItem(item) : null);
                }
                yield out;
            }
            case "inventory.setContents" -> {
                var inv = invOf(p);
                var items = p.getAsJsonArray("items");
                for (int i = 0; i < Math.min(items.size(), inv.getSize()); i++) {
                    var el = items.get(i);
                    inv.setItem(i, el.isJsonNull() ? null : itemFromObject(el.getAsJsonObject()));
                }
                yield true;
            }
            case "inventory.getType" -> typeOf(p);
            case "inventory.getItem" -> {
                var item = invOf(p).getItem(p.get("slot").getAsInt());
                yield item != null && item.getType() != Material.AIR ? serializeItem(item) : null;
            }
            case "inventory.setItem" -> {
                var inv = invOf(p);
                if (p.has("item") && !p.get("item").isJsonNull()) {
                    inv.setItem(p.get("slot").getAsInt(), itemFromObject(p.getAsJsonObject("item")));
                } else {
                    inv.setItem(p.get("slot").getAsInt(), null);
                }
                yield true;
            }
            case "inventory.setItems" -> {
                var inv = invOf(p);
                var item = p.has("item") && !p.get("item").isJsonNull() ? itemFromObject(p.getAsJsonObject("item")) : new ItemStack(Material.AIR);
                for (var el : p.getAsJsonArray("slots")) inv.setItem(el.getAsInt(), item.clone());
                yield true;
            }
            case "inventory.fill" -> {
                var inv = invOf(p);
                var item = itemFromObject(p.getAsJsonObject("item"));
                for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, item.clone());
                yield true;
            }
            case "inventory.clear" -> {
                var inv = invOf(p);
                if (p.has("slot")) inv.setItem(p.get("slot").getAsInt(), null);
                else inv.clear();
                yield true;
            }
            case "inventory.addItem" -> {
                var inv = invOf(p);
                var left = inv.addItem(itemFromObject(p.getAsJsonObject("item")));
                if (left.isEmpty()) yield 0;
                // 玩家物品栏：溢出掉落（对齐 Paper）；其他容器：返回未放入数量
                if (p.has("uuid") && !p.get("uuid").isJsonNull()) {
                    var pl = playerOrNull(p);
                    if (pl != null) {
                        left.values().forEach(rest -> pl.getWorld().dropItem(pl.getLocation(), rest));
                        yield 0;
                    }
                }
                yield left.values().stream().mapToInt(ItemStack::getAmount).sum();
            }
            case "inventory.removeItem" -> { invOf(p).removeItem(itemFromObject(p.getAsJsonObject("item"))); yield true; }
            // ── BossBar
            case "bossbar.create" -> {
                var bb = Bukkit.createBossBar(FoliaTextUtil.toLegacy(FoliaTextUtil.parse(p.get("title"))),
                    org.bukkit.boss.BarColor.valueOf(p.has("color") ? p.get("color").getAsString().toUpperCase() : "PURPLE"),
                    org.bukkit.boss.BarStyle.valueOf(p.has("style") ? p.get("style").getAsString().toUpperCase() : "SOLID"));
                if (p.has("progress")) bb.setProgress(p.get("progress").getAsDouble());
                if (p.has("visible") && !p.get("visible").getAsBoolean()) bb.setVisible(false);
                var id = p.get("id").getAsString();
                bars.put(id, bb);
                owners.put(id, p.has("_plugin") ? p.get("_plugin").getAsString() : "");
                yield id;
            }
            case "bossbar.destroy" -> {
                var bb = bars.remove(p.get("id").getAsString());
                owners.remove(p.get("id").getAsString());
                if (bb != null) { bb.removeAll(); bb.setVisible(false); }
                yield true;
            }
            case "bossbar.setTitle" -> { var bb = bars.get(p.get("id").getAsString()); if (bb != null) bb.setTitle(FoliaTextUtil.toLegacy(FoliaTextUtil.parse(p.get("title")))); yield true; }
            case "bossbar.setProgress" -> { var bb = bars.get(p.get("id").getAsString()); if (bb != null) bb.setProgress(p.get("progress").getAsDouble()); yield true; }
            case "bossbar.setColor" -> { var bb = bars.get(p.get("id").getAsString()); if (bb != null) bb.setColor(org.bukkit.boss.BarColor.valueOf(p.get("color").getAsString().toUpperCase())); yield true; }
            case "bossbar.setStyle" -> { var bb = bars.get(p.get("id").getAsString()); if (bb != null) bb.setStyle(org.bukkit.boss.BarStyle.valueOf(p.get("style").getAsString().toUpperCase())); yield true; }
            case "bossbar.setVisible" -> { var bb = bars.get(p.get("id").getAsString()); if (bb != null) bb.setVisible(p.get("visible").getAsBoolean()); yield true; }
            case "bossbar.addPlayer" -> { var bb = bars.get(p.get("id").getAsString()); if (bb != null) { var pl = playerOrNull(p); if (pl != null) bb.addPlayer(pl); } yield true; }
            case "bossbar.removePlayer" -> { var bb = bars.get(p.get("id").getAsString()); if (bb != null) { var pl = playerOrNull(p); if (pl != null) bb.removePlayer(pl); } yield true; }
            case "bossbar.removeAll" -> { var bb = bars.get(p.get("id").getAsString()); if (bb != null) bb.removeAll(); yield true; }
            case "bossbar.addFlag" -> { var bb = bars.get(p.get("id").getAsString()); if (bb != null) bb.addFlag(org.bukkit.boss.BarFlag.valueOf(p.get("flag").getAsString().toUpperCase())); yield true; }
            case "bossbar.removeFlag" -> { var bb = bars.get(p.get("id").getAsString()); if (bb != null) bb.removeFlag(org.bukkit.boss.BarFlag.valueOf(p.get("flag").getAsString().toUpperCase())); yield true; }
            // ── Scoreboard（Bukkit 计分板为全局对象：全部 GLOBAL；setPlayerBoard 按玩家） ──
            case "scoreboard.createBoard" -> {
                var id = p.get("id").getAsString();
                // Folia 约束：不支持独立计分板（getNewScoreboard 抛 UnsupportedOperationException）——
                // 句柄映射到主计分板（全局安全），board 参数语义保持（多句柄共享主板，实机验证 2026-08-13）
                boards.put(id, Bukkit.getScoreboardManager().getMainScoreboard());
                yield id;
            }
            case "scoreboard.deleteBoard" -> { boards.remove(p.get("id").getAsString()); yield true; }
            case "scoreboard.createObjective" -> {
                var sb = sb(p);
                var name = p.get("name").getAsString();
                var criteria = p.get("criteria").getAsString();
                var displayName = FoliaTextUtil.parse(p.get("displayName"));
                try {
                    var obj = sb.registerNewObjective(name, criteria, displayName);
                    yield Map.of("name", obj.getName(), "displayName", p.get("displayName").getAsString(), "criteria", obj.getCriteria());
                } catch (UnsupportedOperationException e) {
                    // Folia 限制：不支持注册新 objective（全部重载抛 UnsupportedOperationException，反编译确认）——
                    // 已存在的（世界保存/其他插件注册）则更新 displayName；否则返回明确错误
                    var existing = sb.getObjective(name);
                    if (existing != null) {
                        existing.setDisplayName(FoliaTextUtil.toLegacy(displayName));
                        yield Map.of("name", existing.getName(), "displayName", p.get("displayName").getAsString(), "criteria", existing.getCriteria());
                    }
                    yield Map.of("err", "Folia does not support creating new objectives");
                } catch (IllegalArgumentException e) {
                    var existing = sb.getObjective(name);
                    if (existing != null) {
                        existing.setDisplayName(FoliaTextUtil.toLegacy(displayName));
                        yield Map.of("name", existing.getName(), "displayName", p.get("displayName").getAsString(), "criteria", existing.getCriteria());
                    }
                    throw e;
                }
            }
            case "scoreboard.deleteObjective" -> { var obj = sb(p).getObjective(p.get("name").getAsString()); if (obj != null) obj.unregister(); yield true; }
            case "scoreboard.getObjectives" -> {
                yield sb(p).getObjectives().stream().map(o -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("name", o.getName());
                    m.put("criteria", o.getCriteria());
                    m.put("displaySlot", o.getDisplaySlot() != null ? o.getDisplaySlot().name() : null);
                    return m;
                }).toList();
            }
            case "scoreboard.setObjectiveDisplay" -> {
                var obj = sb(p).getObjective(p.get("name").getAsString());
                if (obj == null) yield false;
                if (p.has("slot") && !p.get("slot").isJsonNull()) obj.setDisplaySlot(org.bukkit.scoreboard.DisplaySlot.valueOf(p.get("slot").getAsString()));
                else obj.setDisplaySlot(null);
                yield true;
            }
            case "scoreboard.getScore" -> {
                var obj = sb(p).getObjective(p.get("objective").getAsString());
                if (obj == null) yield null;
                try { yield obj.getScore(p.get("entry").getAsString()).getScore(); }
                catch (Exception e) { yield null; }
            }
            case "scoreboard.setScore" -> {
                var obj = sb(p).getObjective(p.get("objective").getAsString());
                if (obj == null) yield false;
                obj.getScore(p.get("entry").getAsString()).setScore(p.get("value").getAsInt());
                yield true;
            }
            case "scoreboard.resetScore" -> { var obj = sb(p).getObjective(p.get("objective").getAsString()); if (obj != null) sb(p).resetScores(p.get("entry").getAsString()); yield true; }
            case "scoreboard.createTeam" -> {
                // Folia 限制：不支持注册新 team（registerNewTeam 抛 UnsupportedOperationException，反编译确认）
                var t2 = sb(p).getTeam(p.get("name").getAsString());
                if (t2 != null) yield Map.of("name", t2.getName());
                yield Map.of("err", "Folia does not support creating new teams");
            }
            case "scoreboard.deleteTeam" -> { var team = sb(p).getTeam(p.get("name").getAsString()); if (team != null) team.unregister(); yield true; }
            case "scoreboard.getTeam" -> { yield serializeTeam(sb(p).getTeam(p.get("name").getAsString())); }
            case "scoreboard.getTeams" -> { yield sb(p).getTeams().stream().map(FoliaTasks::serializeTeam).filter(java.util.Objects::nonNull).toList(); }
            case "scoreboard.setTeamDisplayName" -> {
                var t = sb(p).getTeam(p.get("name").getAsString());
                if (t == null) yield false;
                t.setDisplayName(FoliaTextUtil.toLegacy(FoliaTextUtil.parse(p.get("displayName"))));
                yield true;
            }
            case "scoreboard.setTeamPrefix" -> {
                var t = sb(p).getTeam(p.get("name").getAsString());
                if (t == null) yield false;
                t.setPrefix(FoliaTextUtil.toLegacy(FoliaTextUtil.parse(p.get("prefix"))));
                yield true;
            }
            case "scoreboard.setTeamSuffix" -> {
                var t = sb(p).getTeam(p.get("name").getAsString());
                if (t == null) yield false;
                t.setSuffix(FoliaTextUtil.toLegacy(FoliaTextUtil.parse(p.get("suffix"))));
                yield true;
            }
            case "scoreboard.setTeamColor" -> {
                var t = sb(p).getTeam(p.get("name").getAsString());
                if (t == null) yield false;
                t.setColor(org.bukkit.ChatColor.valueOf(p.get("color").getAsString().toUpperCase()));
                yield true;
            }
            case "scoreboard.setTeamFriendlyFire" -> {
                var t = sb(p).getTeam(p.get("name").getAsString());
                if (t == null) yield false;
                t.setAllowFriendlyFire(p.get("allow").getAsBoolean());
                yield true;
            }
            case "scoreboard.setTeamSeeInvisible" -> {
                var t = sb(p).getTeam(p.get("name").getAsString());
                if (t == null) yield false;
                t.setCanSeeFriendlyInvisibles(p.get("canSee").getAsBoolean());
                yield true;
            }
            case "scoreboard.setTeamOption" -> {
                var t = sb(p).getTeam(p.get("name").getAsString());
                if (t == null) yield false;
                t.setOption(org.bukkit.scoreboard.Team.Option.valueOf(p.get("option").getAsString().toUpperCase()),
                    org.bukkit.scoreboard.Team.OptionStatus.valueOf(p.get("value").getAsString().toUpperCase()));
                yield true;
            }
            case "scoreboard.teamAddEntry" -> {
                var t = sb(p).getTeam(p.get("name").getAsString());
                if (t == null) yield false;
                t.addEntry(p.get("entry").getAsString());
                yield true;
            }
            case "scoreboard.teamRemoveEntry" -> {
                var t = sb(p).getTeam(p.get("name").getAsString());
                if (t == null) yield false;
                t.removeEntry(p.get("entry").getAsString());
                yield true;
            }
            case "scoreboard.teamGetEntries" -> {
                var t = sb(p).getTeam(p.get("name").getAsString());
                yield t != null ? new java.util.ArrayList<>(t.getEntries()) : java.util.List.of();
            }
            case "scoreboard.setPlayerBoard" -> {
                var pl = playerOrNull(p);
                if (pl == null) yield false;
                if (p.has("board") && !p.get("board").isJsonNull()) {
                    var b = boards.get(p.get("board").getAsString());
                    if (b != null) { pl.setScoreboard(b); yield true; }
                    yield false;
                }
                pl.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                yield true;
            }
            // ── Recipe（全局） ────────────────────────────────────────
            case "recipe.add" -> {
                try {
                    var rtype = p.get("type").getAsString();
                    var nk = recipeKey(p.get("key").getAsString());
                    boolean ok = switch (rtype) {
                        case "shaped" -> { addShaped(nk, p); yield true; }
                        case "shapeless" -> { addShapeless(nk, p); yield true; }
                        case "furnace" -> { Bukkit.getServer().addRecipe(addFurnace(nk, "furnace", p)); yield true; }
                        case "blast" -> { Bukkit.getServer().addRecipe(addFurnace(nk, "blasting", p)); yield true; }
                        case "smoker" -> { Bukkit.getServer().addRecipe(addFurnace(nk, "smoking", p)); yield true; }
                        case "campfire" -> { Bukkit.getServer().addRecipe(addCampfire(nk, p)); yield true; }
                        default -> false;
                    };
                    yield ok;
                } catch (Exception e) {
                    yield Map.of("err", e.getMessage() != null ? e.getMessage() : e.toString());
                }
            }
            case "recipe.remove" -> { Bukkit.removeRecipe(recipeKey(p.get("key").getAsString())); yield true; }
            case "recipe.getForItem" -> {
                var item = itemFromObject(p.getAsJsonObject("item"));
                yield Bukkit.getRecipesFor(item).stream().map(r -> ((org.bukkit.Keyed) r).getKey().toString()).toList();
            }
            // ── Advancement（基于玩家） ────────────────────────────────
            case "advancement.grant" -> {
                var pl = player(p);
                var adv = Bukkit.getAdvancement(advKey(p.get("key").getAsString()));
                if (adv == null) yield false;
                for (var crit : adv.getCriteria()) pl.getAdvancementProgress(adv).awardCriteria(crit);
                yield true;
            }
            case "advancement.revoke" -> {
                var pl = player(p);
                var adv = Bukkit.getAdvancement(advKey(p.get("key").getAsString()));
                if (adv == null) yield false;
                for (var crit : adv.getCriteria()) pl.getAdvancementProgress(adv).revokeCriteria(crit);
                yield true;
            }
            case "advancement.getProgress" -> {
                var pl = player(p);
                var adv = Bukkit.getAdvancement(advKey(p.get("key").getAsString()));
                if (adv == null) yield null;
                var prog = pl.getAdvancementProgress(adv);
                var awarded = new java.util.ArrayList<String>();
                var remaining = new java.util.ArrayList<String>();
                for (var crit : adv.getCriteria()) {
                    if (prog.getAwardedCriteria().contains(crit)) awarded.add(crit);
                    else remaining.add(crit);
                }
                yield Map.of("awardedCriteria", awarded, "remainingCriteria", remaining);
            }
            case "advancement.awardCriteria" -> {
                var pl = player(p);
                var adv = Bukkit.getAdvancement(advKey(p.get("key").getAsString()));
                if (adv == null) yield false;
                pl.getAdvancementProgress(adv).awardCriteria(p.get("criteria").getAsString());
                yield true;
            }
            case "advancement.revokeCriteria" -> {
                var pl = player(p);
                var adv = Bukkit.getAdvancement(advKey(p.get("key").getAsString()));
                if (adv == null) yield false;
                pl.getAdvancementProgress(adv).revokeCriteria(p.get("criteria").getAsString());
                yield true;
            }
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
            case "pdc.getAll" -> {
                var h = pdcHolder(p);
                if (h == null) yield Map.of();
                var pdc = h.getPersistentDataContainer();
                var ns = pluginNamespace(p);
                var out = new LinkedHashMap<String, Object>();
                for (var k : pdc.getKeys()) {
                    if (!k.getNamespace().equals(ns)) continue;
                    var v = pdc.get(k, PersistentDataType.STRING);
                    if (v != null) out.put(k.getKey(), v);
                }
                yield out;
            }
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
            case "world.get" -> {
                var w = Bukkit.getWorld(p.get("name").getAsString());
                yield w != null ? Map.of("name", w.getName()) : null;
            }
            case "world.getAll" -> Bukkit.getWorlds().stream().map(w -> Map.of("name", w.getName())).toList();
            case "world.getTime" -> world(p).getTime();
            case "world.setTime" -> { world(p).setTime(p.get("value").getAsLong()); yield true; }
            case "world.getStorm" -> world(p).hasStorm();
            case "world.setStorm" -> { world(p).setStorm(p.get("value").getAsBoolean()); yield true; }
            case "world.getThundering" -> world(p).isThundering();
            case "world.setThundering" -> { world(p).setThundering(p.get("value").getAsBoolean()); yield true; }
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
            case "world.getSeed" -> world(p).getSeed();
            case "world.getEnvironment" -> world(p).getEnvironment().name();
            case "world.getWorldType" -> { try { yield world(p).getWorldType().name(); } catch (Exception e) { yield null; } }
            case "world.getGameRules" -> java.util.Arrays.asList(world(p).getGameRules());
            case "world.getBorder" -> {
                var b = world(p).getWorldBorder();
                var m = new LinkedHashMap<String, Object>();
                m.put("centerX", b.getCenter().getX());
                m.put("centerZ", b.getCenter().getZ());
                m.put("size", b.getSize());
                m.put("damageAmount", b.getDamageAmount());
                m.put("damageBuffer", b.getDamageBuffer());
                m.put("warningDistance", b.getWarningDistance());
                m.put("warningTime", b.getWarningTime());
                yield m;
            }
            case "world.setBorderCenter" -> { world(p).getWorldBorder().setCenter(p.get("x").getAsDouble(), p.get("z").getAsDouble()); yield true; }
            case "world.setBorderSize" -> { world(p).getWorldBorder().setSize(p.get("size").getAsDouble()); yield true; }
            case "world.setBorderDamage" -> {
                var b = world(p).getWorldBorder();
                if (p.has("amount")) b.setDamageAmount(p.get("amount").getAsDouble());
                if (p.has("buffer")) b.setDamageBuffer(p.get("buffer").getAsDouble());
                yield true;
            }
            case "world.setBorderWarning" -> {
                var b = world(p).getWorldBorder();
                if (p.has("distance")) b.setWarningDistance(p.get("distance").getAsInt());
                if (p.has("time")) b.setWarningTime(p.get("time").getAsInt());
                yield true;
            }
            case "world.setBorderMoving" -> {
                var b = world(p).getWorldBorder();
                b.setSize(p.get("from").getAsDouble());
                b.setSize(p.get("to").getAsDouble(), p.get("seconds").getAsLong());
                yield true;
            }
            case "world.isChunkLoaded" -> world(p).isChunkLoaded(p.get("x").getAsInt(), p.get("z").getAsInt());
            case "world.loadChunk" -> { world(p).loadChunk(p.get("x").getAsInt(), p.get("z").getAsInt()); yield true; }
            case "world.unloadChunk" -> world(p).unloadChunk(p.get("x").getAsInt(), p.get("z").getAsInt());
            case "world.getChunkAt" -> {
                var c = world(p).getChunkAt(p.get("x").getAsInt(), p.get("z").getAsInt());
                yield Map.of("x", c.getX(), "z", c.getZ(), "world", c.getWorld().getName());
            }
            case "world.playSound" -> {
                var w = world(p);
                try {
                    w.playSound(loc(p), p.has("sound") ? p.get("sound").getAsString() : "block.note_block.pling",
                        SoundCategory.MASTER,
                        p.has("volume") ? (float) p.get("volume").getAsDouble() : 1.0f,
                        p.has("pitch") ? (float) p.get("pitch").getAsDouble() : 1.0f);
                } catch (Exception ignored) {}
                yield true;
            }
            case "world.spawnParticle" -> { spawnParticle(p); yield true; }
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
            // ── Chunk 快照（x/z 为区块坐标；索引基准 = server.getBlocks 数组下标） ──
            case "chunk.getSnapshot" -> {
                var w = world(p);
                var chunk = w.getChunkAt(p.get("x").getAsInt(), p.get("z").getAsInt());
                var snap = chunk.getChunkSnapshot();
                int minY = w.getMinHeight();
                int height = w.getMaxHeight() - minY;
                var idx = new short[16 * 16 * height];
                int n = 0;
                for (int y = 0; y < height; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            idx[n++] = blockIndex(snap.getBlockData(x, y + minY, z).getMaterial().getKey().toString());
                        }
                    }
                }
                yield Map.of("data", encodeShorts(idx), "minY", minY, "height", height);
            }
            case "chunk.getTopSnapshot" -> {
                var w = world(p);
                int cx = p.get("x").getAsInt();
                int cz = p.get("z").getAsInt();
                var chunk = w.getChunkAt(cx, cz);
                int minY = w.getMinHeight();
                var idx = new short[256];
                var heights = new short[256];
                int n = 0;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int y = w.getHighestBlockYAt(cx * 16 + x, cz * 16 + z);
                        heights[n] = (short) y;
                        idx[n++] = y < minY ? AIR_INDEX : blockIndex(chunk.getBlock(x, y, z).getType().getKey().toString());
                    }
                }
                var out = new LinkedHashMap<String, Object>();
                out.put("data", encodeShorts(idx));
                if (p.has("withHeight") && p.get("withHeight").getAsBoolean()) out.put("height", encodeShorts(heights));
                yield out;
            }
            case "block.breakNaturally" -> {
                var b = world(p).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
                if (p.has("item") && p.get("item").isJsonObject()) yield b.breakNaturally(itemFromObject(p.getAsJsonObject("item")));
                yield b.breakNaturally();
            }
            // ── Server / Event ───────────────────────────────────────
            case "server.broadcast" -> { Bukkit.broadcast(FoliaTextUtil.parse(p.get("message"))); yield true; }
            case "server.getMotd" -> Bukkit.getMotd();
            case "server.getVersion" -> Bukkit.getVersion();
            case "server.getMaxPlayers" -> Bukkit.getMaxPlayers();
            case "server.setMotd" -> { Bukkit.getServer().setMotd(FoliaTextUtil.toLegacy(FoliaTextUtil.parse(p.get("motd")))); yield true; }
            case "server.getTps" -> {
                // Folia 无全局 TPS 概念：三个值均返回 null，插件侧据此判断不可用
                var tps = new LinkedHashMap<String, Object>();
                tps.put("tps1m", null);
                tps.put("tps5m", null);
                tps.put("tps15m", null);
                yield tps;
            }
            case "event.subscribe" -> {
                if ("permissionCheck".equals(p.get("eventType").getAsString())) {
                    FoliaEventBridge.subscribePermissionCheck(p.get("pluginName").getAsString(), p.get("callbackId").getAsString());
                } else {
                    FoliaEventBridge.subscribe(p);
                }
                yield true;
            }
            case "event.unsubscribe" -> {
                if ("permissionCheck".equals(p.get("eventType").getAsString())) {
                    FoliaEventBridge.unsubscribePermissionCheck(p.get("pluginName").getAsString());
                } else {
                    FoliaEventBridge.unsubscribe(p);
                }
                yield true;
            }
            case "event.complete" -> { FoliaEventBridge.complete(p); yield true; }
            case "command.register" -> { FoliaCommandBridge.register(p); yield true; }
            case "command.dispatch" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), p.get("command").getAsString());
            case "command.unregisterAll" -> { FoliaCommandBridge.unregisterAll(p.get("pluginName").getAsString()); yield true; }
            case "command.tabComplete" -> {
                var cb = p.get("callbackId").getAsString();
                var compsJson = p.has("completions") && !p.get("completions").isJsonNull() ? p.get("completions").toString() : "[]";
                yeow.channel.SyncCallbackHelper.complete(cb, gson.fromJson(compsJson, Object.class));
                yield true;
            }
            case "permission.register" -> {
                var node = p.get("node").getAsString();
                var def = p.has("default") && !p.get("default").isJsonNull() ? p.get("default").getAsString() : "none";
                FoliaPermissionRegistry.register(node, def);
                yield true;
            }
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
        // 无冒号 key → 插件命名空间（_plugin 任务参数，运行时注入）——跨插件裸 key 互不冲突
        return new NamespacedKey(pluginNamespace(p), key.toLowerCase());
    }

    /** 插件命名空间（`_plugin` 任务参数，运行时注入）；缺失时回退 "yeow"。 */
    private static String pluginNamespace(JsonObject p) {
        var el = p.get("_plugin");
        return el != null && !el.isJsonNull() && !el.getAsString().isEmpty() ? el.getAsString().toLowerCase() : "yeow";
    }

    // ── Particle ───────────────────────────────────────────────────

    private static void spawnParticle(JsonObject p) {
        var world = world(p);
        var particle = Particle.valueOf(p.get("particle").getAsString().toUpperCase());
        var count = p.has("count") ? p.get("count").getAsInt() : 1;
        var ox = p.has("offsetX") ? p.get("offsetX").getAsDouble() : 0.0;
        var oy = p.has("offsetY") ? p.get("offsetY").getAsDouble() : 0.0;
        var oz = p.has("offsetZ") ? p.get("offsetZ").getAsDouble() : 0.0;
        var speed = p.has("speed") ? p.get("speed").getAsDouble() : 0.0;
        var force = p.has("force") && p.get("force").getAsBoolean();
        if (p.has("color")) {
            var c = p.getAsJsonObject("color");
            var r = c.has("r") ? c.get("r").getAsInt() : 255;
            var g = c.has("g") ? c.get("g").getAsInt() : 255;
            var b = c.has("b") ? c.get("b").getAsInt() : 255;
            var size = c.has("size") ? (float) c.get("size").getAsDouble() : 1.0f;
            world.spawnParticle(particle, loc(p), count, ox, oy, oz, speed, new Particle.DustOptions(Color.fromRGB(r, g, b), size), force);
        } else if (p.has("blockType")) {
            var mat = Material.matchMaterial(p.get("blockType").getAsString());
            if (mat != null) world.spawnParticle(particle, loc(p), count, ox, oy, oz, speed, mat.createBlockData(), force);
        } else if (p.has("item") && p.get("item").isJsonObject()) {
            world.spawnParticle(particle, loc(p), count, ox, oy, oz, speed, itemFromObject(p.getAsJsonObject("item")), force);
        } else {
            world.spawnParticle(particle, loc(p), count, ox, oy, oz, speed, null, force);
        }
    }

    // ── Item 构造（协议 ItemStack 对象，含元数据；对齐 Paper GuiTasks.buildItem） ──

    private static ItemStack itemFromObject(JsonObject o) {
        if (o == null || !o.has("type")) return new ItemStack(Material.AIR);
        var mat = Material.matchMaterial(o.get("type").getAsString());
        if (mat == null) return new ItemStack(Material.AIR);
        var item = new ItemStack(mat, o.has("amount") ? o.get("amount").getAsInt() : 1);
        if (o.has("meta")) {
            var meta = item.getItemMeta();
            var m = o.getAsJsonObject("meta");
            if (m.has("displayName") && !m.get("displayName").isJsonNull())
                meta.displayName(FoliaTextUtil.parse(m.get("displayName")));
            if (m.has("lore") && m.get("lore").isJsonArray()) {
                var lore = new java.util.ArrayList<Component>();
                for (var el : m.getAsJsonArray("lore")) lore.add(FoliaTextUtil.parse(el));
                meta.lore(lore);
            }
            if (m.has("customModelData") && !m.get("customModelData").isJsonNull())
                meta.setCustomModelData(m.get("customModelData").getAsInt());
            if (m.has("unbreakable") && m.get("unbreakable").getAsBoolean())
                meta.setUnbreakable(true);
            if (m.has("hideTooltip") && m.get("hideTooltip").getAsBoolean())
                meta.setHideTooltip(true);
            if (m.has("enchantments") && m.get("enchantments").isJsonObject()) {
                for (var k : m.getAsJsonObject("enchantments").keySet()) {
                    var ench = Registry.ENCHANTMENT.get(NamespacedKey.fromString(k));
                    if (ench != null) meta.addEnchant(ench, m.getAsJsonObject("enchantments").get(k).getAsInt(), true);
                }
            }
            if (m.has("itemFlags") && m.get("itemFlags").isJsonArray()) {
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
                        var type = org.bukkit.potion.PotionEffectType.getByName(eff.get("type").getAsString().toUpperCase());
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
                        var profile = Bukkit.createProfile(java.util.UUID.randomUUID());
                        profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", owner));
                        sm.setPlayerProfile(profile);
                    } else if (owner.contains("-") && owner.length() == 36) {
                        sm.setPlayerProfile(Bukkit.getOfflinePlayer(java.util.UUID.fromString(owner)).getPlayerProfile());
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
                        var attr = org.bukkit.attribute.Attribute.valueOf(am.get("attribute").getAsString().toUpperCase());
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
    private static org.bukkit.Color colorOf(com.google.gson.JsonElement el) {
        try {
            if (el.isJsonPrimitive()) {
                var s = el.getAsString().replace("#", "");
                if (s.length() == 6) return org.bukkit.Color.fromRGB(Integer.parseInt(s, 16));
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

    /** 统一持有者解析：id（自定义）/ uuid（玩家）/ world+x+y+z（容器方块）。 */
    private static org.bukkit.inventory.Inventory invOf(JsonObject p) {
        if (p.has("id") && !p.get("id").isJsonNull()) {
            var inv = guis.get(p.get("id").getAsString());
            if (inv == null) throw new IllegalArgumentException("Inventory not found: " + p.get("id").getAsString());
            return inv;
        }
        if (p.has("uuid") && !p.get("uuid").isJsonNull()) return player(p).getInventory();
        if (p.has("world") && p.has("x") && p.has("y") && p.has("z")) {
            var b = world(p).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
            var st = b.getState();
            if (st instanceof org.bukkit.block.Container c) return c.getInventory();
            throw new IllegalArgumentException("Not a container block: " + b.getType().getKey());
        }
        throw new IllegalArgumentException("Missing inventory address (id / uuid / world+x+y+z)");
    }

    /** 持有者类型（协议 `inventory.getType`）。 */
    private static String typeOf(JsonObject p) {
        if (p.has("id") && !p.get("id").isJsonNull()) return "CUSTOM";
        if (p.has("uuid") && !p.get("uuid").isJsonNull()) return "PLAYER";
        if (p.has("world") && p.has("x") && p.has("y") && p.has("z")) {
            var b = world(p).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
            var st = b.getState();
            if (st instanceof org.bukkit.block.Container c) return c.getType().name();
            throw new IllegalArgumentException("Not a container block: " + b.getType().getKey());
        }
        throw new IllegalArgumentException("Missing inventory address (id / uuid / world+x+y+z)");
    }

    /** ItemStack → 协议快照（读回：inventory.getItem 等）。 */
    private static Object serializeItem(ItemStack item) {
        var m = new LinkedHashMap<String, Object>();
        m.put("type", item.getType().getKey().toString());
        m.put("amount", item.getAmount());
        if (item.hasItemMeta()) {
            var meta = item.getItemMeta();
            var metaMap = new LinkedHashMap<String, Object>();
            if (meta.hasDisplayName()) metaMap.put("displayName", FoliaTextUtil.toLegacy(meta.displayName()));
            if (meta.hasLore()) metaMap.put("lore", meta.lore().stream().map(FoliaTextUtil::toLegacy).toList());
            if (meta.hasCustomModelData()) metaMap.put("customModelData", meta.getCustomModelData());
            if (meta.isUnbreakable()) metaMap.put("unbreakable", true);
            if (meta.hasEnchants()) {
                var enchs = new LinkedHashMap<String, Object>();
                meta.getEnchants().forEach((ench, lvl) -> enchs.put(ench.getKey().toString(), lvl));
                metaMap.put("enchantments", enchs);
            }
            if (meta instanceof org.bukkit.inventory.meta.Damageable d && d.hasDamage()) {
                metaMap.put("damage", d.getDamage());
            }
            m.put("meta", metaMap);
        }
        return m;
    }

    // ── Chunk 快照（索引基准 = server.getBlocks 数组下标，与 Paper ChunkTasks 一致） ──

    private static volatile java.util.List<String> BLOCK_KEYS;
    private static volatile Map<String, Short> KEY_TO_INDEX;
    private static volatile short AIR_INDEX;

    private static java.util.List<String> blockKeys() {
        var keys = BLOCK_KEYS;
        if (keys != null) return keys;
        synchronized (FoliaTasks.class) {
            if (BLOCK_KEYS == null) {
                var list = new java.util.ArrayList<String>();
                var map = new HashMap<String, Short>();
                short idx = 0;
                for (var mat : Registry.MATERIAL) {
                    if (mat.isBlock()) {
                        var key = mat.getKey().toString();
                        list.add(key);
                        map.put(key, idx);
                        if ("minecraft:air".equals(key)) AIR_INDEX = idx;
                        idx++;
                    }
                }
                BLOCK_KEYS = java.util.List.copyOf(list);
                KEY_TO_INDEX = Map.copyOf(map);
            }
            return BLOCK_KEYS;
        }
    }

    private static short blockIndex(String key) {
        blockKeys(); // 确保索引缓存已初始化（可能先于任何 server.getBlocks 调用）
        return KEY_TO_INDEX.getOrDefault(key, AIR_INDEX);
    }

    /** short[] → little-endian 2 字节/元素 → base64（配合 JS 侧 Uint16Array 零拷贝视图）。 */
    private static String encodeShorts(short[] arr) {
        var bytes = new byte[arr.length * 2];
        for (int i = 0; i < arr.length; i++) {
            bytes[i * 2] = (byte) arr[i];
            bytes[i * 2 + 1] = (byte) (arr[i] >> 8);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    // ── Scoreboard ──────────────────────────────────────────────────

    private static org.bukkit.scoreboard.Scoreboard sb(JsonObject p) {
        if (p.has("board") && !p.get("board").isJsonNull()) {
            var b = boards.get(p.get("board").getAsString());
            if (b != null) return b;
        }
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    private static Object serializeTeam(org.bukkit.scoreboard.Team t) {
        if (t == null) return null;
        var m = new LinkedHashMap<String, Object>();
        m.put("name", t.getName());
        m.put("displayName", t.getDisplayName());
        m.put("prefix", t.getPrefix());
        m.put("suffix", t.getSuffix());
        m.put("color", t.getColor().name());
        m.put("allowFriendlyFire", t.allowFriendlyFire());
        m.put("canSeeFriendlyInvisibles", t.canSeeFriendlyInvisibles());
        m.put("entries", new java.util.ArrayList<>(t.getEntries()));
        var opts = new LinkedHashMap<String, String>();
        opts.put("nameTagVisibility", t.getOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY).name());
        opts.put("deathMessageVisibility", t.getOption(org.bukkit.scoreboard.Team.Option.DEATH_MESSAGE_VISIBILITY).name());
        opts.put("collisionRule", t.getOption(org.bukkit.scoreboard.Team.Option.COLLISION_RULE).name());
        m.put("options", opts);
        return m;
    }

    // ── Recipe ──────────────────────────────────────────────────────

    private static NamespacedKey recipeKey(String k) {
        if (k.contains(":")) {
            var parts = k.split(":", 2);
            return new NamespacedKey(parts[0], parts[1]);
        }
        return new NamespacedKey("yeow", k);
    }

    private static void addShaped(NamespacedKey nk, JsonObject p) {
        var result = itemFromObject(p.getAsJsonObject("result"));
        var shape = new java.util.ArrayList<String>();
        for (var el : p.getAsJsonArray("shape")) shape.add(el.getAsString());
        var recipe = new org.bukkit.inventory.ShapedRecipe(nk, result);
        recipe.shape(shape.toArray(String[]::new));
        var ings = p.getAsJsonObject("ingredients");
        for (var k : ings.keySet()) {
            var mat = Material.matchMaterial(ings.get(k).getAsString());
            if (mat != null) recipe.setIngredient(k.charAt(0), mat);
        }
        if (p.has("group")) recipe.setGroup(p.get("group").getAsString());
        Bukkit.getServer().addRecipe(recipe);
    }

    private static void addShapeless(NamespacedKey nk, JsonObject p) {
        var result = itemFromObject(p.getAsJsonObject("result"));
        var recipe = new org.bukkit.inventory.ShapelessRecipe(nk, result);
        for (var el : p.getAsJsonArray("ingredients")) {
            if (el.isJsonPrimitive()) {
                var mat = Material.matchMaterial(el.getAsString());
                if (mat != null) recipe.addIngredient(mat);
            } else if (el.isJsonObject()) {
                var obj = el.getAsJsonObject();
                var mat = Material.matchMaterial(obj.get("type").getAsString());
                var amt = obj.has("amount") ? obj.get("amount").getAsInt() : 1;
                if (mat != null) recipe.addIngredient(amt, mat);
            }
        }
        if (p.has("group")) recipe.setGroup(p.get("group").getAsString());
        Bukkit.getServer().addRecipe(recipe);
    }

    private static org.bukkit.inventory.Recipe addFurnace(NamespacedKey nk, String type, JsonObject p) {
        var input = Material.matchMaterial(p.get("input").getAsString());
        var result = itemFromObject(p.getAsJsonObject("result"));
        var exp = p.has("experience") ? (float) p.get("experience").getAsDouble() : 0.0f;
        var time = p.has("cookingTime") ? p.get("cookingTime").getAsInt() : 200;
        var recipe = new org.bukkit.inventory.FurnaceRecipe(nk, result, input, exp, time);
        recipe.setGroup(type);
        return recipe;
    }

    private static org.bukkit.inventory.Recipe addCampfire(NamespacedKey nk, JsonObject p) {
        var input = Material.matchMaterial(p.get("input").getAsString());
        var result = itemFromObject(p.getAsJsonObject("result"));
        var exp = p.has("experience") ? (float) p.get("experience").getAsDouble() : 0.0f;
        var time = p.has("cookingTime") ? p.get("cookingTime").getAsInt() : 600;
        return new org.bukkit.inventory.CampfireRecipe(nk, result, input, exp, time);
    }

    // ── Advancement（key 默认 minecraft 命名空间，对齐 Paper） ───────

    private static NamespacedKey advKey(String k) {
        if (k.contains(":")) {
            var parts = k.split(":", 2);
            return new NamespacedKey(parts[0], parts[1]);
        }
        return new NamespacedKey("minecraft", k);
    }
}
