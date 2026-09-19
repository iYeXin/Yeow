// 离线假玩家客户端（node-minecraft-protocol）。连接本地 Paper（online-mode=false）。
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const mc = require('minecraft-protocol');

export function protocolSupports(version) {
  return mc.supportedVersions.includes(version);
}

/**
 * 连接一个离线假玩家。
 * @param {{host?: string, port: number, username: string, version?: string}} opts
 * @returns {{client: any, username: string, joined: Promise<boolean>, state: {joined: boolean, error: Error|null, ended: boolean}, disconnect: () => void}}
 */
export function connectFake({ host = '127.0.0.1', port, username, version = '1.21.4' }) {
  if (!protocolSupports(version)) {
    throw new Error(`minecraft-protocol ${mc.version ?? ''} does not support ${version}`);
  }
  const state = { joined: false, error: null, ended: false };
  const client = mc.createClient({
    host,
    port,
    username,
    auth: 'offline',
    version,
    disableChatSigning: true,
  });

  const joined = new Promise((resolve) => {
    client.on('playerJoin', () => {
      state.joined = true;
      resolve(true);
    });
  });
  client.on('error', (e) => { state.error = e; });
  client.on('end', () => { state.ended = true; });

  return {
    client,
    username,
    joined,
    state,
    disconnect: () => { try { client.end('bye'); } catch { /* already closed */ } },
  };
}
