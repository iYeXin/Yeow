import { call, post } from './task.js';
import type { TaskOptions } from './task.js';
import { LivingEntity } from './entity.js';
import { Location, LocationData } from './location.js';
import type { ItemStack } from './item.js';
import { Block } from './block.js';
import type { Message } from './message.js';
import type { Permission } from './permission.js';
import type { AdvancementProgress } from './advancement.js';
import { Inventory } from './inventory.js';
import {
  get as pdcGet, set as pdcSet, has as pdcHas, remove as pdcRemove,
  keys as pdcKeys, getAll as pdcGetAll,
} from './pdc.js';

interface PlayerData {
  uuid: string;
  name: string;
}

export class Player extends LivingEntity {
  static get(identifier: string, options?: TaskOptions): Promise<Player | null> {
    return post<PlayerData>('player.get', { identifier }, options).then((d) => (d ? new Player(d.uuid, d.name) : null));
  }
  static getSync(identifier: string, options?: TaskOptions): Player | null {
    const d = call<PlayerData>('player.get', { identifier }, options);
    return d ? new Player(d.uuid, d.name) : null;
  }
  static getAll(options?: TaskOptions): Promise<Player[]> {
    return post<PlayerData[]>('player.getAll', {}, options).then((list) => list.map((r) => new Player(r.uuid, r.name)));
  }
  static getAllSync(options?: TaskOptions): Player[] {
    return call<PlayerData[]>('player.getAll', {}, options).map((r) => new Player(r.uuid, r.name));
  }

  // uuid 由 Entity 基类持有（readonly）；本类扩展玩家特有字段。
  constructor(uuid: string, private _name?: string) {
    super(uuid);
  }

  /** 玩家名。未提供时首次访问惰性同步获取并缓存（仅一次往返）。 */
  get name(): string {
    if (this._name === undefined) {
      let n = '';
      try { n = call<PlayerData>('player.get', { identifier: this.uuid })?.name ?? ''; } catch { /* 离线/异常 → '' */ }
      this._name = n;
    }
    return this._name;
  }

  /** 玩家物品栏（统一 Inventory 容器抽象）。 */
  get inventory(): Inventory {
    return Inventory.ofPlayer(this.uuid);
  }

  get ping(): number { return call<number>('player.getPing', { uuid: this.uuid }); }
  getPing(options?: TaskOptions): Promise<number> { return post<number>('player.getPing', { uuid: this.uuid }, options); }

  get gamemode(): string { return call<string>('player.getGamemode', { uuid: this.uuid }); }
  set gamemode(v: string) { call('player.setGamemode', { uuid: this.uuid, value: v }); }
  getGamemode(options?: TaskOptions): Promise<string> { return post<string>('player.getGamemode', { uuid: this.uuid }, options); }
  setGamemode(v: string, options?: TaskOptions): Promise<void> { return post('player.setGamemode', { uuid: this.uuid, value: v }, options); }

  get health(): number { return call<number>('player.getHealth', { uuid: this.uuid }); }
  set health(v: number) { call('player.setHealth', { uuid: this.uuid, value: v }); }
  getHealth(options?: TaskOptions): Promise<number> { return post<number>('player.getHealth', { uuid: this.uuid }, options); }
  setHealth(v: number, options?: TaskOptions): Promise<void> { return post('player.setHealth', { uuid: this.uuid, value: v }, options); }

  get food(): number { return call<number>('player.getFood', { uuid: this.uuid }); }
  set food(v: number) { call('player.setFood', { uuid: this.uuid, value: v }); }
  getFood(options?: TaskOptions): Promise<number> { return post<number>('player.getFood', { uuid: this.uuid }, options); }
  setFood(v: number, options?: TaskOptions): Promise<void> { return post('player.setFood', { uuid: this.uuid, value: v }, options); }

  get exp(): number { return call<number>('player.getExp', { uuid: this.uuid }); }
  set exp(v: number) { call('player.setExp', { uuid: this.uuid, value: v }); }
  getExp(options?: TaskOptions): Promise<number> { return post<number>('player.getExp', { uuid: this.uuid }, options); }
  setExp(v: number, options?: TaskOptions): Promise<void> { return post('player.setExp', { uuid: this.uuid, value: v }, options); }

