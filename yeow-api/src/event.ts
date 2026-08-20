import { call } from './task.js';
import { Player } from './player.js';
import { Location } from './location.js';
import type { ItemStack } from './item.js';
import type { Message } from './message.js';

// ── Event Data Interfaces ──────────────────────────────────────────

export interface PlayerJoinEvent {
  player: Player;
  joinMessage: string;
  cancelled?: boolean;
}
export interface PlayerQuitEvent {
  player: Player;
  quitMessage: string;
  cancelled?: boolean;
}
export interface PlayerChatEvent {
  player: Player;
  message: string;
  format: string;
  cancelled?: boolean;
}
export interface PlayerMoveEvent {
  player: Player;
  from: Location;
  to: Location;
  cancelled?: boolean;
}
export interface PlayerInteractEvent {
  player: Player;
  action: string;
  material: string | null;
  block: { location: Location; type: string } | null;
  cancelled?: boolean;
}
export interface PlayerCommandEvent {
  player: Player;
  message: string;
  cancelled?: boolean;
}
export interface PlayerDeathEvent {
  player: Player;
  /** 死亡消息（Message 对象）：`{key, args}` 可翻译组件（客户端本地化）+ `{text}` 纯文本兜底，同时传递。 */
  deathMessage: Message;
  deathType: string;
  cancelled?: boolean;
}
export interface PlayerRespawnEvent {
  player: Player;
  respawnLocation: Location;
  cancelled?: boolean;
}
export interface PlayerDropItemEvent {
  player: Player;
  item: ItemStack;
  cancelled?: boolean;
}
export interface PlayerPickupItemEvent {
  player: Player;
  item: ItemStack;
  cancelled?: boolean;
}
export interface PlayerBucketFillEvent {
  player: Player;
  bucket: string;
  cancelled?: boolean;
}
export interface PlayerBucketEmptyEvent {
  player: Player;
  bucket: string;
  cancelled?: boolean;
}
export interface PlayerExpChangeEvent {
  player: Player;
  amount: number;
  cancelled?: boolean;
}
export interface PlayerLevelChangeEvent {
  player: Player;
  oldLevel: number;
  newLevel: number;
  cancelled?: boolean;
}
export interface PlayerGameModeChangeEvent {
  player: Player;
  newGameMode: string;
  cancelled?: boolean;
}
export interface FoodLevelChangeEvent {
  player: Player;
  oldFoodLevel: number;
  newFoodLevel: number;
  cancelled?: boolean;
}
export interface EntityDamageEvent {
  entity: string;
  damage: number;
  cause: string;
  entityType: string;
  cancelled?: boolean;
}
export interface EntityDeathEvent {
  entity: string;
  entityType: string;
  entityName: string;
  cancelled?: boolean;
}
export interface EntitySpawnEvent {
  entity: string;
  entityType: string;
  location: Location;
  cancelled?: boolean;
}
export interface ProjectileLaunchEvent {
  entity: string;
  projectileType: string;
  shooter?: string;
  cancelled?: boolean;
}
export interface BlockBreakEvent {
  player: Player;
  block: string;
  location: Location;
  cancelled?: boolean;
}
export interface BlockPlaceEvent {
  player: Player;
  block: string;
  blockAgainst: string;
  location: Location;
  cancelled?: boolean;
}
export interface InventoryOpenEvent {
  player: Player;
  inventoryType: string;
  title: string;
  cancelled?: boolean;
}
export interface InventoryCloseEvent {
  player: Player;
  inventoryType: string;
  /** 若关闭的是 Yeow 自定义 Inventory（Inventory.create 创建）：该 Inventory 的句柄 id（inventory.toString()）；否则缺省。 */
  inventoryId?: string;
  cancelled?: boolean;
}
export interface ServerPingEvent {
  address: string;
  /** 当前在线人数。handler 可返回 `{ numPlayers: <number> }` 覆盖该次 ping 响应显示的在线人数——**不建议修改**（伪装在线人数可能违反服务器列表政策）。 */
  numPlayers: number;
  /** 最大玩家数。handler 可返回 `{ maxPlayers: <number> }` 覆盖该次 ping 响应显示的最大玩家数——**不建议修改**（仅影响显示，不改变实际进入限制）。 */
  maxPlayers: number;
  /** MOTD 文本。handler 可返回 `{ motd: "<text>" }` 覆盖该次 ping 响应的 MOTD。 */
  motd: string;
  cancelled?: boolean;
  /**
   * handler 可返回 `{ icon: "<PNG base64>" }` 修改服务器列表图标
   * （自动缩放至 64×64；无效图片忽略，保持原图标）。
   */
  icon?: string;
}
export interface PlayerTeleportEvent {
  player: Player;
  from: Location;
  to: Location;
  cause: string;
  cancelled?: boolean;
}
export interface PlayerItemConsumeEvent {
  player: Player;
  item: ItemStack;
  cancelled?: boolean;
}
export interface PlayerAdvancementDoneEvent {
  player: Player;
  advancement: string;
  /** 进度标题（Message 对象，`{text}` 纯文本；隐藏进度时缺失）。 */
  title?: Message;
  /** 进度描述（Message 对象，`{text}` 纯文本；隐藏进度时缺失）。 */
  description?: Message;
}
export interface PlayerToggleSneakEvent {
  player: Player;
  sneaking: boolean;
}
export interface PlayerToggleFlightEvent {
  player: Player;
  flying: boolean;
}
export interface EntityExplodeEvent {
  entity: string;
  entityType: string;
  location: Location;
  blockCount: number;
  cancelled?: boolean;
}
export interface EntityRegainHealthEvent {
  entity: string;
  amount: number;
  reason: string;
  cancelled?: boolean;
}
export interface EntityTargetEvent {
  entity: string;
  target: string | null;
  cancelled?: boolean;
}
export interface ProjectileHitEvent {
  entity: string;
  projectileType: string;
  hitEntity: string | null;
  hitBlock: { location: Location; type: string } | null;
  cancelled?: boolean;
}
export interface BlockFadeEvent {
  block: string;
  location: Location;
  cancelled?: boolean;
}
export interface BlockGrowEvent {
  block: string;
  location: Location;
  cancelled?: boolean;
}
export interface BlockSpreadEvent {
  block: string;
  location: Location;
  cancelled?: boolean;
}
export interface BlockExplodeEvent {
  block: string;
  location: Location;
  cancelled?: boolean;
}
export interface ServerCommandEvent {
  command: string;
  sender: string;
  cancelled?: boolean;
}

