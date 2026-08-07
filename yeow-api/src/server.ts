import { call, post } from './task.js';
import type { TaskOptions } from './task.js';

export function broadcast(msg: string, options?: TaskOptions): Promise<void> { return post('server.broadcast', { message: msg }, options); }
export function broadcastSync(msg: string, options?: TaskOptions): void { call('server.broadcast', { message: msg }, options); }
export function dispatchCommand(cmd: string, options?: TaskOptions): Promise<boolean> { return post<boolean>('command.dispatch', { command: cmd }, options); }
export function dispatchCommandSync(cmd: string, options?: TaskOptions): boolean { return call<boolean>('command.dispatch', { command: cmd }, options); }
export function setMotd(motd: string, options?: TaskOptions): Promise<void> { return post('server.setMotd', { motd }, options); }
export function setMotdSync(motd: string, options?: TaskOptions): void { call('server.setMotd', { motd }, options); }
export function getMotd(options?: TaskOptions): Promise<string> { return post<string>('server.getMotd', {}, options); }
export function getMotdSync(options?: TaskOptions): string { return call<string>('server.getMotd', {}, options); }
export function getVersion(options?: TaskOptions): Promise<string> { return post<string>('server.getVersion', {}, options); }
export function getVersionSync(options?: TaskOptions): string { return call<string>('server.getVersion', {}, options); }

export interface TpsInfo {
  /** 最近 1 分钟平均 TPS。 */
  tps1m: number;
  /** 最近 5 分钟平均 TPS。 */
  tps5m: number;
  /** 最近 15 分钟平均 TPS。 */
  tps15m: number;
}
/**
 * 服务器 TPS。**跨平台不保证可用**（Paper 平台基于 `Bukkit.getTPS`；其他平台运行时不保证，
 * 且未来 TPS 这一概念可能发生变化）——调用前需自行降级处理。
 */
export function getTps(options?: TaskOptions): Promise<TpsInfo> { return post<TpsInfo>('server.getTps', {}, options); }
export function getTpsSync(options?: TaskOptions): TpsInfo { return call<TpsInfo>('server.getTps', {}, options); }

/** 服务器最大玩家数。 */
export function getMaxPlayers(options?: TaskOptions): Promise<number> { return post<number>('server.getMaxPlayers', {}, options); }
export function getMaxPlayersSync(options?: TaskOptions): number { return call<number>('server.getMaxPlayers', {}, options); }