  get level(): number { return call<number>('player.getLevel', { uuid: this.uuid }); }
  set level(v: number) { call('player.setLevel', { uuid: this.uuid, value: v }); }
  getLevel(options?: TaskOptions): Promise<number> { return post<number>('player.getLevel', { uuid: this.uuid }, options); }
  setLevel(v: number, options?: TaskOptions): Promise<void> { return post('player.setLevel', { uuid: this.uuid, value: v }, options); }

  get isOp(): boolean { return call<boolean>('player.isOp', { uuid: this.uuid }); }
  isOpAsync(options?: TaskOptions): Promise<boolean> { return post<boolean>('player.isOp', { uuid: this.uuid }, options); }

  get online(): boolean { return call<boolean>('player.isOnline', { uuid: this.uuid }); }
  isOnlineAsync(options?: TaskOptions): Promise<boolean> { return post<boolean>('player.isOnline', { uuid: this.uuid }, options); }

  get isFlying(): boolean { return call<boolean>('player.isFlying', { uuid: this.uuid }); }
  set isFlying(v: boolean) { call('player.setFlying', { uuid: this.uuid, value: v }); }
  isFlyingAsync(options?: TaskOptions): Promise<boolean> { return post<boolean>('player.isFlying', { uuid: this.uuid }, options); }
  setFlying(v: boolean, options?: TaskOptions): Promise<void> { return post('player.setFlying', { uuid: this.uuid, value: v }, options); }

  get isSneaking(): boolean { return call<boolean>('player.isSneaking', { uuid: this.uuid }); }
  isSneakingAsync(options?: TaskOptions): Promise<boolean> { return post<boolean>('player.isSneaking', { uuid: this.uuid }, options); }
  get isSprinting(): boolean { return call<boolean>('player.isSprinting', { uuid: this.uuid }); }
  isSprintingAsync(options?: TaskOptions): Promise<boolean> { return post<boolean>('player.isSprinting', { uuid: this.uuid }, options); }

  get bedLocation(): Location | null {
    const r = call<LocationData>('player.getBedLocation', { uuid: this.uuid });
    return r ? Location.from(r) : null;
  }
  getBedLocation(options?: TaskOptions): Promise<Location | null> {
    return post<LocationData>('player.getBedLocation', { uuid: this.uuid }, options).then((r) => (r ? Location.from(r) : null));
  }

  get allowFlight(): boolean { return call<boolean>('player.getAllowFlight', { uuid: this.uuid }); }
  set allowFlight(v: boolean) { call('player.setAllowFlight', { uuid: this.uuid, value: v }); }
  getAllowFlight(options?: TaskOptions): Promise<boolean> { return post<boolean>('player.getAllowFlight', { uuid: this.uuid }, options); }
  setAllowFlight(v: boolean, options?: TaskOptions): Promise<void> { return post('player.setAllowFlight', { uuid: this.uuid, value: v }, options); }

  get walkSpeed(): number { return call<number>('player.getWalkSpeed', { uuid: this.uuid }); }
  set walkSpeed(v: number) { call('player.setWalkSpeed', { uuid: this.uuid, value: v }); }
  getWalkSpeed(options?: TaskOptions): Promise<number> { return post<number>('player.getWalkSpeed', { uuid: this.uuid }, options); }
  setWalkSpeed(v: number, options?: TaskOptions): Promise<void> { return post('player.setWalkSpeed', { uuid: this.uuid, value: v }, options); }

  get flySpeed(): number { return call<number>('player.getFlySpeed', { uuid: this.uuid }); }
  set flySpeed(v: number) { call('player.setFlySpeed', { uuid: this.uuid, value: v }); }
  getFlySpeed(options?: TaskOptions): Promise<number> { return post<number>('player.getFlySpeed', { uuid: this.uuid }, options); }
  setFlySpeed(v: number, options?: TaskOptions): Promise<void> { return post('player.setFlySpeed', { uuid: this.uuid, value: v }, options); }

  get world(): string { return call<string>('player.getWorld', { uuid: this.uuid }); }
  getWorld(options?: TaskOptions): Promise<string> { return post<string>('player.getWorld', { uuid: this.uuid }, options); }

