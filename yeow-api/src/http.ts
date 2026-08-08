interface HttpResult {
  serverId?: string;
  port?: number;
  status?: number;
  body?: string;
  headers?: Record<string, string>;
  err?: string;
}

/** HTTP 响应选项（respond）。 */
export interface RespondOptions {
  /** HTTP 状态码（默认 200）。 */
  status?: number;
  /** 文本响应体（UTF-8）。 */
  body?: string;
  /** base64 编码的**二进制**响应体（与 body 互斥，优先）——如从 assets 读取的资源包等。 */
  bodyBase64?: string;
  /** 响应头。 */
  headers?: Record<string, string>;
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

export function respond(serverId: string, connId: string, opts: RespondOptions = {}): void {
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

/**
 * 异步 HTTP 请求（不阻塞 JS 线程）——底层走 `http:requestAsync` 通道。
 * 推荐用于事件处理器与高频场景。需要同步阻塞返回的用 `requestSync`。
 */
export function request(url: string, opts: Record<string, unknown> = {}): Promise<HttpResult> {
  return new Promise((resolve, reject) => {
    const cbId = _registerCallback((result: unknown) => {
      if ((result as any)?.err) reject(new Error((result as any).err));
      else resolve(result as HttpResult);
    });
    try {
      _sendHttp({ t: 'requestAsync', p: { url, ...opts, cb: String(cbId) } });
    } catch (e) { reject(e); }
  });
}

/**
 * 同步 HTTP 请求（**阻塞 JS 线程**直到响应返回）——底层走 `http:request` 通道。
 * 阻塞期间 JS 线程无法处理事件/命令/回调（可能触发 event.timeout 告警）；
 * 仅适合低频、非事件上下文。事件处理器或高频场景请用 `request`（异步）或全局 `fetch`。
 */
export function requestSync(url: string, opts: Record<string, unknown> = {}): HttpResult {
  return _sendHttp({ t: 'request', p: { url, ...opts } });
}
