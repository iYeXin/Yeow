package yeow.task;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.util.CachedServerIcon;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import yeow.YeowRuntime;
import yeow.channel.SyncCallbackHelper;
import yeow.profile.instrumentation.EventMetric;
import yeow.profile.instrumentation.ProfileSink;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

public class EventBridge implements Listener {
    private static final Logger LOG = Logger.getLogger("Yeow");
    static final Gson gson = new Gson();

    private final YeowRuntime runtime;
    /** 事件类型 → 插件 → 该插件的全部回调（同一插件可对同一事件注册多个 handler）。 */
    private final Map<String, Map<String, Set<String>>> subs = new ConcurrentHashMap<>();
    /** 事件类型 → 是否已注册 Bukkit 监听器（生命周期 = EventBridge，一经注册永久保留）。 */
    private final Map<String, Boolean> reg = new ConcurrentHashMap<>();
    private volatile ProfileSink sink;
    private volatile long timeoutMs = 5000;

    public void setSink(ProfileSink s) { this.sink = s; }
    public void setTimeoutMs(long ms) { this.timeoutMs = Math.max(100, ms); }

    static final Map<String, Class<? extends Event>> EVENTS = new LinkedHashMap<>();
    static {
        EVENTS.put("playerJoin", PlayerJoinEvent.class);
        EVENTS.put("playerQuit", PlayerQuitEvent.class);
        EVENTS.put("playerChat", AsyncPlayerChatEvent.class);
        EVENTS.put("playerMove", PlayerMoveEvent.class);
        EVENTS.put("playerInteract", PlayerInteractEvent.class);
        EVENTS.put("playerCommand", PlayerCommandPreprocessEvent.class);
        EVENTS.put("playerDeath", PlayerDeathEvent.class);
        EVENTS.put("playerRespawn", PlayerRespawnEvent.class);
        EVENTS.put("playerTeleport", PlayerTeleportEvent.class);
        EVENTS.put("playerItemConsume", PlayerItemConsumeEvent.class);
        EVENTS.put("playerDropItem", PlayerDropItemEvent.class);
        EVENTS.put("playerPickupItem", PlayerPickupItemEvent.class);
        EVENTS.put("playerBucketFill", PlayerBucketFillEvent.class);
        EVENTS.put("playerBucketEmpty", PlayerBucketEmptyEvent.class);
        EVENTS.put("playerExpChange", PlayerExpChangeEvent.class);
        EVENTS.put("playerLevelChange", PlayerLevelChangeEvent.class);
        EVENTS.put("playerGameModeChange", PlayerGameModeChangeEvent.class);
        EVENTS.put("playerAdvancementDone", PlayerAdvancementDoneEvent.class);
        EVENTS.put("playerToggleSneak", PlayerToggleSneakEvent.class);
        EVENTS.put("playerToggleFlight", PlayerToggleFlightEvent.class);
        EVENTS.put("foodLevelChange", FoodLevelChangeEvent.class);
        EVENTS.put("entityDamage", EntityDamageEvent.class);
        EVENTS.put("entityDeath", EntityDeathEvent.class);
        EVENTS.put("entitySpawn", EntitySpawnEvent.class);
        EVENTS.put("entityExplode", EntityExplodeEvent.class);
        EVENTS.put("entityRegainHealth", EntityRegainHealthEvent.class);
        EVENTS.put("entityTarget", EntityTargetLivingEntityEvent.class);
        EVENTS.put("projectileLaunch", ProjectileLaunchEvent.class);
        EVENTS.put("projectileHit", ProjectileHitEvent.class);
        EVENTS.put("blockBreak", BlockBreakEvent.class);
        EVENTS.put("blockPlace", BlockPlaceEvent.class);
        EVENTS.put("blockFade", BlockFadeEvent.class);
        EVENTS.put("blockGrow", BlockGrowEvent.class);
        EVENTS.put("blockSpread", BlockSpreadEvent.class);
        EVENTS.put("blockExplode", BlockExplodeEvent.class);
        EVENTS.put("inventoryOpen", InventoryOpenEvent.class);
        EVENTS.put("inventoryClose", InventoryCloseEvent.class);
        EVENTS.put("serverPing", ServerListPingEvent.class);
        EVENTS.put("serverCommand", ServerCommandEvent.class);
        EVENTS.put("inventoryClick", InventoryClickEvent.class);
        EVENTS.put("playerResourcePackStatus", PlayerResourcePackStatusEvent.class);
    }

