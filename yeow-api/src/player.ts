import { call, post } from './task.js';
import { Location, LocationData } from './location.js';
import type { ItemStack } from './item.js';

interface PlayerData {
  uuid: string;
  name: string;
}

export class Player {
  static get(identifier: string): Promise<Player | null> {
    return post<PlayerData>('player.get', { identifier }).then((d) => (d ? new Player(d.uuid, d.name) : null));
  }
  static getSync(identifier: string): Player | null {
    const d = call<PlayerData>('player.get', { identifier });
    return d ? new Player(d.uuid, d.name) : null;
  }
  static getAll(): Promise<Player[]> {
    return post<PlayerData[]>('player.getAll', {}).then((list) => list.map((r) => new Player(r.uuid, r.name)));
  }
  static getAllSync(): Player[] {
    return call<PlayerData[]>('player.getAll', {}).map((r) => new Player(r.uuid, r.name));
  }

  constructor(public readonly uuid: string, private _name?: string) {}

  get name(): string { return this._name ?? ''; }

  get ping(): number { return call<number>('player.getPing', { uuid: this.uuid }); }
  getPing(): Promise<number> { return post<number>('player.getPing', { uuid: this.uuid }); }

  get gamemode(): string { return call<string>('player.getGamemode', { uuid: this.uuid }); }
  set gamemode(v: string) { call('player.setGamemode', { uuid: this.uuid, value: v }); }
  getGamemode(): Promise<string> { return post<string>('player.getGamemode', { uuid: this.uuid }); }
  setGamemode(v: string): Promise<void> { return post('player.setGamemode', { uuid: this.uuid, value: v }); }

  get health(): number { return call<number>('player.getHealth', { uuid: this.uuid }); }
  set health(v: number) { call('player.setHealth', { uuid: this.uuid, value: v }); }
  getHealth(): Promise<number> { return post<number>('player.getHealth', { uuid: this.uuid }); }
  setHealth(v: number): Promise<void> { return post('player.setHealth', { uuid: this.uuid, value: v }); }

  get food(): number { return call<number>('player.getFood', { uuid: this.uuid }); }
  set food(v: number) { call('player.setFood', { uuid: this.uuid, value: v }); }
  getFood(): Promise<number> { return post<number>('player.getFood', { uuid: this.uuid }); }
  setFood(v: number): Promise<void> { return post('player.setFood', { uuid: this.uuid, value: v }); }

  get exp(): number { return call<number>('player.getExp', { uuid: this.uuid }); }
  set exp(v: number) { call('player.setExp', { uuid: this.uuid, value: v }); }
  getExp(): Promise<number> { return post<number>('player.getExp', { uuid: this.uuid }); }
  setExp(v: number): Promise<void> { return post('player.setExp', { uuid: this.uuid, value: v }); }

  get level(): number { return call<number>('player.getLevel', { uuid: this.uuid }); }
  set level(v: number) { call('player.setLevel', { uuid: this.uuid, value: v }); }
  getLevel(): Promise<number> { return post<number>('player.getLevel', { uuid: this.uuid }); }
  setLevel(v: number): Promise<void> { return post('player.setLevel', { uuid: this.uuid, value: v }); }

  get isOp(): boolean { return call<boolean>('player.isOp', { uuid: this.uuid }); }
  isOpAsync(): Promise<boolean> { return post<boolean>('player.isOp', { uuid: this.uuid }); }

  get online(): boolean { return call<boolean>('player.isOnline', { uuid: this.uuid }); }
  getOnline(): Promise<boolean> { return post<boolean>('player.isOnline', { uuid: this.uuid }); }

  get isFlying(): boolean { return call<boolean>('player.isFlying', { uuid: this.uuid }); }
  set isFlying(v: boolean) { call('player.setFlying', { uuid: this.uuid, value: v }); }
  isFlyingAsync(): Promise<boolean> { return post<boolean>('player.isFlying', { uuid: this.uuid }); }
  setFlying(v: boolean): Promise<void> { return post('player.setFlying', { uuid: this.uuid, value: v }); }

  get isSneaking(): boolean { return call<boolean>('player.isSneaking', { uuid: this.uuid }); }
  isSneakingAsync(): Promise<boolean> { return post<boolean>('player.isSneaking', { uuid: this.uuid }); }
  get isSprinting(): boolean { return call<boolean>('player.isSprinting', { uuid: this.uuid }); }
  isSprintingAsync(): Promise<boolean> { return post<boolean>('player.isSprinting', { uuid: this.uuid }); }

  get bedLocation(): Location | null {
    const r = call<LocationData>('player.getBedLocation', { uuid: this.uuid });
    return r ? Location.from(r) : null;
  }
  getBedLocation(): Promise<Location | null> {
    return post<LocationData>('player.getBedLocation', { uuid: this.uuid }).then((r) => (r ? Location.from(r) : null));
  }

  get allowFlight(): boolean { return call<boolean>('player.getAllowFlight', { uuid: this.uuid }); }
  set allowFlight(v: boolean) { call('player.setAllowFlight', { uuid: this.uuid, value: v }); }
  getAllowFlight(): Promise<boolean> { return post<boolean>('player.getAllowFlight', { uuid: this.uuid }); }
  setAllowFlight(v: boolean): Promise<void> { return post('player.setAllowFlight', { uuid: this.uuid, value: v }); }

