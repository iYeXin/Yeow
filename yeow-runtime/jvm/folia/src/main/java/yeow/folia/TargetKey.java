package yeow.folia;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.UUID;

/**
 * 任务路由目标 key 工具：key 由 {@link FoliaTasks#targetOf} 生成（提交期/投递期/驻留标记），
 * 解析（实体/世界+坐标）的唯一实现点——调度器驻留与投递共用，避免各处重复解析。
 *
 * **归属判断**：日常路径用 {@link FoliaTasks#ownedHere}（按任务类型直接基于参数判定，
 * 不经 key 反推）；本类的 {@link #ownedHere(String key)} 仅作 key 的通用归属解析，
 * 供 event.complete（事件目标为派发时记录的 key）等场景复用。
 *
 * key 格式：
 * <ul>
 *   <li>{@code "uuid:&lt;id&gt;"} — 实体/玩家目标（id 为 UUID 或玩家名）</li>
 *   <li>{@code "world:&lt;name&gt;"} — 世界目标（无坐标，默认区块 0,0）</li>
 *   <li>{@code "world:&lt;name&gt;:&lt;x&gt;:&lt;z&gt;"} — 世界坐标（自动 &gt;&gt;4 转区块坐标）</li>
 *   <li>{@code "world:&lt;name&gt;:c&lt;cx&gt;:c&lt;cz&gt;"} — 区块坐标（loadChunk 类任务，c 前缀标记）</li>
 *   <li>{@code "::global"} — 全局（不属于任何 region）</li>
 * </ul>
 */
final class TargetKey {
    static final String GLOBAL = "::global";
    static final String UUID_PREFIX = "uuid:";
    static final String WORLD_PREFIX = "world:";
    private static final String CHUNK_MARK = "c";
    private static final String SEP = ":";

    private TargetKey() {}

    /** key 是否为全局目标（null 视为全局）。 */
    static boolean isGlobal(String key) {
        return key == null || GLOBAL.equals(key);
    }

    /**
     * uuid 目标解析实体（名称 fallback：非 UUID 的 identifier 按玩家名解析）。
     * 注意：必须在 owned/全局 region 线程调用——实体引用受 Folia AsyncCatcher 约束。
     * Folia 的 {@code Bukkit.getEntity} 可能不含在线玩家（实体列表与玩家列表分离）——
     * UUID 解析失败时**回退玩家表**（getPlayer(UUID)），实机验证的必要修复（2026-08-13）。
     */
    static Entity resolveEntity(String key) {
        if (key == null || !key.startsWith(UUID_PREFIX)) return null;
        var idStr = key.substring(UUID_PREFIX.length());
        if (idStr.contains("-") && idStr.length() == 36) {
            try {
                var uuid = UUID.fromString(idStr);
                var e = Bukkit.getEntity(uuid);
                if (e != null) return e;
                var pl = Bukkit.getPlayer(uuid);
                if (pl != null) return pl;
            } catch (IllegalArgumentException ignored) {}
        }
        return Bukkit.getPlayer(idStr);
    }

    /** world 目标解析世界；null = key 无效或世界不存在。 */
    static World resolveWorld(String key) {
        if (key == null || !key.startsWith(WORLD_PREFIX)) return null;
        return Bukkit.getWorld(key.substring(WORLD_PREFIX.length()).split(SEP)[0]);
    }

    /** 目标区块坐标 {cx, cz}：世界坐标 &gt;&gt;4，区块坐标原样（无坐标 → 0,0）。 */
    static int[] chunkCoords(String key) {
        if (key == null || !key.startsWith(WORLD_PREFIX)) return new int[]{ 0, 0 };
        var parts = key.substring(WORLD_PREFIX.length()).split(SEP);
        if (parts.length >= 3) {
            if (parts[1].startsWith(CHUNK_MARK)) {
                return new int[]{ Integer.parseInt(parts[1].substring(1)), Integer.parseInt(parts[2].substring(1)) };
            }
            return new int[]{ Integer.parseInt(parts[1]) >> 4, Integer.parseInt(parts[2]) >> 4 };
        }
        return new int[]{ 0, 0 };
    }

    /**
     * 当前线程是否拥有该目标（归属自证）。捕获 AsyncCatcher 拦截异常视为"非本 region"；
     * 全局/未知目标恒 false。
     */
    static boolean ownedHere(String key) {
        if (isGlobal(key)) return false;
        try {
            if (key.startsWith(UUID_PREFIX)) {
                var e = resolveEntity(key);
                return e != null && Bukkit.isOwnedByCurrentRegion(e);
            }
            if (key.startsWith(WORLD_PREFIX)) {
                var w = resolveWorld(key);
                if (w == null) return false;
                var c = chunkCoords(key);
                return Bukkit.isOwnedByCurrentRegion(w, c[0], c[1]);
            }
        } catch (Exception ignored) {
            return false; // AsyncCatcher 拦截 = 目标属于其他 region
        }
        return false;
    }
}