    public EventBridge(YeowRuntime rt) { this.runtime = rt; }

    public void subscribe(String plugin, String et, String cbId) {
        var c = EVENTS.get(et); if (c == null) { LOG.warning("Unknown event: " + et); return; }
        // 同一插件对同一事件可注册多个 handler：按插件收集到 Set，全部生效（不再覆盖）。
        subs.computeIfAbsent(et, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(plugin, k -> ConcurrentHashMap.newKeySet())
            .add(cbId);
        // 监听器一经注册永久保留（reg 只作去重标记，绝不在 unsubscribe 时移除）——
        // Bukkit 无法注销匿名监听器，若热重载后重新注册会累积多个监听器，
        // callEvent 会串行调用每个监听器（每个都走一遍完整超时等待），
        // 死循环插件场景下 N 个监听器 = N × 5s 阻塞主线程。
        // dispatch() 开头已对空订阅短路，空订阅时监听器零开销。
        if (reg.putIfAbsent(et, true) == null) {
            // AsyncPlayerChatEvent fires on a Netty thread — hop to the main thread before dispatching,
            // because dispatch() runs the scheduler tick (Bukkit API must stay on the main thread).
            // ServerListPingEvent 也在 Netty 线程触发，但**必须同步 dispatch（不 hop）**：
            // Paper 的 ping 处理同步等待事件返回后才构建响应——hop 到主线程是异步的，
            // callEvent 立即返回，响应在 dispatch 修改事件字段之前就已构建，mods（motd/icon 等）永远不生效。
            // Netty 线程自旋等待期间不调 scheduler.tick()——event.complete 由主线程的正常 tick（runTaskTimer）执行。
            var async = AsyncPlayerChatEvent.class.isAssignableFrom(c);
            Bukkit.getPluginManager().registerEvent(c, this, EventPriority.NORMAL, (l, e) -> {
                var ev = (Event) e;
                if (async && !Bukkit.isPrimaryThread()) {
                    try { Bukkit.getScheduler().runTask(runtime, () -> dispatch(ev, et)); }
                    catch (Exception ignored) { dispatch(ev, et); }
                } else {
                    dispatch(ev, et);
                }
            }, runtime);
        }
    }
    public void unsubscribe(String plugin, String et) {
        var s = subs.get(et);
        if (s != null) s.remove(plugin);
    }

    public void unsubscribeAll(String plugin) {
        subs.forEach((et, s) -> s.remove(plugin));
    }

    void dispatch(Event ev, String et) {
        var pluginMap = subs.get(et); if (pluginMap == null || pluginMap.isEmpty()) return;
        // 跳过空回调集合的插件（理论上 subscribe 后至少一个，防御性过滤）
        var active = new java.util.HashMap<String, Set<String>>();
        for (var e : pluginMap.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) active.put(e.getKey(), e.getValue());
        }
        if (active.isEmpty()) return;
        var data = eventData(ev, et); if (data == null) return;

        if (runtime.getYeowConfig().concurrentEvents())
            dispatchConcurrent(ev, et, active, data);
        else
            dispatchSerial(ev, et, active, data);
    }

    private void dispatchSerial(Event ev, String et, Map<String, Set<String>> pluginMap, Map<String,Object> data) {
        for (var entry : pluginMap.entrySet()) {
            var pn = entry.getKey();
            var pt = runtime.getPlugin(pn); if (pt == null) continue;
            for (var cb : entry.getValue()) {
                try {
                    long t0 = System.nanoTime();
                    var pend = SyncCallbackHelper.register(cb);
                    pt.postMessage(gson.toJson(Map.of("t","cb","p",cb,"r",data)));
                    long timeout = timeoutMs;
                    var deadline = System.nanoTime() + timeout * 1_000_000;
                    boolean primary = Bukkit.isPrimaryThread();
                    while (System.nanoTime() < deadline && !pend.isDone()) {
                        // 仅主线程 dispatch 时喂调度器 tick（事件完成依赖它）；
                        // 非主线程（如 serverPing 的 Netty 线程）纯自旋等待——
                        // 事件完成由主线程的正常 tick 执行，Netty 线程不能执行 Bukkit API。
                        if (primary) runtime.getScheduler().tick();
                        Thread.onSpinWait();
                    }
                    long elapsedNs = System.nanoTime() - t0;
                    boolean timedOut = !pend.isDone();
                    ProfileSink s = sink;
                    if (s != null) s.onEvent(new EventMetric(pn, et, elapsedNs, timedOut));
                    if (pend.isDone() && pend.getResult() instanceof Map<?,?> m)
                        applyMods(ev, m);
                    SyncCallbackHelper.remove(cb);
                } catch (Exception e) { LOG.warning("Event: " + e.getMessage()); }
            }
        }
    }

