import { call, post } from './task.js';
import type { TaskOptions } from './task.js';

export function broadcast(msg: string, options?: TaskOptions): Promise<void> { return post('server.broadcast', { message: msg }, options); }
export function broadcastSync(msg: string, options?: TaskOptions): void { call('server.broadcast', { message: msg }, options); }
export function dispatchCommand(cmd: string, options?: TaskOptions): Promise<boolean> { return post<boolean>('command.dispatch', { command: cmd }, options); }
export function dispatchCommandSync(cmd: string, options?: TaskOptions): boolean { return call<boolean>('command.dispatch', { command: cmd }, options); }
export function setMotd(motd: string, options?: TaskOptions): Promise<void> { return post('server.setMotd', { motd }, options); }
export function setMotdSync(motd: string, options?: TaskOptions): void { call('server.setMotd', { motd }, options); }
export function setIcon(base64: string, options?: TaskOptions): Promise<void> { return post('server.setIcon', { icon: base64 }, options); }
export function setIconSync(base64: string, options?: TaskOptions): void { call('server.setIcon', { icon: base64 }, options); }
export function getMotd(options?: TaskOptions): Promise<string> { return post<string>('server.getMotd', {}, options); }
export function getMotdSync(options?: TaskOptions): string { return call<string>('server.getMotd', {}, options); }
export function getVersion(options?: TaskOptions): Promise<string> { return post<string>('server.getVersion', {}, options); }
export function getVersionSync(options?: TaskOptions): string { return call<string>('server.getVersion', {}, options); }
