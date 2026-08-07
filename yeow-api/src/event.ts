import { call } from './task.js';
import { Player } from './player.js';
import { Location } from './location.js';

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
  block: { x: number; y: number; z: number; type: string } | null;
  cancelled?: boolean;
}
export interface PlayerCommandEvent {
  player: Player;
  message: string;
  cancelled?: boolean;
}
export interface PlayerDeathEvent {
  player: Player;
  deathMessage: string;
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
  itemType: string;
  amount: number;
  cancelled?: boolean;
}
export interface PlayerPickupItemEvent {
  player: Player;
  itemType: string;
  amount: number;
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
  x: number;
  y: number;
  z: number;
  world: string;
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
  x: number;
  y: number;
  z: number;
  cancelled?: boolean;
}
export interface BlockPlaceEvent {
  player: Player;
  block: string;
  blockAgainst: string;
  x: number;
  y: number;
  z: number;
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
  cancelled?: boolean;
}
export interface ServerPingEvent {
  address: string;
  numPlayers: number;
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
  itemType: string;
  cancelled?: boolean;
}
export interface PlayerAdvancementDoneEvent {
  player: Player;
  advancement: string;
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
  x: number;
  y: number;
  z: number;
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
  hitBlock: { x: number; y: number; z: number; type: string } | null;
  cancelled?: boolean;
}
export interface BlockFadeEvent {
  block: string;
  x: number;
  y: number;
  z: number;
  cancelled?: boolean;
}
export interface BlockGrowEvent {
  block: string;
  x: number;
  y: number;
  z: number;
  cancelled?: boolean;
}
export interface BlockSpreadEvent {
  block: string;
  x: number;
  y: number;
  z: number;
  cancelled?: boolean;
}
export interface BlockExplodeEvent {
  block: string;
  x: number;
  y: number;
  z: number;
  cancelled?: boolean;
}
export interface ServerCommandEvent {
  command: string;
  sender: string;
  cancelled?: boolean;
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
  clickedItem: ItemData | null;
  cursorItem: ItemData | null;
  cancelled?: boolean;
}
interface ItemData {
  type: string;
  amount: number;
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

function adaptEvent<K extends keyof EventMap>(type: K, data: RawEvent): EventMap[K] {
  const wrap: Record<string, unknown> = {};
  for (const key of Object.keys(data)) {
    if (key.startsWith('_')) continue;
    wrap[key] = data[key];
  }
  const hasPlayer = [
    'playerJoin', 'playerQuit', 'playerChat', 'playerMove',
    'playerInteract', 'playerCommand', 'playerDeath', 'playerRespawn',
    'playerTeleport', 'playerItemConsume',
    'playerDropItem', 'playerPickupItem', 'playerBucketFill',
    'playerBucketEmpty', 'playerExpChange', 'playerLevelChange',
    'playerGameModeChange', 'foodLevelChange', 'blockBreak',
    'blockPlace', 'inventoryOpen', 'inventoryClose',
  ] as K[];
  if (hasPlayer.includes(type) && data.player) {
    wrap.player = Player.getSync(data.player as string);
  }
  if (data.from) wrap.from = loc(data.from as Record<string, unknown>);
  if (data.to) wrap.to = loc(data.to as Record<string, unknown>);
  if (data.respawnLocation) wrap.respawnLocation = loc(data.respawnLocation as Record<string, unknown>);
  return wrap as unknown as EventMap[K];
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
    const wrapped = adaptEvent(eventType, data);
    const cancellable = data?._cancellable;
    const localMods: { cancelled?: boolean } = {};

    if (cancellable) {
      Object.defineProperty(wrapped, 'cancelled', {
        get: () => localMods.cancelled || false,
        set: (v: boolean) => { localMods.cancelled = v; },
        enumerable: true,
      });
    }

    if (manualRelease) {
      const complete = (result?: Record<string, unknown>) => {
        $send('task', {
          type: 'event.complete',
          params: { callbackId: cbId, mods: result },
          cb: '',
        });
      };
      (handler as ManualHandler<typeof eventType>)(wrapped, complete);
      return;
    }

    const result = (handler as EventHandler<typeof eventType>)(wrapped);
    const mods: Record<string, unknown> = {
      ...(result && typeof result === 'object' ? result : {}),
    };
    if (localMods.cancelled) mods.cancelled = true;
    $send('task', { type: 'event.complete', params: { callbackId: cbId, mods }, cb: '' });
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