    private void dispatchConcurrent(Event ev, String et, Map<String, Set<String>> pluginMap, Map<String,Object> data) {
        int total = 0;
        for (var set : pluginMap.values()) total += set.size();
        if (total == 0) return;
        var latch = new CountDownLatch(total);
        long t0 = System.nanoTime();
        var startNs = new HashMap<String, Long>();
        for (var entry : pluginMap.entrySet()) {
            var pn = entry.getKey();
            var pt = runtime.getPlugin(pn);
            if (pt == null) { for (var cb : entry.getValue()) latch.countDown(); continue; }
            for (var cb : entry.getValue()) {
                startNs.put(cb, System.nanoTime());
                SyncCallbackHelper.register(cb, latch::countDown);
                pt.postMessage(gson.toJson(Map.of("t","cb","p",cb,"r",data)));
            }
        }
        long timeout = timeoutMs;
        var deadline = System.nanoTime() + timeout * 1_000_000;
        boolean primary = Bukkit.isPrimaryThread();
        while (System.nanoTime() < deadline && latch.getCount() > 0) {
            // 仅主线程 dispatch 时喂调度器 tick；非主线程纯自旋（主线程正常 tick 处理 event.complete）。
            if (primary) runtime.getScheduler().tick();
            Thread.onSpinWait();
        }
        long now = System.nanoTime();
        boolean cancelled = false;
        String motd = null, iconBase64 = null;
        Integer maxPlayers = null, numPlayers = null;
        for (var entry : pluginMap.entrySet()) {
            for (var cb : entry.getValue()) {
                var r = SyncCallbackHelper.waitFor(cb, 0);
                if (r instanceof Map<?,?> m) {
                    if (Boolean.TRUE.equals(m.get("cancelled")))
                        cancelled = true;
                    if (m.containsKey("motd"))
                        motd = TextUtil.toLegacy(TextUtil.parse(String.valueOf(m.get("motd"))));
                    if (m.containsKey("maxPlayers"))
                        maxPlayers = ((Number) m.get("maxPlayers")).intValue();
                    if (m.containsKey("numPlayers"))
                        numPlayers = ((Number) m.get("numPlayers")).intValue();
                    if (m.containsKey("icon"))
                        iconBase64 = String.valueOf(m.get("icon"));
                }
                long start = startNs.getOrDefault(cb, t0);
                ProfileSink s = sink;
                if (s != null) s.onEvent(new EventMetric(entry.getKey(), et, now - start, r == null));
                SyncCallbackHelper.remove(cb);
            }
        }
        if (cancelled && ev instanceof Cancellable c) c.setCancelled(true);
        if (ev instanceof PaperServerListPingEvent p) {
            if (motd != null) p.setMotd(motd);
            if (maxPlayers != null) p.setMaxPlayers(maxPlayers);
            if (numPlayers != null) p.setNumPlayers(numPlayers);
            if (iconBase64 != null) {
                var icon = loadPingIcon(iconBase64);
                if (icon != null) p.setServerIcon(icon);
            }
        }
    }

    @SuppressWarnings("unchecked")
    void applyMods(Event ev, Map<?,?> m) {
        if (m.containsKey("cancelled") && ev instanceof Cancellable c)
            c.setCancelled(Boolean.TRUE.equals(m.get("cancelled")));
        if (ev instanceof PaperServerListPingEvent p) {
            if (m.containsKey("motd"))
                p.setMotd(TextUtil.toLegacy(TextUtil.parse(String.valueOf(m.get("motd")))));
            if (m.containsKey("maxPlayers"))
                p.setMaxPlayers(((Number) m.get("maxPlayers")).intValue());
            if (m.containsKey("numPlayers"))
                p.setNumPlayers(((Number) m.get("numPlayers")).intValue());
            if (m.containsKey("icon")) {
                var icon = loadPingIcon(String.valueOf(m.get("icon")));
                if (icon != null) p.setServerIcon(icon);
            }
        }
    }

