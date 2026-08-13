package yeow.folia;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerListPingEvent;
import yeow.channel.SyncCallbackHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * Folia 事件桥（实验性，最小实现）。
 *
 * 关键差异：事件在 **region 线程**触发。派发流程：
 * 1. 事件线程提取字段 → 确定路由目标（事件主体的实体 uuid / 世界+坐标）
 * 2. 为每个（插件, 回调）生成独立 eventId → 投递 JS
 * 3. **自旋 pump**（SpinPump）：进入事件模式，只取本事件订阅插件的任务执行
 *    ——属于本线程的任务就地执行，跨 region 的经调度器投递
 * 4. 完成后应用 mods（cancelled），无论成功/超时都清理 pend 与 eventTargets（无泄漏）
 *
 * 自旋只阻塞本 region；其余 region 的并行事件不受影响（事件之间不互斥）。
 * 监听器**懒注册**（首次订阅该事件类型时注册，一经注册永久保留）——与 Paper
 * EventBridge 行为一致，空订阅时 dispatch 短路零开销。
 */
public class FoliaEventBridge implements Listener {
    private static final Logger LOG = Logger.getLogger("Yeow");
    static final Gson gson = new Gson();

    private static FoliaEventBridge inst;
    private static FoliaRuntime runtime;
    private static FoliaScheduler scheduler;
    /** eventType → 插件 → 回调集合（同一插件可对同一事件注册多个回调，全部生效）。 */
    private static final Map<String, Map<String, Set<String>>> subs = new ConcurrentHashMap<>();
    /** permissionCheck 订阅：插件 → 回调集合（Yeow 生态权限检查拦截，非 Bukkit 事件）。 */
    private static final Map<String, Set<String>> permSubs = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong permSeq = new java.util.concurrent.atomic.AtomicLong();
    /** eventId → 路由目标（event.complete 路由用；dispatch 结束后清理）。 */
    private static final Map<String, String> eventTargets = new ConcurrentHashMap<>();
    /** 事件类型 → 是否已注册 Bukkit 监听器（一经注册永久保留，unsubscribe 不移除）。 */
    private static final Map<String, Boolean> reg = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong dispatchSeq = new java.util.concurrent.atomic.AtomicLong();

    /** 一次 dispatch 的派发单元：eventId 按（插件, 回调）逐个生成，latch 计数与实际投递数一致。 */
    private record Dispatch(String plugin, String cb, String eventId) {}

    public static void init(FoliaRuntime rt, FoliaScheduler sched) {
        runtime = rt;
        scheduler = sched;
        inst = new FoliaEventBridge();
    }

