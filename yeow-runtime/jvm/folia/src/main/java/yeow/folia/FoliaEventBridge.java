package yeow.folia;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import yeow.channel.SyncCallbackHelper;

import java.util.ArrayList;
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
            // PlayerDeathEvent 继承 EntityDeathEvent——此 Folia build 的事件分发会向父类型注册的
            // 监听器串扰（注册 PlayerDeathEvent 监听器会收到纯 EntityDeathEvent），故合并为一个
            // 监听器，按 instanceof 分流：玩家死亡 → playerDeath；其他实体 → entityDeath
            case "playerDeath", "entityDeath" -> pm.registerEvent(EntityDeathEvent.class, inst, EventPriority.NORMAL, (l, e) -> {
                if (e instanceof PlayerDeathEvent pe) dispatch("playerDeath", pe);
                else dispatch("entityDeath", (EntityDeathEvent) e);
            }, runtime);
            case "playerInteract" -> pm.registerEvent(PlayerInteractEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerInteract", (Event) e), runtime);
            case "playerTeleport" -> pm.registerEvent(PlayerTeleportEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("playerTeleport", (Event) e), runtime);
            case "blockBreak" -> pm.registerEvent(BlockBreakEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("blockBreak", (Event) e), runtime);
            case "inventoryOpen" -> pm.registerEvent(InventoryOpenEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("inventoryOpen", (Event) e), runtime);
            case "inventoryClick" -> pm.registerEvent(InventoryClickEvent.class, inst, EventPriority.NORMAL, (l, e) -> dispatch("inventoryClick", (Event) e), runtime);
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
        // 能精确路由到目标区块 region，而不是 (0,0) 区块）
        String target = switch (ev) {
            case PlayerJoinEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerQuitEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerChatEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerDeathEvent e -> "uuid:" + e.getEntity().getUniqueId();
            case PlayerInteractEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case PlayerTeleportEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case EntityDeathEvent e -> "uuid:" + e.getEntity().getUniqueId();
            case BlockBreakEvent e -> "world:" + e.getBlock().getWorld().getName() + ":" + e.getBlock().getX() + ":" + e.getBlock().getZ();
            case InventoryOpenEvent e -> "uuid:" + e.getPlayer().getUniqueId();
            case InventoryClickEvent e -> "uuid:" + e.getWhoClicked().getUniqueId();
            default -> null;
        };
        if (target == null) return;

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
            eventTargets.put(d.eventId(), target);
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
            case "playerDeath" -> {
                var e = (PlayerDeathEvent) ev;
                m.put("player", e.getEntity().getUniqueId().toString());
                m.put("deathMessage", e.getDeathMessage());
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
            }
        }
        m.put("_cancellable", ev instanceof org.bukkit.event.Cancellable);
        return m;
    }
}