/**
 * Yeow 生态权限检查（仅 `player.hasPermission` 任务与 Yeow 插件注册命令的执行检查触发；
 * 其他 Java 插件的 hasPermission / 命令不会触发）。
 *
 * handler 返回 `{ allowed: <boolean> }` 决定结果；**不返回视为未处理**（回退 Bukkit hasPermission）。
 * 多个 handler 都返回且结果冲突时，以**最后一个返回的为准**（不保证执行顺序）。
 *
 * ⚠ 普通插件不建议监听（每次权限检查都触发，影响性能）——专为 Yeow 生态权限管理插件设计。
 * ⚠ handler 中调用 `hasPermission` 会再次触发本检查——**可能导致无限循环**，请避免。
 */
export interface PermissionCheckEvent {
  /** 检查对象：玩家 UUID 或 "CONSOLE"。 */
  target: string;
  /** 权限节点（如 "myplugin.home"）。 */
  node: string;
  /** 权限对象（含节点默认值，`registerPermission` 或命令声明的权限）。 */
  permission: { node: string; default?: string };
  /** handler 返回 `{ allowed }` 决定结果。 */
  allowed?: boolean;
}
export interface InventoryClickEvent {
  player: Player;
  slot: number;
  hotbarKey: number;
  action: string;
  inventoryType: string;
  isLeftClick: boolean;
  isRightClick: boolean;
  isShiftClick: boolean;
  clickedItem: ItemStack | null;
  cursorItem: ItemStack | null;
  /** 若点击发生在 Yeow 自定义 Inventory（Inventory.create 创建）：该 Inventory 的句柄 id（inventory.toString()）；否则缺省。 */
  inventoryId?: string;
  cancelled?: boolean;
}
export interface PlayerResourcePackStatusEvent {
  player: Player;
  status: string;
  hash: string;
  cancelled?: boolean;
}

