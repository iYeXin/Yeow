import { call, post } from './task.js';
import { Location, LocationData } from './location.js';
import { Block } from './block.js';

interface WorldData {
  name: string;
}

export class World {
  static get(name: string): Promise<World | null> {
    return post<WorldData>('world.get', { name }).then((d) => (d ? new World(d.name) : null));
  }
  static getSync(name: string): World | null {
    const d = call<WorldData>('world.get', { name });
    return d ? new World(d.name) : null;
  }
  static getAll(): Promise<World[]> {
    return post<WorldData[]>('world.getAll', {}).then((list) => list.map((r) => new World(r.name)));
  }
  static getAllSync(): World[] {
    return call<WorldData[]>('world.getAll', {}).map((r) => new World(r.name));
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

  getHighestBlockY(x: number, z: number): Promise<number> {
    return post<number>('world.getHighestBlockY', { world: this.name, x, z });
  }
  getHighestBlockYSync(x: number, z: number): number {
    return call<number>('world.getHighestBlockY', { world: this.name, x, z });
  }
  getChunkAt(x: number, z: number): Promise<{ x: number; z: number }> {
    return post('world.getChunkAt', { world: this.name, x, z });
  }
  getChunkAtSync(x: number, z: number): { x: number; z: number } {
    return call('world.getChunkAt', { world: this.name, x, z });
  }
  isChunkLoaded(x: number, z: number): Promise<boolean> {
    return post<boolean>('world.isChunkLoaded', { world: this.name, x, z });
  }
  isChunkLoadedSync(x: number, z: number): boolean {
    return call<boolean>('world.isChunkLoaded', { world: this.name, x, z });
  }
  loadChunk(x: number, z: number): Promise<boolean> {
    return post<boolean>('world.loadChunk', { world: this.name, x, z });
  }
  loadChunkSync(x: number, z: number): boolean {
    return call<boolean>('world.loadChunk', { world: this.name, x, z });
  }
  unloadChunk(x: number, z: number): Promise<boolean> {
    return post<boolean>('world.unloadChunk', { world: this.name, x, z });
  }
  unloadChunkSync(x: number, z: number): boolean {
    return call<boolean>('world.unloadChunk', { world: this.name, x, z });
  }
  getBlockLightLevel(x: number, y: number, z: number): Promise<number> {
    return post<number>('world.getBlockLightLevel', { world: this.name, x, y, z });
  }
  getBlockLightLevelSync(x: number, y: number, z: number): number {
    return call<number>('world.getBlockLightLevel', { world: this.name, x, y, z });
  }
  getSkyLightLevel(x: number, y: number, z: number): Promise<number> {
    return post<number>('world.getSkyLightLevel', { world: this.name, x, y, z });
  }
  getSkyLightLevelSync(x: number, y: number, z: number): number {
    return call<number>('world.getSkyLightLevel', { world: this.name, x, y, z });
  }
  getGameRule(rule: string): Promise<string | null> {
    return post<string | null>('world.getGameRule', { world: this.name, rule });
  }
  getGameRuleSync(rule: string): string | null {
    return call<string | null>('world.getGameRule', { world: this.name, rule });
  }
  setGameRule(rule: string, value: string): Promise<boolean> {
    return post('world.setGameRule', { world: this.name, rule, value });
  }
  setGameRuleSync(rule: string, value: string): boolean {
    return call<boolean>('world.setGameRule', { world: this.name, rule, value });
  }
  getBiome(x: number, y: number, z: number): Promise<string> {
    return post<string>('world.getBiome', { world: this.name, x, y, z });
  }
  getBiomeSync(x: number, y: number, z: number): string {
    return call<string>('world.getBiome', { world: this.name, x, y, z });
  }
  getBlock(x: number, y: number, z: number): Promise<Block | null> {
    return post<{ x: number; y: number; z: number; type: string }>('world.getBlock', { world: this.name, x, y, z })
      .then((r) => (r ? new Block(this.name, r.x, r.y, r.z, r.type) : null));
  }
  getBlockSync(x: number, y: number, z: number): Block | null {
    const r = call<{ x: number; y: number; z: number; type: string }>('world.getBlock', { world: this.name, x, y, z });
    return r ? new Block(this.name, r.x, r.y, r.z, r.type) : null;
  }
  setBlock(x: number, y: number, z: number, blockType: string): Promise<void> {
    return post('world.setBlock', { world: this.name, x, y, z, blockType });
  }
  setBlockSync(x: number, y: number, z: number, blockType: string): void {
    call('world.setBlock', { world: this.name, x, y, z, blockType });
  }
  getEntities(): Promise<string[]> {
    return post<string[]>('world.getEntities', { world: this.name });
  }
  getEntitiesSync(): string[] {
    return call<string[]>('world.getEntities', { world: this.name });
  }
  getPlayers(): Promise<string[]> {
    return post<string[]>('world.getPlayers', { world: this.name });
  }
  getPlayersSync(): string[] {
    return call<string[]>('world.getPlayers', { world: this.name });
  }
  getNearbyEntities(x: number, y: number, z: number, radius: number): Promise<string[]> {
    return post<string[]>('world.getNearbyEntities', { world: this.name, x, y, z, radius });
  }
  getNearbyEntitiesSync(x: number, y: number, z: number, radius: number): string[] {
    return call<string[]>('world.getNearbyEntities', { world: this.name, x, y, z, radius });
  }
  dropItem(x: number, y: number, z: number, itemType: string, amount?: number): Promise<void> {
    return post('world.dropItem', { world: this.name, x, y, z, itemType, amount });
  }
  dropItemSync(x: number, y: number, z: number, itemType: string, amount?: number): void {
    call('world.dropItem', { world: this.name, x, y, z, itemType, amount });
  }
  strikeLightning(x: number, y: number, z: number): Promise<void> {
    return post('world.strikeLightning', { world: this.name, x, y, z });
  }
  strikeLightningSync(x: number, y: number, z: number): void {
    call('world.strikeLightning', { world: this.name, x, y, z });
  }
  strikeLightningEffect(x: number, y: number, z: number): Promise<void> {
    return post('world.strikeLightningEffect', { world: this.name, x, y, z });
  }
  strikeLightningEffectSync(x: number, y: number, z: number): void {
    call('world.strikeLightningEffect', { world: this.name, x, y, z });
  }
  createExplosion(x: number, y: number, z: number, power?: number, fire?: boolean, breaks?: boolean): Promise<void> {
    return post('world.createExplosion', { world: this.name, x, y, z, power, setFire: fire, breakBlocks: breaks });
  }
  createExplosionSync(x: number, y: number, z: number, power?: number, fire?: boolean, breaks?: boolean): void {
    call('world.createExplosion', { world: this.name, x, y, z, power, setFire: fire, breakBlocks: breaks });
  }
  spawnEntity(type: string, x: number, y: number, z: number): Promise<string | null> {
    return post<string | null>('world.spawnEntity', { world: this.name, type, x, y, z });
  }
  spawnEntitySync(type: string, x: number, y: number, z: number): string | null {
    return call<string | null>('world.spawnEntity', { world: this.name, type, x, y, z });
  }
  playSound(sound: string, x: number, y: number, z: number, volume?: number, pitch?: number): Promise<void> {
    return post('world.playSound', { world: this.name, sound, x, y, z, volume, pitch });
  }
  playSoundSync(sound: string, x: number, y: number, z: number, volume?: number, pitch?: number): void {
    call('world.playSound', { world: this.name, sound, x, y, z, volume, pitch });
  }
}
