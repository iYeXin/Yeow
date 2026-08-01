import { call, post } from './task.js';
import { Location, LocationData } from './location.js';

export class Entity {
  static get(uuid: string): Promise<Entity | null> {
    return post<{ uuid: string }>('entity.get', { uuid }).then((d) => (d ? new Entity(d.uuid) : null));
  }
  static getSync(uuid: string): Entity | null {
    const d = call<{ uuid: string }>('entity.get', { uuid });
    return d ? new Entity(d.uuid) : null;
  }

  constructor(public readonly uuid: string) {}

  get type(): string { return call<string>('entity.getType', { uuid: this.uuid }); }
  getType(): Promise<string> { return post<string>('entity.getType', { uuid: this.uuid }); }

  get name(): string { return call<string>('entity.getName', { uuid: this.uuid }); }
  getName(): Promise<string> { return post<string>('entity.getName', { uuid: this.uuid }); }

  get customName(): string | null { return call<string | null>('entity.getCustomName', { uuid: this.uuid }); }
  set customName(v: string | null) { call('entity.setCustomName', { uuid: this.uuid, value: v }); }
  getCustomName(): Promise<string | null> { return post<string | null>('entity.getCustomName', { uuid: this.uuid }); }
  setCustomName(v: string | null): Promise<void> { return post('entity.setCustomName', { uuid: this.uuid, value: v }); }

  setCustomNameVisible(v: boolean): Promise<void> {
    return post('entity.setCustomNameVisible', { uuid: this.uuid, value: v });
  }
  setCustomNameVisibleSync(v: boolean): void {
    call('entity.setCustomNameVisible', { uuid: this.uuid, value: v });
  }

  get world(): string | null { return call<string | null>('entity.getWorld', { uuid: this.uuid }); }
  getWorld(): Promise<string | null> { return post<string | null>('entity.getWorld', { uuid: this.uuid }); }

  get location(): Location | null {
    const r = call<LocationData>('entity.getLocation', { uuid: this.uuid });
    return r ? Location.from(r) : null;
  }
  getLocation(): Promise<Location | null> {
    return post<LocationData>('entity.getLocation', { uuid: this.uuid }).then((r) => (r ? Location.from(r) : null));
  }

  get isGlowing(): boolean { return call<boolean>('entity.isGlowing', { uuid: this.uuid }); }
  set isGlowing(v: boolean) { call('entity.setGlowing', { uuid: this.uuid, value: v }); }
  isGlowingAsync(): Promise<boolean> { return post<boolean>('entity.isGlowing', { uuid: this.uuid }); }
  setGlowing(v: boolean): Promise<void> { return post('entity.setGlowing', { uuid: this.uuid, value: v }); }

  get isInvulnerable(): boolean { return call<boolean>('entity.isInvulnerable', { uuid: this.uuid }); }
  set isInvulnerable(v: boolean) { call('entity.setInvulnerable', { uuid: this.uuid, value: v }); }
  isInvulnerableAsync(): Promise<boolean> { return post<boolean>('entity.isInvulnerable', { uuid: this.uuid }); }
  setInvulnerable(v: boolean): Promise<void> { return post('entity.setInvulnerable', { uuid: this.uuid, value: v }); }

  get isSilent(): boolean { return call<boolean>('entity.isSilent', { uuid: this.uuid }); }
  set isSilent(v: boolean) { call('entity.setSilent', { uuid: this.uuid, value: v }); }
  isSilentAsync(): Promise<boolean> { return post<boolean>('entity.isSilent', { uuid: this.uuid }); }
  setSilent(v: boolean): Promise<void> { return post('entity.setSilent', { uuid: this.uuid, value: v }); }

  get hasGravity(): boolean { return call<boolean>('entity.hasGravity', { uuid: this.uuid }); }
  set hasGravity(v: boolean) { call('entity.setGravity', { uuid: this.uuid, value: v }); }
  hasGravityAsync(): Promise<boolean> { return post<boolean>('entity.hasGravity', { uuid: this.uuid }); }
  setGravity(v: boolean): Promise<void> { return post('entity.setGravity', { uuid: this.uuid, value: v }); }

  get passengers(): string[] { return call<string[]>('entity.getPassengers', { uuid: this.uuid }); }
  getPassengers(): Promise<string[]> { return post<string[]>('entity.getPassengers', { uuid: this.uuid }); }

  get vehicle(): string | null { return call<string | null>('entity.getVehicle', { uuid: this.uuid }); }
  getVehicle(): Promise<string | null> { return post<string | null>('entity.getVehicle', { uuid: this.uuid }); }

  remove(): Promise<void> { return post('entity.remove', { uuid: this.uuid }); }
  removeSync(): void { call('entity.remove', { uuid: this.uuid }); }
  teleport(loc: Location): Promise<void> { return post('entity.teleport', { uuid: this.uuid, ...loc.toObject() }); }
  teleportSync(loc: Location): void { call('entity.teleport', { uuid: this.uuid, ...loc.toObject() }); }
}

export class LivingEntity extends Entity {
  get health(): number { return call<number>('entity.getHealth', { uuid: this.uuid }); }
  set health(v: number) { call('entity.setHealth', { uuid: this.uuid, value: v }); }
  getHealth(): Promise<number> { return post<number>('entity.getHealth', { uuid: this.uuid }); }
  setHealth(v: number): Promise<void> { return post('entity.setHealth', { uuid: this.uuid, value: v }); }

  get maxHealth(): number { return call<number>('entity.getMaxHealth', { uuid: this.uuid }); }
  getMaxHealth(): Promise<number> { return post<number>('entity.getMaxHealth', { uuid: this.uuid }); }

  get isDead(): boolean { return call<boolean>('entity.isDead', { uuid: this.uuid }); }
  isDeadAsync(): Promise<boolean> { return post<boolean>('entity.isDead', { uuid: this.uuid }); }
}
