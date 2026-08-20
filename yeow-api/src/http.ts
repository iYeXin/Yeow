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
  /**
   * 响应体：`Uint8Array` 直接作为二进制写出；字符串按 `encoding` 解释
   * （默认 UTF-8 文本；`'base64'` 时视为 base64 编码的二进制数据）。
   */
  body?: string | Uint8Array;
  /** body 为字符串时的编码：`'utf8'`（默认）或 `'base64'`（base64 编码的二进制）；Uint8Array 时忽略。 */
  encoding?: 'utf8' | 'base64';
  /** 响应头。 */
  headers?: Record<string, string>;
}

/** HTTP 请求选项（request）。 */
export interface RequestOptions {
  /** HTTP 方法（默认 `"GET"`）。 */
  method?: string;
  /** 请求头。 */
  headers?: Record<string, string>;
  /**
   * 请求体（与 `fs.writeFile` 同语义）：`Uint8Array` 直接作为二进制；
   * 字符串按 `encoding` 解释——缺省 UTF-8 文本，`'base64'` 时视为 base64 编码的二进制。
   */
  body?: string | Uint8Array;
  /**
   * 请求体（body 为字符串时）解释：`'utf8'`（默认）文本 / `'base64'` base64 二进制
   * （与 `fs.writeFile` 同语义）。
   */
  encoding?: 'utf8' | 'base64';
  /**
   * 响应体形态：缺省 `Uint8Array`（原始字节，可在收到后自行解码，
   * 如 `bytesToStringSync(body)` 或 `new TextDecoder().decode(body)`）；
   * `'utf8'` → UTF-8 字符串；`'base64'` → base64 字符串（原样返回，不解码）。
   */
  responseEncoding?: 'utf8' | 'base64';
  /** 超时（毫秒）；缺省运行时默认（连接 5s / 读取 10s）。 */
  timeout?: number;
}

/** HTTP 响应（request）。 */
export interface HttpResponse {
  /** HTTP 状态码。 */
  status: number;
  /** 响应头（键小写）。 */
  headers: Record<string, string>;
  /** 响应体：缺省 `Uint8Array`；`encoding: 'utf8' | 'base64'` 时为字符串。 */
  body: string | Uint8Array;
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
  const p: Record<string, unknown> = { serverId, connId };
  if (opts.body instanceof Uint8Array) {
    p.body = opts.body.toBase64();   // Uint8Array → 二进制（base64 承载）
    p.encoding = 'base64';
  } else {
    p.body = opts.body;
    if (opts.encoding === 'base64') p.encoding = 'base64';
  }
  if (opts.status !== undefined) p.status = opts.status;
  if (opts.headers !== undefined) p.headers = opts.headers;
  _sendHttp({ t: 'respond', p });
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
 *
 * 请求体与 `fs.writeFile` 同语义（`body: string | Uint8Array`，字符串按
 * `encoding` 解释）；响应体默认 `Uint8Array`（原始字节），可用
 * `responseEncoding` 直接得到字符串（`'utf8'` / `'base64'`），也可在收到后
 * 用 `bytesToStringSync` 自行解码。`timeout` 指定超时毫秒数（连接与读取，缺省
 * 运行时默认）。
 */
export function request(url: string, opts: RequestOptions = {}): Promise<HttpResponse> {
  return new Promise((resolve, reject) => {
    const cbId = _registerCallback((result: unknown) => {
      const r = result as HttpResult;
      if (r?.err || (r as any)?.error) {
        reject(new Error(r?.err || (r as any).error));
        return;
      }
      const body = r.body ?? '';
      resolve({
        status: r.status ?? 0,
        headers: r.headers ?? {},
        // 底层始终以 base64 承载原始字节；'utf8' 时 Java 侧已解码为文本，其余形态本地转换
        body: opts.responseEncoding === 'utf8' ? body
          : opts.responseEncoding === 'base64' ? body
            : Uint8Array.fromBase64(body),
      });
    });
    try {
      const p: Record<string, unknown> = {
        url,
        method: opts.method || 'GET',
        headers: opts.headers || {},
      };
      // 请求体与 fs.writeFile 同语义：Uint8Array 直接二进制（base64 承载）；
      // 字符串按 encoding——缺省 UTF-8 文本，'base64' 视为 base64 二进制
      if (opts.body instanceof Uint8Array) {
        p.body = opts.body.toBase64();
        p.encoding = 'base64';
      } else {
        p.body = opts.body ?? null;
        if (opts.encoding === 'base64') p.encoding = 'base64';
      }
      p.responseType = opts.responseEncoding === 'utf8' ? 'text' : 'base64';
      if (opts.timeout !== undefined) p.timeout = opts.timeout;
      _sendHttp({ t: 'requestAsync', p: { ...p, cb: String(cbId) } });
    } catch (e) { reject(e); }
  });
}
