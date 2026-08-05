import { call, post } from './task.js';
import type { TaskOptions } from './task.js';
import { Location, LocationData } from './location.js';
import { Block } from './block.js';
import { Chunk, ChunkData } from './chunk.js';

interface WorldData {
  name: string;
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
    return post<{ x: number; y: number; z: number; type: string }>('world.getBlock', { world: this.name, x, y, z }, options)
      .then((r) => (r ? new Block(this.name, r.x, r.y, r.z, r.type) : null));
  }
  getBlockSync(x: number, y: number, z: number, options?: TaskOptions): Block | null {
    const r = call<{ x: number; y: number; z: number; type: string }>('world.getBlock', { world: this.name, x, y, z }, options);
    return r ? new Block(this.name, r.x, r.y, r.z, r.type) : null;
  }
  setBlock(x: number, y: number, z: number, blockType: string, options?: TaskOptions): Promise<void> {
    return post('world.setBlock', { world: this.name, x, y, z, blockType }, options);
  }
  setBlockSync(x: number, y: number, z: number, blockType: string, options?: TaskOptions): void {
    call('world.setBlock', { world: this.name, x, y, z, blockType }, options);
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
  dropItem(x: number, y: number, z: number, itemType: string, amount?: number, options?: TaskOptions): Promise<void> {
    return post('world.dropItem', { world: this.name, x, y, z, itemType, amount }, options);
  }
  dropItemSync(x: number, y: number, z: number, itemType: string, amount?: number, options?: TaskOptions): void {
    call('world.dropItem', { world: this.name, x, y, z, itemType, amount }, options);
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
