import { call, post } from './task.js';
import type { TaskOptions } from './task.js';
import { Location, LocationData } from './location.js';

export interface BoundingBox {
  minX: number;
  minY: number;
  minZ: number;
  maxX: number;
  maxY: number;
  maxZ: number;
}

export class Entity {
  static get(uuid: string, options?: TaskOptions): Promise<Entity | null> {
    return post<{ uuid: string }>('entity.get', { uuid }, options).then((d) => (d ? new Entity(d.uuid) : null));
  }
  static getSync(uuid: string, options?: TaskOptions): Entity | null {
    const d = call<{ uuid: string }>('entity.get', { uuid }, options);
    return d ? new Entity(d.uuid) : null;
  }

  constructor(public readonly uuid: string) {}

  get type(): string { return call<string>('entity.getType', { uuid: this.uuid }); }
  getType(options?: TaskOptions): Promise<string> { return post<string>('entity.getType', { uuid: this.uuid }, options); }

  get name(): string { return call<string>('entity.getName', { uuid: this.uuid }); }
  getName(options?: TaskOptions): Promise<string> { return post<string>('entity.getName', { uuid: this.uuid }, options); }

  get customName(): string | null { return call<string | null>('entity.getCustomName', { uuid: this.uuid }); }
  set customName(v: string | null) { call('entity.setCustomName', { uuid: this.uuid, value: v }); }
  getCustomName(options?: TaskOptions): Promise<string | null> { return post<string | null>('entity.getCustomName', { uuid: this.uuid }, options); }
  setCustomName(v: string | null, options?: TaskOptions): Promise<void> { return post('entity.setCustomName', { uuid: this.uuid, value: v }, options); }

  setCustomNameVisible(v: boolean, options?: TaskOptions): Promise<void> {
    return post('entity.setCustomNameVisible', { uuid: this.uuid, value: v }, options);
  }
  setCustomNameVisibleSync(v: boolean, options?: TaskOptions): void {
    call('entity.setCustomNameVisible', { uuid: this.uuid, value: v }, options);
  }

  get world(): string | null { return call<string | null>('entity.getWorld', { uuid: this.uuid }); }
  getWorld(options?: TaskOptions): Promise<string | null> { return post<string | null>('entity.getWorld', { uuid: this.uuid }, options); }

  get location(): Location | null {
    const r = call<LocationData>('entity.getLocation', { uuid: this.uuid });
    return r ? Location.from(r) : null;
  }
  getLocation(options?: TaskOptions): Promise<Location | null> {
    return post<LocationData>('entity.getLocation', { uuid: this.uuid }, options).then((r) => (r ? Location.from(r) : null));
  }

  get isGlowing(): boolean { return call<boolean>('entity.isGlowing', { uuid: this.uuid }); }
  set isGlowing(v: boolean) { call('entity.setGlowing', { uuid: this.uuid, value: v }); }
  isGlowingAsync(options?: TaskOptions): Promise<boolean> { return post<boolean>('entity.isGlowing', { uuid: this.uuid }, options); }
  setGlowing(v: boolean, options?: TaskOptions): Promise<void> { return post('entity.setGlowing', { uuid: this.uuid, value: v }, options); }

  get isInvulnerable(): boolean { return call<boolean>('entity.isInvulnerable', { uuid: this.uuid }); }
  set isInvulnerable(v: boolean) { call('entity.setInvulnerable', { uuid: this.uuid, value: v }); }
  isInvulnerableAsync(options?: TaskOptions): Promise<boolean> { return post<boolean>('entity.isInvulnerable', { uuid: this.uuid }, options); }
  setInvulnerable(v: boolean, options?: TaskOptions): Promise<void> { return post('entity.setInvulnerable', { uuid: this.uuid, value: v }, options); }

  get isSilent(): boolean { return call<boolean>('entity.isSilent', { uuid: this.uuid }); }
  set isSilent(v: boolean) { call('entity.setSilent', { uuid: this.uuid, value: v }); }
  isSilentAsync(options?: TaskOptions): Promise<boolean> { return post<boolean>('entity.isSilent', { uuid: this.uuid }, options); }
  setSilent(v: boolean, options?: TaskOptions): Promise<void> { return post('entity.setSilent', { uuid: this.uuid, value: v }, options); }

  get hasGravity(): boolean { return call<boolean>('entity.hasGravity', { uuid: this.uuid }); }
  set hasGravity(v: boolean) { call('entity.setGravity', { uuid: this.uuid, value: v }); }
  hasGravityAsync(options?: TaskOptions): Promise<boolean> { return post<boolean>('entity.hasGravity', { uuid: this.uuid }, options); }
  setGravity(v: boolean, options?: TaskOptions): Promise<void> { return post('entity.setGravity', { uuid: this.uuid, value: v }, options); }

  get passengers(): string[] { return call<string[]>('entity.getPassengers', { uuid: this.uuid }); }
  getPassengers(options?: TaskOptions): Promise<string[]> { return post<string[]>('entity.getPassengers', { uuid: this.uuid }, options); }

  get vehicle(): string | null { return call<string | null>('entity.getVehicle', { uuid: this.uuid }); }
  getVehicle(options?: TaskOptions): Promise<string | null> { return post<string | null>('entity.getVehicle', { uuid: this.uuid }, options); }

