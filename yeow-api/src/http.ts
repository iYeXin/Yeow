interface HttpResult {
  serverId?: string;
  port?: number;
  status?: number;
  body?: string;
  headers?: Record<string, string>;
  err?: string;
}

function _sendHttp(payload: Record<string, unknown>): HttpResult {
  const r = $send('http', payload);
  if (!r) return {};
  if ((r as any).err) throw new Error((r as any).err);
  return r as HttpResult;
}

const _servers = new Set<string>();

export function listen(callback: (req: Record<string, unknown>) => void, port?: number): HttpResult {
  const cbId = _registerCallback((payload: unknown) => {
    callback(payload as Record<string, unknown>);
  }, { persistent: true });
  const result = _sendHttp({
    t: 'listen', p: {
      pluginName: __plugin?.name || 'unknown',
      callbackId: String(cbId),
      port: port || 0,
    },
  });
  if (result.serverId) _servers.add(result.serverId);
  return result;
}

export function respond(serverId: string, connId: string, opts: Record<string, unknown> = {}): void {
  _sendHttp({ t: 'respond', p: { serverId, connId, ...opts } });
}

export function close(serverId: string): void {
  _servers.delete(serverId);
  _sendHttp({ t: 'close', p: { serverId } });
}

// Auto-close servers on shutdown
_registerCallback(() => {
  for (const id of _servers) {
    try { _sendHttp({ t: 'close', p: { serverId: id } }); } catch { /* ignore */ }
  }
  _servers.clear();
}, { persistent: true });

export function request(url: string, opts: Record<string, unknown> = {}): Promise<HttpResult> {
  return new Promise((resolve, reject) => {
    try {
      const result = _sendHttp({ t: 'request', p: { url, ...opts } });
      resolve(result);
    } catch (e) { reject(e); }
  });
}