type EventMap = {
  playerJoin: PlayerJoinEvent;
  playerQuit: PlayerQuitEvent;
  playerChat: PlayerChatEvent;
  playerMove: PlayerMoveEvent;
  playerInteract: PlayerInteractEvent;
  playerCommand: PlayerCommandEvent;
  playerDeath: PlayerDeathEvent;
  playerRespawn: PlayerRespawnEvent;
  playerDropItem: PlayerDropItemEvent;
  playerPickupItem: PlayerPickupItemEvent;
  playerBucketFill: PlayerBucketFillEvent;
  playerBucketEmpty: PlayerBucketEmptyEvent;
  playerExpChange: PlayerExpChangeEvent;
  playerLevelChange: PlayerLevelChangeEvent;
  playerGameModeChange: PlayerGameModeChangeEvent;
  playerAdvancementDone: PlayerAdvancementDoneEvent;
  playerToggleSneak: PlayerToggleSneakEvent;
  playerToggleFlight: PlayerToggleFlightEvent;
  foodLevelChange: FoodLevelChangeEvent;
  entityDamage: EntityDamageEvent;
  entityDeath: EntityDeathEvent;
  entitySpawn: EntitySpawnEvent;
  entityExplode: EntityExplodeEvent;
  entityRegainHealth: EntityRegainHealthEvent;
  entityTarget: EntityTargetEvent;
  projectileLaunch: ProjectileLaunchEvent;
  projectileHit: ProjectileHitEvent;
  blockBreak: BlockBreakEvent;
  blockPlace: BlockPlaceEvent;
  blockFade: BlockFadeEvent;
  blockGrow: BlockGrowEvent;
  blockSpread: BlockSpreadEvent;
  blockExplode: BlockExplodeEvent;
  inventoryOpen: InventoryOpenEvent;
  inventoryClose: InventoryCloseEvent;
  serverPing: ServerPingEvent;
  serverCommand: ServerCommandEvent;
  permissionCheck: PermissionCheckEvent;
  playerTeleport: PlayerTeleportEvent;
  playerItemConsume: PlayerItemConsumeEvent;
  inventoryClick: InventoryClickEvent;
  playerResourcePackStatus: PlayerResourcePackStatusEvent;
};

type RawEvent = Record<string, unknown> & { _cancellable?: boolean };

function loc(raw: Record<string, unknown> | null): Location | null {
  if (!raw) return null;
  return new Location(
    raw.x as number, raw.y as number, raw.z as number,
    (raw.yaw as number) ?? 0, (raw.pitch as number) ?? 0, raw.world as string,
  );
}

function adaptEvent<K extends keyof EventMap>(type: K, data: RawEvent): { event: EventMap[K]; mods: Record<string, unknown> } {
  // 初始值：原始数据（跳过 _ 前缀内部字段），player/from/to/respawnLocation 适配为对象
  const initial: Record<string, unknown> = {};
  for (const key of Object.keys(data)) {
    if (key.startsWith('_')) continue;
    initial[key] = data[key];
  }
  const hasPlayer = [
    'playerJoin', 'playerQuit', 'playerChat', 'playerMove',
    'playerInteract', 'playerCommand', 'playerDeath', 'playerRespawn',
    'playerTeleport', 'playerItemConsume',
    'playerDropItem', 'playerPickupItem', 'playerBucketFill',
    'playerBucketEmpty', 'playerExpChange', 'playerLevelChange',
    'playerGameModeChange', 'foodLevelChange', 'blockBreak',
    'blockPlace', 'inventoryOpen', 'inventoryClose',
    'playerAdvancementDone', 'playerToggleSneak', 'playerToggleFlight',
    'inventoryClick', 'playerResourcePackStatus',
  ] as K[];
  // 直接以 uuid 构造 Player（零往返）；name 在首次访问时惰性获取（见 Player.name）
  if (hasPlayer.includes(type) && data.player) initial.player = new Player(data.player as string);
  if (data.from) initial.from = loc(data.from as Record<string, unknown>);
  if (data.to) initial.to = loc(data.to as Record<string, unknown>);
  if (data.respawnLocation) initial.respawnLocation = loc(data.respawnLocation as Record<string, unknown>);

  // 平铺坐标事件（entitySpawn / blockBreak 等）→ 统一 `location: Location`（与 from/to 同形；
  // 原始 x/y/z/world 字段保留在事件对象上，兼容旧代码读取）。
  if (typeof data.x === 'number' && typeof data.y === 'number' && typeof data.z === 'number') {
    initial.location = loc(data);
  }
  // 嵌套方块坐标 → `{ location, type }`
  if (type === 'playerInteract' && data.block && typeof data.block === 'object') {
    const b = data.block as Record<string, unknown>;
    initial.block = { type: b.type as string, location: loc(b) };
  }
  if (type === 'projectileHit' && data.hitBlock && typeof data.hitBlock === 'object') {
    const b = data.hitBlock as Record<string, unknown>;
    initial.hitBlock = { type: b.type as string, location: loc(b) };
  }
  // 物品事件字段统一为 ItemStack（{type, amount} 数据快照；原始 itemType/amount 保留）
  if ((type === 'playerDropItem' || type === 'playerPickupItem') && data.itemType) {
    initial.item = { type: data.itemType as string, amount: (data.amount as number) ?? 1 };
  }
  if (type === 'playerItemConsume' && data.itemType) {
    initial.item = { type: data.itemType as string };
  }

  // 修改收集：所有字段经 getter/setter——handler 直接赋值（e.xxx = ...）即记录为回写 mods。
  // cancelled 单独处理（仅可取消事件暴露；读取语义保持原状：未设置时返回 false）。
  const mods: Record<string, unknown> = {};
  const wrap: Record<string, unknown> = {};
  for (const key of Object.keys(initial)) {
    if (key === 'cancelled') continue;
    Object.defineProperty(wrap, key, {
      get: () => (key in mods ? mods[key] : initial[key]),
      set: (v: unknown) => { mods[key] = v; },
      enumerable: true,
      configurable: true,
    });
  }
  if (data._cancellable) {
    Object.defineProperty(wrap, 'cancelled', {
      get: () => (mods.cancelled as boolean) || false,
      set: (v: boolean) => { mods.cancelled = v; },
      enumerable: true,
      configurable: true,
    });
  }
  return { event: wrap as unknown as EventMap[K], mods };
}