  get walkSpeed(): number { return call<number>('player.getWalkSpeed', { uuid: this.uuid }); }
  set walkSpeed(v: number) { call('player.setWalkSpeed', { uuid: this.uuid, value: v }); }
  getWalkSpeed(): Promise<number> { return post<number>('player.getWalkSpeed', { uuid: this.uuid }); }
  setWalkSpeed(v: number): Promise<void> { return post('player.setWalkSpeed', { uuid: this.uuid, value: v }); }

  get flySpeed(): number { return call<number>('player.getFlySpeed', { uuid: this.uuid }); }
  set flySpeed(v: number) { call('player.setFlySpeed', { uuid: this.uuid, value: v }); }
  getFlySpeed(): Promise<number> { return post<number>('player.getFlySpeed', { uuid: this.uuid }); }
  setFlySpeed(v: number): Promise<void> { return post('player.setFlySpeed', { uuid: this.uuid, value: v }); }

  get world(): string { return call<string>('player.getWorld', { uuid: this.uuid }); }
  getWorld(): Promise<string> { return post<string>('player.getWorld', { uuid: this.uuid }); }

  get location(): Location | null {
    const r = call<LocationData>('player.getLocation', { uuid: this.uuid });
    return r ? Location.from(r) : null;
  }
  getLocation(): Promise<Location | null> {
    return post<LocationData>('player.getLocation', { uuid: this.uuid }).then((r) => (r ? Location.from(r) : null));
  }

  get displayName(): string { return call<string>('player.getDisplayName', { uuid: this.uuid }); }
  set displayName(v: string | null) { call('player.setDisplayName', { uuid: this.uuid, value: v }); }
  getDisplayName(): Promise<string> { return post<string>('player.getDisplayName', { uuid: this.uuid }); }
  setDisplayName(v: string | null): Promise<void> { return post('player.setDisplayName', { uuid: this.uuid, value: v }); }

  get saturation(): number { return call<number>('player.getSaturation', { uuid: this.uuid }); }
  getSaturation(): Promise<number> { return post<number>('player.getSaturation', { uuid: this.uuid }); }

  get totalExperience(): number { return call<number>('player.getTotalExperience', { uuid: this.uuid }); }
  getTotalExperience(): Promise<number> { return post<number>('player.getTotalExperience', { uuid: this.uuid }); }

  sendMessage(msg: string): Promise<void> { return post('player.sendMessage', { uuid: this.uuid, message: msg }); }
  sendMessageSync(msg: string): void { call('player.sendMessage', { uuid: this.uuid, message: msg }); }
  kick(reason?: string): Promise<void> { return post('player.kick', { uuid: this.uuid, reason }); }
  kickSync(reason?: string): void { call('player.kick', { uuid: this.uuid, reason }); }
  sendTitle(title?: string, subtitle?: string, fadeIn?: number, stay?: number, fadeOut?: number): Promise<void> {
    return post('player.sendTitle', { uuid: this.uuid, title, subtitle, fadeIn, stay, fadeOut });
  }
  sendTitleSync(title?: string, subtitle?: string, fadeIn?: number, stay?: number, fadeOut?: number): void {
    call('player.sendTitle', { uuid: this.uuid, title, subtitle, fadeIn, stay, fadeOut });
  }
  playSound(sound: string, volume?: number, pitch?: number): Promise<void> {
    return post('player.playSound', { uuid: this.uuid, sound, volume, pitch });
  }
  playSoundSync(sound: string, volume?: number, pitch?: number): void {
    call('player.playSound', { uuid: this.uuid, sound, volume, pitch });
  }
  giveExp(amount: number): Promise<void> { return post('player.giveExp', { uuid: this.uuid, amount }); }
  giveExpSync(amount: number): void { call('player.giveExp', { uuid: this.uuid, amount }); }
  hasPermission(node: string): Promise<boolean> {
    return post<boolean>('player.hasPermission', { uuid: this.uuid, permission: node });
  }
  hasPermissionSync(node: string): boolean {
    return call<boolean>('player.hasPermission', { uuid: this.uuid, permission: node });
  }
  teleport(loc: Location): Promise<void> { return post('player.teleport', { uuid: this.uuid, ...loc.toObject() }); }
  teleportSync(loc: Location): void { call('player.teleport', { uuid: this.uuid, ...loc.toObject() }); }
  sendActionBar(message: string): Promise<void> { return post('player.sendActionBar', { uuid: this.uuid, message }); }
  sendActionBarSync(message: string): void { call('player.sendActionBar', { uuid: this.uuid, message }); }
  sendResourcePack(url: string, hash?: string, prompt?: string, force?: boolean): Promise<void> {
    return post('player.sendResourcePack', { uuid: this.uuid, url, hash, prompt, force });
  }
  getItemInMainHand(): Promise<ItemStack | null> { return post<ItemStack | null>('player.getItemInMainHand', { uuid: this.uuid }); }
  getItemInMainHandSync(): ItemStack | null { return call<ItemStack | null>('player.getItemInMainHand', { uuid: this.uuid }); }
  getItemInOffHand(): Promise<ItemStack | null> { return post<ItemStack | null>('player.getItemInOffHand', { uuid: this.uuid }); }
  getItemInOffHandSync(): ItemStack | null { return call<ItemStack | null>('player.getItemInOffHand', { uuid: this.uuid }); }
}