  get location(): Location | null {
    const r = call<LocationData>('player.getLocation', { uuid: this.uuid });
    return r ? Location.from(r) : null;
  }
  getLocation(options?: TaskOptions): Promise<Location | null> {
    return post<LocationData>('player.getLocation', { uuid: this.uuid }, options).then((r) => (r ? Location.from(r) : null));
  }

  get displayName(): string { return call<string>('player.getDisplayName', { uuid: this.uuid }); }
  set displayName(v: string | null) { call('player.setDisplayName', { uuid: this.uuid, value: v }); }
  getDisplayName(options?: TaskOptions): Promise<string> { return post<string>('player.getDisplayName', { uuid: this.uuid }, options); }
  setDisplayName(v: string | null, options?: TaskOptions): Promise<void> { return post('player.setDisplayName', { uuid: this.uuid, value: v }, options); }

  get saturation(): number { return call<number>('player.getSaturation', { uuid: this.uuid }); }
  getSaturation(options?: TaskOptions): Promise<number> { return post<number>('player.getSaturation', { uuid: this.uuid }, options); }

  get totalExperience(): number { return call<number>('player.getTotalExperience', { uuid: this.uuid }); }
  getTotalExperience(options?: TaskOptions): Promise<number> { return post<number>('player.getTotalExperience', { uuid: this.uuid }, options); }