    public static void subscribe(JsonObject p) {
        var et = p.get("eventType").getAsString();
        var plugin = p.get("pluginName").getAsString();
        var cb = p.get("callbackId").getAsString();
        subs.computeIfAbsent(et, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(plugin, k -> ConcurrentHashMap.newKeySet())
            .add(cb);
        ensureRegistered(et);
    }

    public static void unsubscribe(JsonObject p) {
        var et = p.get("eventType").getAsString();
        var plugin = p.get("pluginName").getAsString();
        var m = subs.get(et);
        if (m != null) m.remove(plugin);
    }

    public static void unsubscribeAll(String plugin) {
        subs.forEach((et, m) -> m.remove(plugin));
        permSubs.remove(plugin);
    }

    public static void subscribePermissionCheck(String plugin, String cbId) {
        permSubs.computeIfAbsent(plugin, k -> ConcurrentHashMap.newKeySet()).add(cbId);
    }

    public static void unsubscribePermissionCheck(String plugin) {
        permSubs.remove(plugin);
    }

    /**
     * Yeow 生态权限检查：触发 `permissionCheck` 事件——**仅 Yeow 生态内**（
     * `player.hasPermission` 任务与 Yeow 插件注册命令的执行检查）触发；
     * 其他 Java 插件的 hasPermission / 命令执行不会经过此检查。
     *
     * handler 返回 `{ "allowed": <bool> }` 决定结果；不返回视为未处理。
     * 多个 handler 返回结果冲突时**以最后一个返回的为准**（不保证执行顺序）。
     *
     * @return null = 无 Yeow 插件处理（调用方回退 Bukkit hasPermission）；否则为最终结果
     */
    public static Boolean checkPermission(String target, String node) {
        if (permSubs.isEmpty()) return null;
        var active = new java.util.HashMap<String, Set<String>>();
        for (var e : permSubs.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) active.put(e.getKey(), e.getValue());
        }
        if (active.isEmpty()) return null;
        int total = 0;
        for (var s : active.values()) total += s.size();
        var latch = new CountDownLatch(total);
        // permission 对象（含节点默认值）随事件投递（对齐 Paper EventBridge.checkPermission）
        var permObj = new java.util.LinkedHashMap<String, Object>();
        permObj.put("node", node);
        var def = FoliaPermissionRegistry.defaultOf(node);
        if (def != null) permObj.put("default", def);
        var data = Map.of("target", target, "node", node, "permission", permObj);
        var cbToEvent = new HashMap<String, String>();
        for (var entry : active.entrySet()) {
            var pt = runtime.core().getPlugin(entry.getKey());
            if (pt == null) { for (var cb : entry.getValue()) latch.countDown(); continue; }
            for (var cb : entry.getValue()) {
                var eventId = "perm#" + permSeq.incrementAndGet();
                cbToEvent.put(entry.getKey() + "\u0000" + cb, eventId);
                SyncCallbackHelper.register(eventId, latch::countDown);
                // 事件数据携带 _eventId（_eventId 契约）：JS 侧 event.complete 原样回传精确匹配
                var r = new java.util.HashMap<>(data);
                r.put("_eventId", eventId);
                pt.postMessage(gson.toJson(Map.of("t", "cb", "p", cb, "r", r, "eventId", eventId)));
            }
        }
        // 与事件派发同机制：进入事件模式自旋，只取订阅插件的任务（handler 的
        // event.complete 才能执行）；超时遵循普通事件配置（默认 5s）
        SpinPump.spin(scheduler, active.keySet(), () -> latch.getCount() == 0, runtime.core().config().profileCallbackTimeoutEventMs());
        Boolean result = null;
        for (var entry : active.entrySet()) {
            for (var cb : entry.getValue()) {
                var eventId = cbToEvent.get(entry.getKey() + "\u0000" + cb);
                var r = SyncCallbackHelper.waitFor(eventId, 0);
                if (r instanceof Map<?, ?> m && m.get("allowed") instanceof Boolean b) {
                    result = b; // 最后返回的为准
                }
                SyncCallbackHelper.remove(eventId);
            }
        }
        return result;
    }