  get boundingBox(): BoundingBox {
    return call<BoundingBox>('entity.getBoundingBox', { uuid: this.uuid });
  }
  getBoundingBox(options?: TaskOptions): Promise<BoundingBox> { return post<BoundingBox>('entity.getBoundingBox', { uuid: this.uuid }, options); }

  remove(options?: TaskOptions): Promise<void> { return post('entity.remove', { uuid: this.uuid }, options); }
  removeSync(options?: TaskOptions): void { call('entity.remove', { uuid: this.uuid }, options); }
  teleport(loc: Location, options?: TaskOptions): Promise<void> { return post('entity.teleport', { uuid: this.uuid, ...loc.toObject() }, options); }
  teleportSync(loc: Location, options?: TaskOptions): void { call('entity.teleport', { uuid: this.uuid, ...loc.toObject() }, options); }

  // ── 基础补齐（2026-08-13） ──

  /** 速度向量 { x, y, z }（方块/秒）。 */
  get velocity(): { x: number; y: number; z: number } { return call<{ x: number; y: number; z: number }>('entity.getVelocity', { uuid: this.uuid }); }
  set velocity(v: { x: number; y: number; z: number }) { call('entity.setVelocity', { uuid: this.uuid, x: v.x, y: v.y, z: v.z }); }
  getVelocity(options?: TaskOptions): Promise<{ x: number; y: number; z: number }> { return post('entity.getVelocity', { uuid: this.uuid }, options); }
  setVelocity(v: { x: number; y: number; z: number }, options?: TaskOptions): Promise<void> { return post('entity.setVelocity', { uuid: this.uuid, x: v.x, y: v.y, z: v.z }, options); }

  /** 着火刻数（0 = 未着火）。 */
  get fireTicks(): number { return call<number>('entity.getFireTicks', { uuid: this.uuid }); }
  set fireTicks(v: number) { call('entity.setFireTicks', { uuid: this.uuid, value: v }); }
  getFireTicks(options?: TaskOptions): Promise<number> { return post<number>('entity.getFireTicks', { uuid: this.uuid }, options); }
  setFireTicks(v: number, options?: TaskOptions): Promise<void> { return post('entity.setFireTicks', { uuid: this.uuid, value: v }, options); }

  /** 已存活刻数。 */
  get ticksLived(): number { return call<number>('entity.getTicksLived', { uuid: this.uuid }); }
  set ticksLived(v: number) { call('entity.setTicksLived', { uuid: this.uuid, value: v }); }
  getTicksLived(options?: TaskOptions): Promise<number> { return post<number>('entity.getTicksLived', { uuid: this.uuid }, options); }
  setTicksLived(v: number, options?: TaskOptions): Promise<void> { return post('entity.setTicksLived', { uuid: this.uuid, value: v }, options); }

  /** 是否在地面上。 */
  get isOnGround(): boolean { return call<boolean>('entity.isOnGround', { uuid: this.uuid }); }
  isOnGroundAsync(options?: TaskOptions): Promise<boolean> { return post<boolean>('entity.isOnGround', { uuid: this.uuid }, options); }
}

export class LivingEntity extends Entity {
  get health(): number { return call<number>('entity.getHealth', { uuid: this.uuid }); }
  set health(v: number) { call('entity.setHealth', { uuid: this.uuid, value: v }); }
  getHealth(options?: TaskOptions): Promise<number> { return post<number>('entity.getHealth', { uuid: this.uuid }, options); }
  setHealth(v: number, options?: TaskOptions): Promise<void> { return post('entity.setHealth', { uuid: this.uuid, value: v }, options); }

  get maxHealth(): number { return call<number>('entity.getMaxHealth', { uuid: this.uuid }); }
  getMaxHealth(options?: TaskOptions): Promise<number> { return post<number>('entity.getMaxHealth', { uuid: this.uuid }, options); }

  get isDead(): boolean { return call<boolean>('entity.isDead', { uuid: this.uuid }); }
  isDeadAsync(options?: TaskOptions): Promise<boolean> { return post<boolean>('entity.isDead', { uuid: this.uuid }, options); }

  /** 施加伤害（可带伤害来源实体 uuid）。 */
  damage(amount: number, damager?: string, options?: TaskOptions): Promise<void> {
    return post('entity.damage', { uuid: this.uuid, amount, damager }, options);
  }
  damageSync(amount: number, damager?: string, options?: TaskOptions): void {
    call('entity.damage', { uuid: this.uuid, amount, damager }, options);
  }

  /**
   * 设置 AI 目标（**不保证必然生效**——取决于实体类型/寻路能力）。
   * 目标为实体（targetUuid）或位置（world+x+y+z，可带 speed）。
   */
  setTarget(target: { targetUuid: string } | { world: string; x: number; y: number; z: number; speed?: number }, options?: TaskOptions): Promise<boolean> {
    return post<boolean>('entity.setTarget', { uuid: this.uuid, ...target }, options);
  }
  setTargetSync(target: { targetUuid: string } | { world: string; x: number; y: number; z: number; speed?: number }, options?: TaskOptions): boolean {
    return call<boolean>('entity.setTarget', { uuid: this.uuid, ...target }, options);
  }
}
