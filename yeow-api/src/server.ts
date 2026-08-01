import { call, post } from './task.js';

export function broadcast(msg: string): Promise<void> { return post('server.broadcast', { message: msg }); }
export function broadcastSync(msg: string): void { call('server.broadcast', { message: msg }); }
export function dispatchCommand(cmd: string): Promise<boolean> { return post<boolean>('command.dispatch', { command: cmd }); }
export function dispatchCommandSync(cmd: string): boolean { return call<boolean>('command.dispatch', { command: cmd }); }
export function setMotd(motd: string): Promise<void> { return post('server.setMotd', { motd }); }
export function setMotdSync(motd: string): void { call('server.setMotd', { motd }); }
export function setIcon(base64: string): Promise<void> { return post('server.setIcon', { icon: base64 }); }
export function setIconSync(base64: string): void { call('server.setIcon', { icon: base64 }); }
export function getMotd(): Promise<string> { return post<string>('server.getMotd', {}); }
export function getMotdSync(): string { return call<string>('server.getMotd', {}); }
export function getVersion(): Promise<string> { return post<string>('server.getVersion', {}); }
export function getVersionSync(): string { return call<string>('server.getVersion', {}); }
