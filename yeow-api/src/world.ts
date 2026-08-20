import { call, post } from './task.js';
import type { TaskOptions } from './task.js';
import { Location, LocationData } from './location.js';
import { Block } from './block.js';
import type { BlockState } from './block.js';
import type { ItemStack } from './item.js';
import { Chunk, ChunkData } from './chunk.js';

interface WorldData {
  name: string;
}

/** 世界边界快照。 */
export interface WorldBorderInfo {
  centerX: number;
  centerZ: number;
  size: number;
  damageAmount: number;
  damageBuffer: number;
  warningDistance: number;
  warningTime: number;
}

export class World {
  static get(name: string, options?: TaskOptions): Promise<World | null> {
    return post<WorldData>('world.get', { name }, options).then((d) => (d ? new World(d.name) : null));
  }
  static getSync(name: string, options?: TaskOptions): World | null {
    const d = call<WorldData>('world.get', { name }, options);
    return d ? new World(d.name) : null;
  }
  static getAll(options?: TaskOptions): Promise<World[]> {
    return post<WorldData[]>('world.getAll', {}, options).then((list) => list.map((r) => new World(r.name)));
  }
  static getAllSync(options?: TaskOptions): World[] {
    return call<WorldData[]>('world.getAll', {}, options).map((r) => new World(r.name));
  }

  constructor(public readonly name: string) {}

  get time(): number { return call<number>('world.getTime', { world: this.name }); }
  set time(v: number) { call('world.setTime', { world: this.name, value: v }); }
  get storm(): boolean { return call<boolean>('world.getStorm', { world: this.name }); }
  set storm(v: boolean) { call('world.setStorm', { world: this.name, value: v }); }
  get thundering(): boolean { return call<boolean>('world.getThundering', { world: this.name }); }
  set thundering(v: boolean) { call('world.setThundering', { world: this.name, value: v }); }
  get difficulty(): string { return call<string>('world.getDifficulty', { world: this.name }); }
  set difficulty(v: string) { call('world.setDifficulty', { world: this.name, value: v }); }
  get spawnLocation(): Location | null {
    const r = call<LocationData>('world.getSpawnLocation', { world: this.name });
    return r ? new Location(r.x, r.y, r.z, r.yaw, r.pitch) : null;
  }
  set spawnLocation(v: Location) { call('world.setSpawnLocation', { world: this.name, ...v.toObject() }); }

  getHighestBlockY(x: number, z: number, options?: TaskOptions): Promise<number> {
    return post<number>('world.getHighestBlockY', { world: this.name, x, z }, options);
  }
  getHighestBlockYSync(x: number, z: number, options?: TaskOptions): number {
    return call<number>('world.getHighestBlockY', { world: this.name, x, z }, options);
  }

  // ── 世界信息（2026-08-13） ──

  /** 世界种子。 */
  get seed(): number { return call<number>('world.getSeed', { world: this.name }); }
  getSeed(options?: TaskOptions): Promise<number> { return post<number>('world.getSeed', { world: this.name }, options); }

  /** 环境：NORMAL / NETHER / THE_END。 */
  get environment(): string { return call<string>('world.getEnvironment', { world: this.name }); }
  getEnvironment(options?: TaskOptions): Promise<string> { return post<string>('world.getEnvironment', { world: this.name }, options); }

  /** 世界类型（可能返回 null——平台不支持时）。 */
  get worldType(): string | null { return call<string | null>('world.getWorldType', { world: this.name }); }
  getWorldType(options?: TaskOptions): Promise<string | null> { return post<string | null>('world.getWorldType', { world: this.name }, options); }

  /** 全部游戏规则名。 */
  get gameRules(): string[] { return call<string[]>('world.getGameRules', { world: this.name }); }
  getGameRules(options?: TaskOptions): Promise<string[]> { return post<string[]>('world.getGameRules', { world: this.name }, options); }

  // ── WorldBorder（2026-08-13） ──