  sendMessage(msg: string | Message, options?: TaskOptions): Promise<void> { return post('player.sendMessage', { uuid: this.uuid, message: msg }, options); }
  sendMessageSync(msg: string | Message, options?: TaskOptions): void { call('player.sendMessage', { uuid: this.uuid, message: msg }, options); }
  kick(reason?: string, options?: TaskOptions): Promise<void> { return post('player.kick', { uuid: this.uuid, reason }, options); }
  kickSync(reason?: string, options?: TaskOptions): void { call('player.kick', { uuid: this.uuid, reason }, options); }
  sendTitle(title?: string, subtitle?: string, fadeIn?: number, stay?: number, fadeOut?: number, options?: TaskOptions): Promise<void> {
    return post('player.sendTitle', { uuid: this.uuid, title, subtitle, fadeIn, stay, fadeOut }, options);
  }
  sendTitleSync(title?: string, subtitle?: string, fadeIn?: number, stay?: number, fadeOut?: number, options?: TaskOptions): void {
    call('player.sendTitle', { uuid: this.uuid, title, subtitle, fadeIn, stay, fadeOut }, options);
  }
  playSound(sound: string, volume?: number, pitch?: number, options?: TaskOptions): Promise<void> {
    return post('player.playSound', { uuid: this.uuid, sound, volume, pitch }, options);
  }
  playSoundSync(sound: string, volume?: number, pitch?: number, options?: TaskOptions): void {
    call('player.playSound', { uuid: this.uuid, sound, volume, pitch }, options);
  }
  /** 停止播放指定音效。 */
  stopSound(sound: string, options?: TaskOptions): Promise<void> {
    return post('player.stopSound', { uuid: this.uuid, sound }, options);
  }
  stopSoundSync(sound: string, options?: TaskOptions): void {
    call('player.stopSound', { uuid: this.uuid, sound }, options);
  }
  /** 停止该玩家所有音效。 */
  stopAllSounds(options?: TaskOptions): Promise<void> {
    return post('player.stopAllSounds', { uuid: this.uuid }, options);
  }
  stopAllSoundsSync(options?: TaskOptions): void {
    call('player.stopAllSounds', { uuid: this.uuid }, options);
  }
  giveExp(amount: number, options?: TaskOptions): Promise<void> { return post('player.giveExp', { uuid: this.uuid, amount }, options); }
  giveExpSync(amount: number, options?: TaskOptions): void { call('player.giveExp', { uuid: this.uuid, amount }, options); }
  /** 检查权限（经 Yeow 权限检查：`permissionCheck` 事件优先，无处理时回退 Bukkit）。node 可为权限节点对象。 */
  hasPermission(node: string | Permission, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('player.hasPermission', { uuid: this.uuid, permission: typeof node === 'string' ? { node } : { node: node.node } }, options);
  }
  hasPermissionSync(node: string | Permission, options?: TaskOptions): boolean {
    return call<boolean>('player.hasPermission', { uuid: this.uuid, permission: typeof node === 'string' ? { node } : { node: node.node } }, options);
  }
  /** 以玩家身份执行命令（**不含 `/` 前缀**，如 `say hi`；前缀会自动剥离；与服务器 `dispatchCommand`（控制台）相对）。 */
  performCommand(cmd: string, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('player.performCommand', { uuid: this.uuid, command: cmd.replace(/^\//, '') }, options);
  }
  performCommandSync(cmd: string, options?: TaskOptions): boolean {
    return call<boolean>('player.performCommand', { uuid: this.uuid, command: cmd.replace(/^\//, '') }, options);
  }
  /** 授予进度全部条件。 */
  grantAdvancement(key: string, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('advancement.grant', { uuid: this.uuid, key }, options);
  }
  grantAdvancementSync(key: string, options?: TaskOptions): boolean {
    return call<boolean>('advancement.grant', { uuid: this.uuid, key }, options);
  }
  /** 撤销进度全部条件。 */
  revokeAdvancement(key: string, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('advancement.revoke', { uuid: this.uuid, key }, options);
  }
  revokeAdvancementSync(key: string, options?: TaskOptions): boolean {
    return call<boolean>('advancement.revoke', { uuid: this.uuid, key }, options);
  }
  /** 查询进度完成情况。 */
  getAdvancementProgress(key: string, options?: TaskOptions): Promise<AdvancementProgress | null> {
    return post<AdvancementProgress | null>('advancement.getProgress', { uuid: this.uuid, key }, options);
  }
  getAdvancementProgressSync(key: string, options?: TaskOptions): AdvancementProgress | null {
    return call<AdvancementProgress | null>('advancement.getProgress', { uuid: this.uuid, key }, options);
  }
  /** 授予进度单个条件。 */
  awardCriteria(key: string, criteria: string, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('advancement.awardCriteria', { uuid: this.uuid, key, criteria }, options);
  }
  awardCriteriaSync(key: string, criteria: string, options?: TaskOptions): boolean {
    return call<boolean>('advancement.awardCriteria', { uuid: this.uuid, key, criteria }, options);
  }
  /** 撤销进度单个条件。 */
  revokeCriteria(key: string, criteria: string, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('advancement.revokeCriteria', { uuid: this.uuid, key, criteria }, options);
  }
  revokeCriteriaSync(key: string, criteria: string, options?: TaskOptions): boolean {
    return call<boolean>('advancement.revokeCriteria', { uuid: this.uuid, key, criteria }, options);
  }
  teleport(loc: Location, options?: TaskOptions): Promise<void> { return post('player.teleport', { uuid: this.uuid, ...loc.toObject() }, options); }
  teleportSync(loc: Location, options?: TaskOptions): void { call('player.teleport', { uuid: this.uuid, ...loc.toObject() }, options); }
  /** 向玩家发送假方块变化（仅客户端视觉，不改变真实世界）。block 为 Block 对象或字符串（同 world.setBlock，字符串无状态）。 */
  sendBlockChange(location: Location, block: Block | string, options?: TaskOptions): Promise<void> {
    const p: Record<string, unknown> = { uuid: this.uuid, ...location.toObject() };
    if (typeof block === 'string') {
      p.blockType = block;
    } else {
      p.blockType = block.type;
      if (block.state && Object.keys(block.state).length > 0) p.state = block.state;
    }
    return post('player.sendBlockChange', p, options);
  }
  sendBlockChangeSync(location: Location, block: Block | string, options?: TaskOptions): void {
    const p: Record<string, unknown> = { uuid: this.uuid, ...location.toObject() };
    if (typeof block === 'string') {
      p.blockType = block;
    } else {
      p.blockType = block.type;
      if (block.state && Object.keys(block.state).length > 0) p.state = block.state;
    }
    call('player.sendBlockChange', p, options);
  }
  sendActionBar(message: string | Message, options?: TaskOptions): Promise<void> { return post('player.sendActionBar', { uuid: this.uuid, message }, options); }
  sendActionBarSync(message: string | Message, options?: TaskOptions): void { call('player.sendActionBar', { uuid: this.uuid, message }, options); }
  sendResourcePack(url: string, hash?: string, prompt?: string | Message, force?: boolean, options?: TaskOptions): Promise<void> {
    return post('player.sendResourcePack', { uuid: this.uuid, url, hash, prompt, force }, options);
  }
  sendResourcePackSync(url: string, hash?: string, prompt?: string | Message, force?: boolean, options?: TaskOptions): void {
    call('player.sendResourcePack', { uuid: this.uuid, url, hash, prompt, force }, options);
  }
  getItemInMainHand(options?: TaskOptions): Promise<ItemStack | null> { return post<ItemStack | null>('player.getItemInMainHand', { uuid: this.uuid }, options); }
  getItemInMainHandSync(options?: TaskOptions): ItemStack | null { return call<ItemStack | null>('player.getItemInMainHand', { uuid: this.uuid }, options); }
  getItemInOffHand(options?: TaskOptions): Promise<ItemStack | null> { return post<ItemStack | null>('player.getItemInOffHand', { uuid: this.uuid }, options); }
  getItemInOffHandSync(options?: TaskOptions): ItemStack | null { return call<ItemStack | null>('player.getItemInOffHand', { uuid: this.uuid }, options); }

  /** 设置主手物品（完整 ItemStack 含 meta；传 null 清空）。 */
  setItemInMainHand(item: ItemStack | null, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('player.setItemInMainHand', { uuid: this.uuid, item }, options);
  }
  setItemInMainHandSync(item: ItemStack | null, options?: TaskOptions): boolean {
    return call<boolean>('player.setItemInMainHand', { uuid: this.uuid, item }, options);
  }
  /** 设置副手物品（完整 ItemStack 含 meta；传 null 清空）。 */
  setItemInOffHand(item: ItemStack | null, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('player.setItemInOffHand', { uuid: this.uuid, item }, options);
  }
  setItemInOffHandSync(item: ItemStack | null, options?: TaskOptions): boolean {
    return call<boolean>('player.setItemInOffHand', { uuid: this.uuid, item }, options);
  }

  /** 设置 Tab 列表 header/footer（MiniMessage；传 null 清空对应栏）。 */
  sendTabHeader(header: string | null, footer: string | null, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('player.sendTabHeader', { uuid: this.uuid, header, footer }, options);
  }
  sendTabHeaderSync(header: string | null, footer: string | null, options?: TaskOptions): boolean {
    return call<boolean>('player.sendTabHeader', { uuid: this.uuid, header, footer }, options);
  }

  /** 设置 Tab 列表显示名（传 null 恢复默认）。 */
  setPlayerListName(name: string | null, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('player.setPlayerListName', { uuid: this.uuid, name }, options);
  }
  setPlayerListNameSync(name: string | null, options?: TaskOptions): boolean {
    return call<boolean>('player.setPlayerListName', { uuid: this.uuid, name }, options);
  }

  // ── PDC（玩家持久数据） ──

  /** 读取并 JSON 反序列化（无值返回 null；旧数据非 JSON 时原样返回字符串）。 */
  getPdc<T = unknown>(key: string, options?: TaskOptions): Promise<T | null> {
    return pdcGet(this.uuid, key, options);
  }

  /** 任意可 JSON 序列化的值自动序列化后写入。 */
  setPdc(key: string, value: unknown, options?: TaskOptions): Promise<boolean> {
    return pdcSet(this.uuid, key, value, options);
  }

  /** 键是否存在。 */
  hasPdc(key: string, options?: TaskOptions): Promise<boolean> {
    return pdcHas(this.uuid, key, options);
  }

  /** 移除键。 */
  removePdc(key: string, options?: TaskOptions): Promise<boolean> {
    return pdcRemove(this.uuid, key, options);
  }

  /** 全部键（完整 key 格式，含命名空间）。 */
  keysPdc(options?: TaskOptions): Promise<string[]> {
    return pdcKeys(this.uuid, options);
  }

  /** 全量读取本插件命名空间的键值（每个值 JSON 反序列化）。 */
  getAllPdc(options?: TaskOptions): Promise<Record<string, unknown>> {
    return pdcGetAll(this.uuid, options);
  }
}