    /** 将 base64 PNG 解码为服务器列表图标（自动缩放至 64×64；失败返回 null，保持原图标）。 */
    static CachedServerIcon loadPingIcon(String base64) {
        try {
            var img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
            if (img == null) return null;
            if (img.getWidth() != 64 || img.getHeight() != 64) {
                var scaled = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                var g = scaled.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, 0, 0, 64, 64, null);
                g.dispose();
                img = scaled;
            }
            return Bukkit.getServer().loadServerIcon(img);
        } catch (Exception e) {
            return null;
        }
    }

    Map<String,Object> eventData(Event ev, String type) {
        var m = new HashMap<String,Object>();
        m.put("_cancellable", ev instanceof Cancellable);
        try { switch (type) {
            case "playerJoin":{ var e=(PlayerJoinEvent)ev; putP(m,e.getPlayer()); m.put("joinMessage",e.getJoinMessage()); break; }
            case "playerQuit":{ var e=(PlayerQuitEvent)ev; putP(m,e.getPlayer()); m.put("quitMessage",e.getQuitMessage()); break; }
            case "playerChat":{ var e=(AsyncPlayerChatEvent)ev; putP(m,e.getPlayer()); m.put("message",e.getMessage()); m.put("format",e.getFormat()); break; }
            case "playerMove":{ var e=(PlayerMoveEvent)ev; putP(m,e.getPlayer()); m.put("from",pos(e.getFrom())); m.put("to",pos(e.getTo())); break; }
            case "playerInteract":{ var e=(PlayerInteractEvent)ev; putP(m,e.getPlayer()); m.put("action",e.getAction().name());
                m.put("material",e.getMaterial()!=null?e.getMaterial().getKey().toString():null);
                if(e.getClickedBlock()!=null){var b=e.getClickedBlock();m.put("block",Map.of("x",b.getX(),"y",b.getY(),"z",b.getZ(),"type",b.getType().getKey().toString()));} break; }
            case "playerCommand":{ var e=(PlayerCommandPreprocessEvent)ev; putP(m,e.getPlayer()); m.put("message",e.getMessage()); break; }
            case "playerDeath":{ var e=(PlayerDeathEvent)ev; putP(m,e.getPlayer()); m.put("deathMessage",e.getDeathMessage());
                m.put("deathType",e.getDamageSource()!=null?e.getDamageSource().getDamageType().getKey().getKey():"UNKNOWN"); break; }
            case "playerRespawn":{ var e=(PlayerRespawnEvent)ev; putP(m,e.getPlayer()); m.put("respawnLocation",pos(e.getRespawnLocation())); break; }
            case "playerDropItem":{ var e=(PlayerDropItemEvent)ev; putP(m,e.getPlayer());
                m.put("itemType",e.getItemDrop().getItemStack().getType().getKey().toString()); m.put("amount",e.getItemDrop().getItemStack().getAmount()); break; }
            case "playerPickupItem":{ var e=(PlayerPickupItemEvent)ev; putP(m,e.getPlayer());
                m.put("itemType",e.getItem().getItemStack().getType().getKey().toString()); m.put("amount",e.getItem().getItemStack().getAmount()); break; }
            case "playerBucketFill":{ var e=(PlayerBucketFillEvent)ev; putP(m,e.getPlayer()); m.put("bucket",e.getBucket().getKey().toString()); break; }
            case "playerBucketEmpty":{ var e=(PlayerBucketEmptyEvent)ev; putP(m,e.getPlayer()); m.put("bucket",e.getBucket().getKey().toString()); break; }
            case "playerExpChange":{ var e=(PlayerExpChangeEvent)ev; putP(m,e.getPlayer()); m.put("amount",e.getAmount()); break; }
            case "playerLevelChange":{ var e=(PlayerLevelChangeEvent)ev; putP(m,e.getPlayer()); m.put("oldLevel",e.getOldLevel()); m.put("newLevel",e.getNewLevel()); break; }
            case "playerGameModeChange":{ var e=(PlayerGameModeChangeEvent)ev; putP(m,e.getPlayer()); m.put("newGameMode",e.getNewGameMode().name()); break; }
            case "playerAdvancementDone":{ var e=(PlayerAdvancementDoneEvent)ev; putP(m,e.getPlayer()); m.put("advancement",e.getAdvancement().getKey().toString()); break; }
            case "playerToggleSneak":{ var e=(PlayerToggleSneakEvent)ev; putP(m,e.getPlayer()); m.put("sneaking",e.isSneaking()); break; }
            case "playerToggleFlight":{ var e=(PlayerToggleFlightEvent)ev; putP(m,e.getPlayer()); m.put("flying",e.isFlying()); break; }
            case "foodLevelChange":{ var e=(FoodLevelChangeEvent)ev; putP(m,(org.bukkit.entity.Player)e.getEntity()); m.put("oldFoodLevel",e.getEntity().getFoodLevel()); m.put("newFoodLevel",e.getFoodLevel()); break; }
            case "entityDamage":{ var e=(EntityDamageEvent)ev; m.put("entity",e.getEntity().getUniqueId().toString()); m.put("damage",e.getDamage());
                m.put("cause",e.getCause().name()); m.put("entityType",e.getEntityType().name()); break; }
            case "entityDeath":{ var e=(EntityDeathEvent)ev; m.put("entity",e.getEntity().getUniqueId().toString()); m.put("entityType",e.getEntityType().name());
                m.put("entityName",e.getEntity().getName()); break; }
            case "entitySpawn":{ var e=(EntitySpawnEvent)ev; m.put("entity",e.getEntity().getUniqueId().toString()); m.put("entityType",e.getEntityType().name());
                var l=e.getLocation(); m.put("x",l.getX());m.put("y",l.getY());m.put("z",l.getZ());m.put("world",l.getWorld().getName()); break; }
            case "projectileLaunch":{ var e=(ProjectileLaunchEvent)ev; m.put("entity",e.getEntity().getUniqueId().toString());
                m.put("projectileType",e.getEntityType().name());
                if(e.getEntity().getShooter() instanceof org.bukkit.entity.LivingEntity ls) m.put("shooter",ls.getUniqueId().toString()); break; }
            case "blockBreak":{ var e=(BlockBreakEvent)ev; putP(m,e.getPlayer()); var b=e.getBlock(); m.put("block",b.getType().getKey().toString());
                m.put("x",b.getX());m.put("y",b.getY());m.put("z",b.getZ()); break; }
            case "blockPlace":{ var e=(BlockPlaceEvent)ev; putP(m,e.getPlayer()); var b=e.getBlock();
                m.put("block",b.getType().getKey().toString()); m.put("blockAgainst",e.getBlockAgainst().getType().getKey().toString());
                m.put("x",b.getX());m.put("y",b.getY());m.put("z",b.getZ()); break; }
            case "inventoryOpen":{ var e=(InventoryOpenEvent)ev; putP(m,(org.bukkit.entity.Player)e.getPlayer());
                m.put("inventoryType",e.getInventory().getType().name()); m.put("title",e.getView().getTitle()); break; }
            case "inventoryClose":{ var e=(InventoryCloseEvent)ev; putP(m,(org.bukkit.entity.Player)e.getPlayer());
                m.put("inventoryType",e.getInventory().getType().name()); break; }
            case "serverPing":{ var e=(ServerListPingEvent)ev; m.put("address",e.getAddress().toString());
                m.put("numPlayers",e.getNumPlayers()); m.put("maxPlayers",e.getMaxPlayers()); m.put("motd",e.getMotd()); break; }
            case "playerTeleport":{ var e=(PlayerTeleportEvent)ev; putP(m,e.getPlayer());
                m.put("from",pos(e.getFrom())); m.put("to",pos(e.getTo())); m.put("cause",e.getCause().name()); break; }
            case "playerItemConsume":{ var e=(PlayerItemConsumeEvent)ev; putP(m,e.getPlayer());
                m.put("itemType",e.getItem().getType().getKey().toString()); break; }
            case "entityExplode":{ var e=(EntityExplodeEvent)ev; m.put("entity",e.getEntity().getUniqueId().toString());
                m.put("entityType",e.getEntityType().name()); m.put("x",e.getLocation().getX()); m.put("y",e.getLocation().getY()); m.put("z",e.getLocation().getZ());
                m.put("blockCount",e.blockList().size()); break; }
            case "entityRegainHealth":{ var e=(EntityRegainHealthEvent)ev; m.put("entity",e.getEntity().getUniqueId().toString());
                m.put("amount",e.getAmount()); m.put("reason",e.getRegainReason().name()); break; }
            case "entityTarget":{ var e=(EntityTargetLivingEntityEvent)ev; m.put("entity",e.getEntity().getUniqueId().toString());
                m.put("target",e.getTarget()!=null?e.getTarget().getUniqueId().toString():null); break; }
            case "projectileHit":{ var e=(ProjectileHitEvent)ev; m.put("entity",e.getEntity().getUniqueId().toString());
                m.put("projectileType",e.getEntityType().name());
                m.put("hitEntity",e.getHitEntity()!=null?e.getHitEntity().getUniqueId().toString():null);
                if(e.getHitBlock()!=null){var b=e.getHitBlock();m.put("hitBlock",Map.of("x",b.getX(),"y",b.getY(),"z",b.getZ(),"type",b.getType().getKey().toString()));} break; }
            case "blockFade":{ var e=(BlockFadeEvent)ev; var b=e.getBlock(); m.put("block",b.getType().getKey().toString());
                m.put("x",b.getX());m.put("y",b.getY());m.put("z",b.getZ()); break; }
            case "blockGrow":{ var e=(BlockGrowEvent)ev; var b=e.getBlock(); m.put("block",b.getType().getKey().toString());
                m.put("x",b.getX());m.put("y",b.getY());m.put("z",b.getZ()); break; }
            case "blockSpread":{ var e=(BlockSpreadEvent)ev; var b=e.getBlock(); m.put("block",b.getType().getKey().toString());
                m.put("x",b.getX());m.put("y",b.getY());m.put("z",b.getZ()); break; }
            case "blockExplode":{ var e=(BlockExplodeEvent)ev; var b=e.getBlock(); m.put("block",b.getType().getKey().toString());
                m.put("x",b.getX());m.put("y",b.getY());m.put("z",b.getZ()); break; }
            case "serverCommand":{ var e=(ServerCommandEvent)ev; m.put("command",e.getCommand()); m.put("sender",e.getSender().getName()); break; }
            case "inventoryClick":{
                var e=(InventoryClickEvent)ev;
                putP(m,(org.bukkit.entity.Player)e.getWhoClicked());
                m.put("slot",e.getSlot());
                m.put("hotbarKey",e.getHotbarButton());
                m.put("action",e.getClick().name());
                m.put("inventoryType",e.getInventory().getType().name());
                m.put("isLeftClick",e.isLeftClick());
                m.put("isRightClick",e.isRightClick());
                m.put("isShiftClick",e.isShiftClick());
                m.put("clickedItem",e.getCurrentItem() != null && e.getCurrentItem().getType() != Material.AIR
                    ? Map.of("type",e.getCurrentItem().getType().getKey().toString(),"amount",e.getCurrentItem().getAmount()) : null);
                m.put("cursorItem",e.getCursor() != null && e.getCursor().getType() != Material.AIR
                    ? Map.of("type",e.getCursor().getType().getKey().toString(),"amount",e.getCursor().getAmount()) : null);
                break;
            }
            case "playerResourcePackStatus":{
                var e=(PlayerResourcePackStatusEvent)ev;
                putP(m,e.getPlayer());
                m.put("status",e.getStatus().name());
                m.put("hash",e.getHash() != null ? e.getHash() : "");
                break;
            }
        }} catch(Exception ignored){}
        return m;
    }

    private void putP(Map<String,Object> m, org.bukkit.entity.Player p) {
        m.put("player", p != null ? p.getUniqueId().toString() : null);
    }
    private Map<String,Object> pos(org.bukkit.Location l) {
        if(l==null) return null;
        var m=new HashMap<String,Object>(); m.put("x",l.getX());m.put("y",l.getY());m.put("z",l.getZ());
        m.put("yaw",(double)l.getYaw());m.put("pitch",(double)l.getPitch());m.put("world",l.getWorld().getName()); return m;
    }
}