  /** 世界边界快照。 */
  get border(): WorldBorderInfo {
    return call<WorldBorderInfo>('world.getBorder', { world: this.name });
  }
  getBorder(options?: TaskOptions): Promise<WorldBorderInfo> {
    return post<WorldBorderInfo>('world.getBorder', { world: this.name }, options);
  }
  /** 边界中心。 */
  setBorderCenter(x: number, z: number, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('world.setBorderCenter', { world: this.name, x, z }, options);
  }
  setBorderCenterSync(x: number, z: number, options?: TaskOptions): boolean {
    return call<boolean>('world.setBorderCenter', { world: this.name, x, z }, options);
  }
  /** 边界半径（方块）。 */
  setBorderSize(size: number, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('world.setBorderSize', { world: this.name, size }, options);
  }
  setBorderSizeSync(size: number, options?: TaskOptions): boolean {
    return call<boolean>('world.setBorderSize', { world: this.name, size }, options);
  }
  /** 边界伤害（amount 每秒伤害；buffer 无伤缓冲距离）。 */
  setBorderDamage(amount?: number, buffer?: number, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('world.setBorderDamage', { world: this.name, amount, buffer }, options);
  }
  setBorderDamageSync(amount?: number, buffer?: number, options?: TaskOptions): boolean {
    return call<boolean>('world.setBorderDamage', { world: this.name, amount, buffer }, options);
  }
  /** 边界警告（distance 方块距离；time 秒）。 */
  setBorderWarning(distance?: number, time?: number, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('world.setBorderWarning', { world: this.name, distance, time }, options);
  }
  setBorderWarningSync(distance?: number, time?: number, options?: TaskOptions): boolean {
    return call<boolean>('world.setBorderWarning', { world: this.name, distance, time }, options);
  }
  /** 边界平滑移动（from → to，seconds 秒）。 */
  setBorderMoving(from: number, to: number, seconds: number, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('world.setBorderMoving', { world: this.name, from, to, seconds }, options);
  }
  setBorderMovingSync(from: number, to: number, seconds: number, options?: TaskOptions): boolean {
    return call<boolean>('world.setBorderMoving', { world: this.name, from, to, seconds }, options);
  }
  getChunkAt(x: number, z: number, options?: TaskOptions): Promise<Chunk> {
    return post<ChunkData>('world.getChunkAt', { world: this.name, x, z }, options).then((d) => Chunk.from(d));
  }
  getChunkAtSync(x: number, z: number, options?: TaskOptions): Chunk {
    return Chunk.from(call<ChunkData>('world.getChunkAt', { world: this.name, x, z }, options));
  }
  isChunkLoaded(x: number, z: number, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('world.isChunkLoaded', { world: this.name, x, z }, options);
  }
  isChunkLoadedSync(x: number, z: number, options?: TaskOptions): boolean {
    return call<boolean>('world.isChunkLoaded', { world: this.name, x, z }, options);
  }
  isChunkGenerated(x: number, z: number, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('world.isChunkGenerated', { world: this.name, x, z }, options);
  }
  isChunkGeneratedSync(x: number, z: number, options?: TaskOptions): boolean {
    return call<boolean>('world.isChunkGenerated', { world: this.name, x, z }, options);
  }
  loadChunk(x: number, z: number, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('world.loadChunk', { world: this.name, x, z }, options);
  }
  loadChunkSync(x: number, z: number, options?: TaskOptions): boolean {
    return call<boolean>('world.loadChunk', { world: this.name, x, z }, options);
  }
  unloadChunk(x: number, z: number, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('world.unloadChunk', { world: this.name, x, z }, options);
  }
  unloadChunkSync(x: number, z: number, options?: TaskOptions): boolean {
    return call<boolean>('world.unloadChunk', { world: this.name, x, z }, options);
  }
  getBlockLightLevel(x: number, y: number, z: number, options?: TaskOptions): Promise<number> {
    return post<number>('world.getBlockLightLevel', { world: this.name, x, y, z }, options);
  }
  getBlockLightLevelSync(x: number, y: number, z: number, options?: TaskOptions): number {
    return call<number>('world.getBlockLightLevel', { world: this.name, x, y, z }, options);
  }
  getSkyLightLevel(x: number, y: number, z: number, options?: TaskOptions): Promise<number> {
    return post<number>('world.getSkyLightLevel', { world: this.name, x, y, z }, options);
  }
  getSkyLightLevelSync(x: number, y: number, z: number, options?: TaskOptions): number {
    return call<number>('world.getSkyLightLevel', { world: this.name, x, y, z }, options);
  }
  getGameRule(rule: string, options?: TaskOptions): Promise<string | null> {
    return post<string | null>('world.getGameRule', { world: this.name, rule }, options);
  }
  getGameRuleSync(rule: string, options?: TaskOptions): string | null {
    return call<string | null>('world.getGameRule', { world: this.name, rule }, options);
  }
  setGameRule(rule: string, value: string, options?: TaskOptions): Promise<boolean> {
    return post('world.setGameRule', { world: this.name, rule, value }, options);
  }
  setGameRuleSync(rule: string, value: string, options?: TaskOptions): boolean {
    return call<boolean>('world.setGameRule', { world: this.name, rule, value }, options);
  }
  getBiome(x: number, y: number, z: number, options?: TaskOptions): Promise<string> {
    return post<string>('world.getBiome', { world: this.name, x, y, z }, options);
  }
  getBiomeSync(x: number, y: number, z: number, options?: TaskOptions): string {
    return call<string>('world.getBiome', { world: this.name, x, y, z }, options);
  }
  getBlock(x: number, y: number, z: number, options?: TaskOptions): Promise<Block | null> {
    return post<{ world: string; x: number; y: number; z: number; type: string; state: BlockState }>('world.getBlock', { world: this.name, x, y, z }, options)
      .then((r) => (r ? new Block(r.type, r.state, new Location(r.x, r.y, r.z, 0, 0, r.world)) : null));
  }
  getBlockSync(x: number, y: number, z: number, options?: TaskOptions): Block | null {
    const r = call<{ world: string; x: number; y: number; z: number; type: string; state: BlockState }>('world.getBlock', { world: this.name, x, y, z }, options);
    return r ? new Block(r.type, r.state, new Location(r.x, r.y, r.z, 0, 0, r.world)) : null;
  }
  setBlock(x: number, y: number, z: number, block: Block | string, options?: TaskOptions): Promise<void> {
    const p: Record<string, unknown> = { world: this.name, x, y, z };
    if (typeof block === 'string') {
      p.blockType = block;
    } else {
      p.blockType = block.type;
      if (block.state && Object.keys(block.state).length > 0) p.state = block.state;
    }
    return post('world.setBlock', p, options);
  }
  setBlockSync(x: number, y: number, z: number, block: Block | string, options?: TaskOptions): void {
    const p: Record<string, unknown> = { world: this.name, x, y, z };
    if (typeof block === 'string') {
      p.blockType = block;
    } else {
      p.blockType = block.type;
      if (block.state && Object.keys(block.state).length > 0) p.state = block.state;
    }
    call('world.setBlock', p, options);
  }
  getEntities(options?: TaskOptions): Promise<string[]> {
    return post<string[]>('world.getEntities', { world: this.name }, options);
  }
  getEntitiesSync(options?: TaskOptions): string[] {
    return call<string[]>('world.getEntities', { world: this.name }, options);
  }
  getPlayers(options?: TaskOptions): Promise<string[]> {
    return post<string[]>('world.getPlayers', { world: this.name }, options);
  }
  getPlayersSync(options?: TaskOptions): string[] {
    return call<string[]>('world.getPlayers', { world: this.name }, options);
  }
  getNearbyEntities(x: number, y: number, z: number, radius: number, options?: TaskOptions): Promise<string[]> {
    return post<string[]>('world.getNearbyEntities', { world: this.name, x, y, z, radius }, options);
  }
  getNearbyEntitiesSync(x: number, y: number, z: number, radius: number, options?: TaskOptions): string[] {
    return call<string[]>('world.getNearbyEntities', { world: this.name, x, y, z, radius }, options);
  }
  /** 在指定位置掉落物品（`item` 为 ItemStack 数据快照或材质名字符串；字符串时可用 `amount` 指定数量——旧式参数兼容）。 */
  dropItem(x: number, y: number, z: number, item: ItemStack | string, amount?: number, options?: TaskOptions): Promise<void> {
    const it: Record<string, unknown> = typeof item === 'string' ? { type: item } : { ...item };
    if (typeof item === 'string' && amount !== undefined) it.amount = amount;
    return post('world.dropItem', { world: this.name, x, y, z, item: it }, options);
  }
  dropItemSync(x: number, y: number, z: number, item: ItemStack | string, amount?: number, options?: TaskOptions): void {
    const it: Record<string, unknown> = typeof item === 'string' ? { type: item } : { ...item };
    if (typeof item === 'string' && amount !== undefined) it.amount = amount;
    call('world.dropItem', { world: this.name, x, y, z, item: it }, options);
  }
  strikeLightning(x: number, y: number, z: number, options?: TaskOptions): Promise<void> {
    return post('world.strikeLightning', { world: this.name, x, y, z }, options);
  }
  strikeLightningSync(x: number, y: number, z: number, options?: TaskOptions): void {
    call('world.strikeLightning', { world: this.name, x, y, z }, options);
  }
  strikeLightningEffect(x: number, y: number, z: number, options?: TaskOptions): Promise<void> {
    return post('world.strikeLightningEffect', { world: this.name, x, y, z }, options);
  }
  strikeLightningEffectSync(x: number, y: number, z: number, options?: TaskOptions): void {
    call('world.strikeLightningEffect', { world: this.name, x, y, z }, options);
  }
  createExplosion(x: number, y: number, z: number, power?: number, fire?: boolean, breaks?: boolean, options?: TaskOptions): Promise<void> {
    return post('world.createExplosion', { world: this.name, x, y, z, power, setFire: fire, breakBlocks: breaks }, options);
  }
  createExplosionSync(x: number, y: number, z: number, power?: number, fire?: boolean, breaks?: boolean, options?: TaskOptions): void {
    call('world.createExplosion', { world: this.name, x, y, z, power, setFire: fire, breakBlocks: breaks }, options);
  }
  spawnEntity(type: string, x: number, y: number, z: number, options?: TaskOptions): Promise<string | null> {
    return post<string | null>('world.spawnEntity', { world: this.name, type, x, y, z }, options);
  }
  spawnEntitySync(type: string, x: number, y: number, z: number, options?: TaskOptions): string | null {
    return call<string | null>('world.spawnEntity', { world: this.name, type, x, y, z }, options);
  }
  playSound(sound: string, x: number, y: number, z: number, volume?: number, pitch?: number, options?: TaskOptions): Promise<void> {
    return post('world.playSound', { world: this.name, sound, x, y, z, volume, pitch }, options);
  }
  playSoundSync(sound: string, x: number, y: number, z: number, volume?: number, pitch?: number, options?: TaskOptions): void {
    call('world.playSound', { world: this.name, sound, x, y, z, volume, pitch }, options);
  }
}
