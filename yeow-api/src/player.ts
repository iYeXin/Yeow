import { call, post } from './task.js';
import type { TaskOptions } from './task.js';
import { Location, LocationData } from './location.js';
import type { ItemStack } from './item.js';
import type { Message } from './message.js';

interface PlayerData {
  uuid: string;
  name: string;
}

export class Player {
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

  constructor(public readonly uuid: string, private _name?: string) {}

  get name(): string { return this._name ?? ''; }

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
  getOnline(options?: TaskOptions): Promise<boolean> { return post<boolean>('player.isOnline', { uuid: this.uuid }, options); }

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
  giveExp(amount: number, options?: TaskOptions): Promise<void> { return post('player.giveExp', { uuid: this.uuid, amount }, options); }
  giveExpSync(amount: number, options?: TaskOptions): void { call('player.giveExp', { uuid: this.uuid, amount }, options); }
  hasPermission(node: string, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('player.hasPermission', { uuid: this.uuid, permission: node }, options);
  }
  hasPermissionSync(node: string, options?: TaskOptions): boolean {
    return call<boolean>('player.hasPermission', { uuid: this.uuid, permission: node }, options);
  }
  teleport(loc: Location, options?: TaskOptions): Promise<void> { return post('player.teleport', { uuid: this.uuid, ...loc.toObject() }, options); }
  teleportSync(loc: Location, options?: TaskOptions): void { call('player.teleport', { uuid: this.uuid, ...loc.toObject() }, options); }
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
}