// ── Event Subscription ─────────────────────────────────────────────

type EventHandler<K extends keyof EventMap> = (e: EventMap[K]) => unknown;
type ManualHandler<K extends keyof EventMap> = (
  e: EventMap[K],
  complete: (result?: Record<string, unknown>) => void,
) => void;

export function eventOn<K extends keyof EventMap>(
  eventType: K,
  handler: EventHandler<K>,
): () => void;
export function eventOn<K extends keyof EventMap>(
  eventType: K,
  options: { manualRelease: true },
  handler: ManualHandler<K>,
): () => void;
export function eventOn<K extends keyof EventMap>(
  eventType: K,
  handlerOrOptions: EventHandler<K> | { manualRelease?: boolean },
  handlerOrUndef?: ManualHandler<K>,
): () => void {
  const manualRelease = typeof handlerOrOptions === 'object' && handlerOrOptions?.manualRelease === true;
  const handler = manualRelease ? handlerOrUndef! : (handlerOrOptions as EventHandler<K>);
  const pluginName = __plugin?.name || 'unknown';

  const cbId = _registerCallback((data: RawEvent) => {
    const { event: wrapped, mods: mutatedMods } = adaptEvent(eventType, data);
    const eventId = data?._eventId;

    // 释放事件（event.complete）。处理器抛错时也必须释放——否则事件桥等待 complete
    // 直到 5s 超时（EventBridge.timeoutMs），每次抛错都会卡住该事件并产生 event.timeout 噪音。
    const release = (mods?: Record<string, unknown>) => {
      try {
        $send('task', {
          type: 'event.complete',
          params: { eventId, mods },
          cb: '',
        });
      } catch { /* 桥故障：保留原始错误（若有），事件由 Java 侧超时兜底 */ }
    };

    if (manualRelease) {
      const complete = (result?: Record<string, unknown>) => release(result);
      try {
        (handler as ManualHandler<typeof eventType>)(wrapped, complete);
      } catch (e) {
        release(); // 处理器同步抛错：立即释放（complete 幂等，重复调用无副作用）
        throw e;
      }
      return;
    }

    let result: unknown;
    try {
      result = (handler as EventHandler<typeof eventType>)(wrapped);
    } finally {
      // 回写合并：返回值（mods）优先合并，事件参数直接赋值（e.xxx = ...）覆盖之——
      // 返回 Promise 时视为无修改（Promise 不展开），事件立即释放。
      // finally 保证处理器抛错时也释放事件。
      const mods: Record<string, unknown> = {
        ...(result && typeof result === 'object' ? result : {}),
        ...mutatedMods,
      };
      release(mods);
    }
  }, { persistent: true });

  const gh = globalThis as any;
  if (!gh.__yeowEventHandlers) gh.__yeowEventHandlers = {};
  if (!gh.__yeowEventHandlers[eventType]) gh.__yeowEventHandlers[eventType] = [];
  gh.__yeowEventHandlers[eventType].push({ cbId, handler, manualRelease });

  call('event.subscribe', { pluginName, eventType, callbackId: String(cbId) });
  return () => eventOff(eventType, handler);
}

export function eventOff(eventType: string, handler: Function): void {
  const gh = globalThis as any;
  const entries = gh.__yeowEventHandlers?.[eventType];
  if (!entries) return;
  const idx = entries.findIndex((e: any) => e.handler === handler);
  if (idx !== -1) {
    const removed = entries.splice(idx, 1)[0];
    if (removed.cbId) _unregisterCallback(removed.cbId);
  }
  if (entries.length === 0) {
    delete gh.__yeowEventHandlers[eventType];
    call('event.unsubscribe', {
      pluginName: __plugin?.name || 'unknown',
      eventType,
    });
  }
}