    /**
     * 懒注册监听器（首次订阅时）。Bukkit 无法注销匿名监听器——注册后永久保留，
     * 空订阅时 dispatch 开头短路（零开销），与 Paper EventBridge 同一策略。
     */
    private static void ensureRegistered(String et) {
        if (reg.putIfAbsent(et, true) != null) return;
        var pm = Bukkit.getPluginManager();
        switch (et) {
            case "playerJoin" -> pm.registerEvent(PlayerJoinEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerJoin", (Event) e), runtime);
            case "playerQuit" -> pm.registerEvent(PlayerQuitEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerQuit", (Event) e), runtime);
            case "playerChat" -> pm.registerEvent(PlayerChatEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerChat", (Event) e), runtime);
            case "playerMove" -> pm.registerEvent(PlayerMoveEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerMove", (Event) e), runtime);
            case "playerCommand" -> pm.registerEvent(PlayerCommandPreprocessEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerCommand", (Event) e), runtime);
            // PlayerDeathEvent 继承 EntityDeathEvent——此 Folia build 的事件分发会向父类型注册的
            // 监听器串扰（注册 PlayerDeathEvent 监听器会收到纯 EntityDeathEvent），故合并为一个
            // 监听器，按 instanceof 分流：玩家死亡 → playerDeath；其他实体 → entityDeath
            case "playerDeath", "entityDeath" -> pm.registerEvent(EntityDeathEvent.class, inst, EventPriority.NORMAL, (l, e) -> {
                if (e instanceof PlayerDeathEvent pe) dispatch("playerDeath", pe);
                else dispatch("entityDeath", (EntityDeathEvent) e);
            }, runtime);
            case "playerInteract" -> pm.registerEvent(PlayerInteractEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerInteract", (Event) e), runtime);
            case "playerTeleport" -> pm.registerEvent(PlayerTeleportEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerTeleport", (Event) e), runtime);
            case "playerRespawn" -> pm.registerEvent(PlayerRespawnEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerRespawn", (Event) e), runtime);
            case "playerItemConsume" -> pm.registerEvent(PlayerItemConsumeEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerItemConsume", (Event) e), runtime);
            case "playerDropItem" -> pm.registerEvent(PlayerDropItemEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerDropItem", (Event) e), runtime);
            case "playerPickupItem" -> pm.registerEvent(PlayerPickupItemEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerPickupItem", (Event) e), runtime);
            case "playerBucketFill" -> pm.registerEvent(PlayerBucketFillEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerBucketFill", (Event) e), runtime);
            case "playerBucketEmpty" -> pm.registerEvent(PlayerBucketEmptyEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerBucketEmpty", (Event) e), runtime);
            case "playerExpChange" -> pm.registerEvent(PlayerExpChangeEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerExpChange", (Event) e), runtime);
            case "playerLevelChange" -> pm.registerEvent(PlayerLevelChangeEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerLevelChange", (Event) e), runtime);
            case "playerGameModeChange" -> pm.registerEvent(PlayerGameModeChangeEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerGameModeChange", (Event) e), runtime);
            case "playerAdvancementDone" -> pm.registerEvent(PlayerAdvancementDoneEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerAdvancementDone", (Event) e), runtime);
            case "playerToggleSneak" -> pm.registerEvent(PlayerToggleSneakEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerToggleSneak", (Event) e), runtime);
            case "playerToggleFlight" -> pm.registerEvent(PlayerToggleFlightEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerToggleFlight", (Event) e), runtime);
            case "playerResourcePackStatus" -> pm.registerEvent(PlayerResourcePackStatusEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerResourcePackStatus", (Event) e), runtime);
            case "foodLevelChange" -> pm.registerEvent(FoodLevelChangeEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("foodLevelChange", (Event) e), runtime);
            // 注意：此 Folia build 中 EntityExplodeEvent 与 BlockExplodeEvent 是独立类（无继承）——分别注册
            case "entityExplode" -> pm.registerEvent(EntityExplodeEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("entityExplode", (Event) e), runtime);
            case "blockExplode" -> pm.registerEvent(BlockExplodeEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("blockExplode", (Event) e), runtime);
            case "entityDamage" -> pm.registerEvent(EntityDamageEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("entityDamage", (Event) e), runtime);
            case "entitySpawn" -> pm.registerEvent(EntitySpawnEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("entitySpawn", (Event) e), runtime);
            case "entityRegainHealth" -> pm.registerEvent(EntityRegainHealthEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("entityRegainHealth", (Event) e), runtime);
            case "entityTarget" -> pm.registerEvent(EntityTargetLivingEntityEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("entityTarget", (Event) e), runtime);
            case "projectileLaunch" -> pm.registerEvent(ProjectileLaunchEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("projectileLaunch", (Event) e), runtime);
            case "projectileHit" -> pm.registerEvent(ProjectileHitEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("projectileHit", (Event) e), runtime);
            case "blockBreak" -> pm.registerEvent(BlockBreakEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("blockBreak", (Event) e), runtime);
            case "blockPlace" -> pm.registerEvent(BlockPlaceEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("blockPlace", (Event) e), runtime);
            case "blockFade" -> pm.registerEvent(BlockFadeEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("blockFade", (Event) e), runtime);
            case "blockGrow" -> pm.registerEvent(BlockGrowEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("blockGrow", (Event) e), runtime);
            case "blockSpread" -> pm.registerEvent(BlockSpreadEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("blockSpread", (Event) e), runtime);
            case "inventoryOpen" -> pm.registerEvent(InventoryOpenEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("inventoryOpen", (Event) e), runtime);
            case "inventoryClose" -> pm.registerEvent(InventoryCloseEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("inventoryClose", (Event) e), runtime);
            case "inventoryClick" -> pm.registerEvent(InventoryClickEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("inventoryClick", (Event) e), runtime);
            // serverPing：ping 线程同步触发（对齐 Paper：不 hop、mods 必须生效）；无目标 → event.complete 走 GLOBAL
            case "serverPing" -> pm.registerEvent(ServerListPingEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("serverPing", (Event) e), runtime);
            case "serverCommand" -> pm.registerEvent(ServerCommandEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("serverCommand", (Event) e), runtime);
            default -> {
                reg.remove(et); // 未实现的事件类型：不注册（对齐 Paper：订阅时告警）
                LOG.warning("Unknown event on folia: " + et);
            }
        }
    }

    /** event.complete：按 eventId 精确完成等待。 */
    public static void complete(JsonObject p) {
        var eventId = p.get("eventId").getAsString();
        var mods = p.has("mods") && !p.get("mods").isJsonNull() ? p.get("mods").toString() : "{}";
        SyncCallbackHelper.complete(eventId, gson.fromJson(mods, Object.class));
    }

    static String targetOfEvent(String eventId) {
        return eventTargets.get(eventId);
    }

    private static void dispatch(String et, Event ev) {
        var pluginMap = subs.get(et);
        if (pluginMap == null || pluginMap.isEmpty()) return;
        // 路由目标：事件主体（玩家 uuid / 方块所在世界+坐标——带坐标使 event.complete
        // 能精确路由到目标区块 region，而不是 (0,0) 区块）。serverPing/serverCommand
        // 无主体目标 → null（event.complete 回退 GLOBAL 投递）。
        String target = switch (ev) {
            // 注意：此 Folia build 的继承链影响 case 顺序——子类型必须排在父类型之前
            // （PlayerTeleportEvent extends PlayerMoveEvent；ProjectileLaunchEvent extends
            // EntitySpawnEvent；BlockSpreadEvent extends BlockFormEvent extends BlockGrowEvent）
            case PlayerJoinEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerQuitEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerChatEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerTeleportEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerMoveEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerCommandPreprocessEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerDeathEvent e -> "uuid:" + e.getEntity().getUniqueId();
            case PlayerInteractEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerRespawnEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerItemConsumeEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerDropItemEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerPickupItemEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerBucketFillEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerBucketEmptyEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerExpChangeEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerLevelChangeEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerGameModeChangeEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerAdvancementDoneEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerToggleSneakEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerToggleFlightEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerResourcePackStatusEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case FoodLevelChangeEvent e -> "uuid:" + e.getEntity().getUniqueId();
            case EntityDeathEvent e -> "uuid:" + e.getEntity().getUniqueId();
            case EntityDamageEvent e -> "uuid:" + e.getEntity().getUniqueId();
            case EntityRegainHealthEvent e -> "uuid:" + e.getEntity().getUniqueId();
            case EntityTargetLivingEntityEvent e -> "uuid:" + e.getEntity().getUniqueId();
            case ProjectileLaunchEvent e -> worldTarget(e.getEntity().getLocation());
            case EntitySpawnEvent e -> worldTarget(e.getLocation());
            case ProjectileHitEvent e -> worldTarget(e.getEntity().getLocation());
            case EntityExplodeEvent e -> worldTarget(e.getLocation());
            case BlockBreakEvent e -> "world:" + e.getBlock().getWorld().getName() + ":" + e.getBlock().getX() + ":" + e.getBlock().getZ();
            case BlockPlaceEvent e -> "world:" + e.getBlock().getWorld().getName() + ":" + e.getBlock().getX() + ":" + e.getBlock().getZ();
            case BlockFadeEvent e -> "world:" + e.getBlock().getWorld().getName() + ":" + e.getBlock().getX() + ":" + e.getBlock().getZ();
            case BlockSpreadEvent e -> "world:" + e.getBlock().getWorld().getName() + ":" + e.getBlock().getX() + ":" + e.getBlock().getZ();
            case BlockGrowEvent e -> "world:" + e.getBlock().getWorld().getName() + ":" + e.getBlock().getX() + ":" + e.getBlock().getZ();
            case BlockExplodeEvent e -> "world:" + e.getBlock().getWorld().getName() + ":" + e.getBlock().getX() + ":" + e.getBlock().getZ();
            case InventoryOpenEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case InventoryCloseEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case InventoryClickEvent e -> "uuid:" + e.getWhoClicked().getUniqueId();
            case ServerListPingEvent e -> null;
            case ServerCommandEvent e -> null;
            default -> null;
        };
        if (target == null && !"serverPing".equals(et) && !"serverCommand".equals(et)) return;

        var data = eventData(et, ev);
        // 派发单元 = （插件, 回调）逐个生成独立 eventId——同一插件多 handler 时
        // 不再共用一个 eventId（旧实现 eventIds 按插件生成、按 cb 消费，会越界且 latch 错位）
        var dispatches = new ArrayList<Dispatch>();
        var subscribed = new LinkedHashSet<String>();
        for (var entry : pluginMap.entrySet()) {
            var pn = entry.getKey();
            if (runtime.core().getPlugin(pn) == null) continue; // 跳过已卸载插件
            subscribed.add(pn);
            for (var cb : entry.getValue()) {
                dispatches.add(new Dispatch(pn, cb, et + "#" + dispatchSeq.incrementAndGet()));
            }
        }
        if (dispatches.isEmpty()) return;
        var latch = new CountDownLatch(dispatches.size());

        for (var d : dispatches) {
            // serverPing/serverCommand 无目标（null）：不记录——event.complete 回退 GLOBAL
            if (target != null) eventTargets.put(d.eventId(), target);
            SyncCallbackHelper.register(d.eventId(), latch::countDown);
            var pt = runtime.core().getPlugin(d.plugin());
            if (pt == null) { latch.countDown(); continue; }
            var r = new java.util.HashMap<>(data);
            r.put("_eventId", d.eventId());
            try {
                // 事件数据内携带 _eventId：JS 侧 event.ts 从 `data._eventId` 取事件 id 回传
                pt.postMessage(gson.toJson(Map.of("t", "cb", "p", d.cb(), "r", r, "eventId", d.eventId())));
            } catch (Exception e) {
                latch.countDown(); // 投递失败：不悬挂自旋
            }
        }

        // 自旋：只取本事件订阅插件的任务（插件 JS 单线程支点），执行器内部处理归属
        var timeoutMs = runtime.core().config().profileCallbackTimeoutEventMs();
        boolean done = SpinPump.spin(scheduler, subscribed, () -> latch.getCount() == 0, timeoutMs);
        if (!done) {
            LOG.warning("event timeout: " + et
                + " target=" + target + " pending=" + latch.getCount()
                + " (JS thread may be blocked)");
        }

        // 应用 mods + 清理（无论成功/超时/投递异常——eventTargets 与 pend 绝无泄漏）
        for (var d : dispatches) {
            var r = SyncCallbackHelper.waitFor(d.eventId(), 0);
            SyncCallbackHelper.remove(d.eventId());
            eventTargets.remove(d.eventId());
            if (r instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("cancelled")) && ev instanceof org.bukkit.event.Cancellable c) {
                c.setCancelled(true);
            }
        }
    }

    private static Map<String, Object> eventData(String et, Event ev) {
        var m = new java.util.HashMap<String, Object>();
        switch (et) {
            case "playerJoin" -> { var e = (PlayerJoinEvent) ev; m.put("player", e.getPlayer().getUniqueId().toString()); m.put("joinMessage", e.getJoinMessage()); }
            case "playerQuit" -> { var e = (PlayerQuitEvent) ev; m.put("player", e.getPlayer().getUniqueId().toString()); m.put("quitMessage", e.getQuitMessage()); }
            case "playerChat" -> { var e = (PlayerChatEvent) ev; m.put("player", e.getPlayer().getUniqueId().toString()); m.put("message", e.getMessage()); m.put("format", e.getFormat()); }
            case "playerMove" -> {
                var e = (PlayerMoveEvent) ev;
                m.put("player", e.getPlayer().getUniqueId().toString());
                m.put("from", pos(e.getFrom()));
                m.put("to", pos(e.getTo()));
            }
            case "playerCommand" -> { var e = (PlayerCommandPreprocessEvent) ev; m.put("player", e.getPlayer().getUniqueId().toString()); m.put("message", e.getMessage()); }
            case "playerDeath" -> {
                var e = (PlayerDeathEvent) ev;
                m.put("player", e.getEntity().getUniqueId().toString());
                m.put("deathMessage", e.getDeathMessage());
            }
            case "playerRespawn" -> {
                var e = (PlayerRespawnEvent) ev;
                m.put("player", e.getPlayer().getUniqueId().toString());
                m.put("respawnLocation", pos(e.getRespawnLocation()));
            }
            case "playerItemConsume" -> { var e = (PlayerItemConsumeEvent) ev; m.put("player", e.getPlayer().getUniqueId().toString()); m.put("itemType", e.getItem().getType().getKey().toString()); }
            case "playerDropItem" -> {
                var e = (PlayerDropItemEvent) ev;
                m.put("player", e.getPlayer().getUniqueId().toString());
                m.put("itemType", e.getItemDrop().getItemStack().getType().getKey().toString());
                m.put("amount", e.getItemDrop().getItemStack().getAmount());
            }
            case "playerPickupItem" -> {
                var e = (PlayerPickupItemEvent) ev;
                m.put("player", e.getPlayer().getUniqueId().toString());
                m.put("itemType", e.getItem().getItemStack().getType().getKey().toString());
                m.put("amount", e.getItem().getItemStack().getAmount());
            }
            case "playerBucketFill" -> { var e = (PlayerBucketFillEvent) ev; m.put("player", e.getPlayer().getUniqueId().toString()); m.put("bucket", e.getBucket().getKey().toString()); }
            case "playerBucketEmpty" -> { var e = (PlayerBucketEmptyEvent) ev; m.put("player", e.getPlayer().getUniqueId().toString()); m.put("bucket", e.getBucket().getKey().toString()); }
            case "playerExpChange" -> { var e = (PlayerExpChangeEvent) ev; m.put("player", e.getPlayer().getUniqueId().toString()); m.put("amount", e.getAmount()); }
            case "playerLevelChange" -> {
                var e = (PlayerLevelChangeEvent) ev;
                m.put("player", e.getPlayer().getUniqueId().toString());
                m.put("oldLevel", e.getOldLevel());
                m.put("newLevel", e.getNewLevel());
            }
            case "playerGameModeChange" -> { var e = (PlayerGameModeChangeEvent) ev; m.put("player", e.getPlayer().getUniqueId().toString()); m.put("newGameMode", e.getNewGameMode().name()); }
            case "playerAdvancementDone" -> {
                var e = (PlayerAdvancementDoneEvent) ev;
                m.put("player", e.getPlayer().getUniqueId().toString());
                m.put("advancement", e.getAdvancement().getKey().toString());
                var disp = e.getAdvancement().getDisplay();
                if (disp != null) {
                    m.put("title", componentToMessage(disp.title()));
                    m.put("description", componentToMessage(disp.description()));
                }
            }
            case "playerToggleSneak" -> { var e = (PlayerToggleSneakEvent) ev; m.put("player", e.getPlayer().getUniqueId().toString()); m.put("sneaking", e.isSneaking()); }
            case "playerToggleFlight" -> { var e = (PlayerToggleFlightEvent) ev; m.put("player", e.getPlayer().getUniqueId().toString()); m.put("flying", e.isFlying()); }
            case "playerResourcePackStatus" -> {
                var e = (PlayerResourcePackStatusEvent) ev;
                m.put("player", e.getPlayer().getUniqueId().toString());
                m.put("status", e.getStatus().name());
                m.put("hash", e.getHash() != null ? e.getHash() : "");
            }
            case "foodLevelChange" -> {
                var e = (FoodLevelChangeEvent) ev;
                m.put("player", e.getEntity().getUniqueId().toString());
                m.put("oldFoodLevel", e.getEntity().getFoodLevel());
                m.put("newFoodLevel", e.getFoodLevel());
            }
            case "playerInteract" -> {
                var e = (PlayerInteractEvent) ev;
                m.put("player", e.getPlayer().getUniqueId().toString());
                m.put("action", e.getAction().name());
                m.put("material", e.getMaterial() != null ? e.getMaterial().getKey().toString() : null);
                if (e.getClickedBlock() != null) {
                    var b = e.getClickedBlock();
                    m.put("block", Map.of("x", b.getX(), "y", b.getY(), "z", b.getZ(), "type", b.getType().getKey().toString()));
                }
            }
            case "playerTeleport" -> {
                var e = (PlayerTeleportEvent) ev;
                m.put("player", e.getPlayer().getUniqueId().toString());
                m.put("cause", e.getCause().name());
                var from = e.getFrom();
                m.put("from", Map.of("x", from.getX(), "y", from.getY(), "z", from.getZ(), "yaw", (double) from.getYaw(), "pitch", (double) from.getPitch(), "world", from.getWorld().getName()));
                var to = e.getTo();
                m.put("to", Map.of("x", to.getX(), "y", to.getY(), "z", to.getZ(), "yaw", (double) to.getYaw(), "pitch", (double) to.getPitch(), "world", to.getWorld().getName()));
            }
            case "entityDeath" -> { var e = (EntityDeathEvent) ev; m.put("entity", e.getEntity().getUniqueId().toString()); m.put("entityType", e.getEntityType().name()); }
            case "blockBreak" -> { var e = (BlockBreakEvent) ev; m.put("player", e.getPlayer().getUniqueId().toString()); m.put("block", e.getBlock().getType().getKey().toString()); m.put("x", e.getBlock().getX()); m.put("y", e.getBlock().getY()); m.put("z", e.getBlock().getZ()); }
            case "inventoryOpen" -> {
                var e = (InventoryOpenEvent) ev;
                m.put("player", e.getPlayer().getUniqueId().toString());
                m.put("inventoryType", e.getInventory().getType().name());
            }
            case "inventoryClick" -> {
                var e = (InventoryClickEvent) ev;
                m.put("player", e.getWhoClicked().getUniqueId().toString());
                m.put("slot", e.getSlot());
                m.put("action", e.getClick().name());
                m.put("inventoryType", e.getInventory().getType().name());
                m.put("isLeftClick", e.isLeftClick());
                m.put("isRightClick", e.isRightClick());
                m.put("isShiftClick", e.isShiftClick());
                var gid = FoliaTasks.byInv.get(e.getInventory());
                if (gid != null) m.put("inventoryId", gid);
            }
            case "inventoryClose" -> {
                var e = (InventoryCloseEvent) ev;
                m.put("player", e.getPlayer().getUniqueId().toString());
                m.put("inventoryType", e.getInventory().getType().name());
                var gid = FoliaTasks.byInv.get(e.getInventory());
                if (gid != null) m.put("inventoryId", gid);
            }
            case "entityDamage" -> {
                var e = (EntityDamageEvent) ev;
                m.put("entity", e.getEntity().getUniqueId().toString());
                m.put("damage", e.getDamage());
                m.put("cause", e.getCause().name());
                m.put("entityType", e.getEntityType().name());
            }
            case "entitySpawn" -> {
                var e = (EntitySpawnEvent) ev;
                m.put("entity", e.getEntity().getUniqueId().toString());
                m.put("entityType", e.getEntityType().name());
                var l = e.getLocation();
                m.put("x", l.getX()); m.put("y", l.getY()); m.put("z", l.getZ()); m.put("world", l.getWorld().getName());
            }
            case "entityExplode" -> {
                var e = (EntityExplodeEvent) ev;
                m.put("entity", e.getEntity().getUniqueId().toString());
                m.put("entityType", e.getEntityType().name());
                var l = e.getLocation();
                m.put("x", l.getX()); m.put("y", l.getY()); m.put("z", l.getZ());
                m.put("blockCount", e.blockList().size());
            }
            case "entityRegainHealth" -> {
                var e = (EntityRegainHealthEvent) ev;
                m.put("entity", e.getEntity().getUniqueId().toString());
                m.put("amount", e.getAmount());
                m.put("reason", e.getRegainReason().name());
            }
            case "entityTarget" -> {
                var e = (EntityTargetLivingEntityEvent) ev;
                m.put("entity", e.getEntity().getUniqueId().toString());
                m.put("target", e.getTarget() != null ? e.getTarget().getUniqueId().toString() : null);
            }
            case "projectileLaunch" -> {
                var e = (ProjectileLaunchEvent) ev;
                m.put("entity", e.getEntity().getUniqueId().toString());
                m.put("projectileType", e.getEntityType().name());
                if (e.getEntity().getShooter() instanceof org.bukkit.entity.LivingEntity ls) m.put("shooter", ls.getUniqueId().toString());
            }
            case "projectileHit" -> {
                var e = (ProjectileHitEvent) ev;
                m.put("entity", e.getEntity().getUniqueId().toString());
                m.put("projectileType", e.getEntityType().name());
                m.put("hitEntity", e.getHitEntity() != null ? e.getHitEntity().getUniqueId().toString() : null);
                if (e.getHitBlock() != null) {
                    var b = e.getHitBlock();
                    m.put("hitBlock", Map.of("x", b.getX(), "y", b.getY(), "z", b.getZ(), "type", b.getType().getKey().toString()));
                }
            }
            case "blockPlace" -> {
                var e = (BlockPlaceEvent) ev;
                m.put("player", e.getPlayer().getUniqueId().toString());
                var b = e.getBlock();
                m.put("block", b.getType().getKey().toString());
                m.put("blockAgainst", e.getBlockAgainst().getType().getKey().toString());
                m.put("x", b.getX()); m.put("y", b.getY()); m.put("z", b.getZ());
            }
            case "blockFade" -> { var e = (BlockFadeEvent) ev; var b = e.getBlock(); m.put("block", b.getType().getKey().toString()); m.put("x", b.getX()); m.put("y", b.getY()); m.put("z", b.getZ()); }
            case "blockGrow" -> { var e = (BlockGrowEvent) ev; var b = e.getBlock(); m.put("block", b.getType().getKey().toString()); m.put("x", b.getX()); m.put("y", b.getY()); m.put("z", b.getZ()); }
            case "blockSpread" -> { var e = (BlockSpreadEvent) ev; var b = e.getBlock(); m.put("block", b.getType().getKey().toString()); m.put("x", b.getX()); m.put("y", b.getY()); m.put("z", b.getZ()); }
            case "blockExplode" -> { var e = (BlockExplodeEvent) ev; var b = e.getBlock(); m.put("block", b.getType().getKey().toString()); m.put("x", b.getX()); m.put("y", b.getY()); m.put("z", b.getZ()); }
            case "serverPing" -> {
                var e = (ServerListPingEvent) ev;
                m.put("address", e.getAddress() != null ? e.getAddress().toString() : "");
                m.put("numPlayers", e.getNumPlayers());
                m.put("maxPlayers", e.getMaxPlayers());
                m.put("motd", e.getMotd());
            }
            case "serverCommand" -> {
                var e = (ServerCommandEvent) ev;
                m.put("command", e.getCommand());
                m.put("sender", e.getSender().getName());
            }
        }
        m.put("_cancellable", ev instanceof org.bukkit.event.Cancellable);
        return m;
    }

    // ── 辅助 ─────────────────────────────────────────────────────────

    /** 世界目标 key（带世界坐标，event.complete 精确路由到目标区块）。 */
    private static String worldTarget(org.bukkit.Location l) {
        return l != null ? "world:" + l.getWorld().getName() + ":" + (int) l.getX() + ":" + (int) l.getZ() : null;
    }

    private static Map<String, Object> pos(org.bukkit.Location l) {
        if (l == null) return null;
        var m = new java.util.HashMap<String, Object>();
        m.put("x", l.getX()); m.put("y", l.getY()); m.put("z", l.getZ());
        m.put("yaw", (double) l.getYaw()); m.put("pitch", (double) l.getPitch()); m.put("world", l.getWorld().getName());
        return m;
    }

    /**
     * Component → Message 对象（协议层可翻译组件载荷）：
     * 可翻译组件 → `{ "key", "args", "text" }`（key 本地化 + text 纯文本兜底，同时传递）；
     * 否则 → `{ "text" }`。null → null。
     */
    private static Object componentToMessage(net.kyori.adventure.text.Component c) {
        if (c == null) return null;
        var text = FoliaTextUtil.toLegacy(c);
        if (c instanceof net.kyori.adventure.text.TranslatableComponent tc) {
            var args = new java.util.ArrayList<String>();
            for (var a : tc.args()) args.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(a));
            return Map.of("key", tc.key(), "args", args, "text", text);
        }
        return Map.of("text", text);
    }
}
