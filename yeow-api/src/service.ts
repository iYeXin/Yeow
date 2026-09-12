export type ServiceKind = 'plugin' | 'native';

export interface ServiceRequestOptions {
  /** 请求头（app 级键值对；`content-type` 为典型项）。 */
  headers?: Record<string, string>;
  /** 便捷别名：等价于 `headers['content-type']`。 */
  contentType?: string;
  /** 请求体：JSON 值，或 `Uint8Array`（原始二进制，运行时解码后原样转发）。 */
  body?: any;
  /** 超时（毫秒）；缺省用运行时配置（默认 30000）。 */
  timeout?: number;
}

/**
 * 服务响应（fetch 风格）。响应体始终以字节承载：`json()` / `text()` 在 JS 侧解码，
 * `bytes()` / `arrayBuffer()` 直接取原始字节。`body` 为未来的可读流预留。
 */
export interface ServiceResponse {
  readonly ok: boolean;
  readonly status: number;
  readonly contentType: string;
  readonly headers: Record<string, string>;
  /** 预留：未来的可读流（当前为 undefined）。 */
  readonly body?: unknown;
  base64(): Promise<string>;
  bytes(): Promise<Uint8Array>;
  arrayBuffer(): Promise<ArrayBuffer>;
  text(): Promise<string>;
  json(): Promise<any>;
}

export interface NativeTerminateInfo {
  serviceId: string;
  reason: string;
  exitCode?: number;
  output?: string;
}

/** 服务回复：服务方 `onRequest` 返回它以携带响应头；返回普通值等价于 JSON body。 */
export class ServiceReply {
  constructor(public readonly body: any, public readonly headers?: Record<string, string>) {}
}

type NativePlatform = string | { file: string };
export type NativePlatforms = Record<string, NativePlatform>;

// ── 内部实现 ──────────────────────────────────────────────────────

function _response(r: { headers?: Record<string, string>; contentType?: string; base64?: string; status?: number }): ServiceResponse {
  const b64 = r.base64 ?? '';
  const headers: Record<string, string> = {};
  const raw = r.headers || {};
  for (const k of Object.keys(raw)) headers[k.toLowerCase()] = raw[k];
  const ct = r.contentType || headers['content-type'] || 'application/octet-stream';
  headers['content-type'] = ct;
  const decode = () => new TextDecoder().decode(Uint8Array.fromBase64(b64));
  return {
    ok: true,
    status: r.status ?? 200,
    contentType: ct,
    headers,
    base64: () => Promise.resolve(b64),
    bytes: () => Promise.resolve(Uint8Array.fromBase64(b64)),
    arrayBuffer: () => {
      const u8 = Uint8Array.fromBase64(b64);
      return Promise.resolve(u8.byteOffset === 0 && u8.byteLength === u8.buffer.byteLength ? u8.buffer : u8.slice().buffer);
    },
    text: () => Promise.resolve(decode()),
    json: () => Promise.resolve().then(() => { const t = decode(); return t === '' ? null : JSON.parse(t); }),
  };
}

function _request(serviceId: string, path: string, options: ServiceRequestOptions): Promise<ServiceResponse> {
  return new Promise((resolve, reject) => {
    const cbId = _registerCallback((result: any) => {
      if (result?.err) { reject(new Error(result.err)); return; }
      resolve(_response(result));
    });
    const headers: Record<string, string> = { ...(options.headers || {}) };
    if (options.contentType && !headers['content-type']) headers['content-type'] = options.contentType;
    const body = options.body;
    if (body instanceof Uint8Array) {
      if (!headers['content-type']) headers['content-type'] = 'application/octet-stream';
    } else if (body !== undefined && body !== null) {
      if (!headers['content-type']) headers['content-type'] = 'application/json';
    } else if (!headers['content-type']) {
      headers['content-type'] = 'application/json';
    }
    const p: Record<string, unknown> = { t: 'request', serviceId, path, requestId: cbId, headers, contentType: headers['content-type'] };
    if (options.timeout !== undefined && options.timeout !== null) p.timeout = options.timeout;
    if (body instanceof Uint8Array) {
      p.body = body.toBase64();
      p.bodyEncoding = 'base64';
    } else if (body !== undefined && body !== null) {
      p.body = body;
    }
    $send('service', p);
  });
}

function _subscribe(serviceId: string, eventPath: string, handler: (body: any, eventPath: string) => void): () => void {
  const cbId = _registerCallback((payload: any) => { handler(payload.body, payload.eventPath); }, { persistent: true });
  $send('service', { t: 'subscribe', serviceId, eventPath, cb: cbId });
  return () => {
    $send('service', { t: 'unsubscribe', serviceId, eventPath });
    _unregisterCallback(cbId);
  };
}

function _unregister(serviceId: string, token?: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const p: Record<string, unknown> = { t: 'unregister', serviceId };
    if (token) p.token = token;
    const r = $send('service', p) as any;
    if (r?.err) reject(new Error(r.err)); else resolve();
  });
}

function _awaitReady(serviceId: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const cbId = _registerCallback((result: any) => {
      if (result?.err) {
        const info = result.err;
        if (typeof info === 'string') {
          reject(new Error(info));
        } else {
          const e = new Error(info.message || 'Unknown error');
          (e as any).exitCode = info.exitCode;
          (e as any).output = info.output || '';
          reject(e);
        }
      } else resolve();
    });
    $send('service', { t: 'awaitReady', serviceId, cb: cbId });
  });
}

function _info(serviceId: string): Promise<{ exists: boolean; kind: ServiceKind | null }> {
  return new Promise((resolve, reject) => {
    const r = $send('service', { t: 'info', serviceId }) as any;
    if (r?.err) { reject(new Error(r.err)); return; }
    resolve({ exists: !!r.exists, kind: (r.kind ?? null) as ServiceKind | null });
  });
}

// ── Service 对象（OOP）────────────────────────────────────────────

/** 服务句柄基类（插件服务 / 原生服务通用能力）。 */
export abstract class Service {
  abstract readonly kind: ServiceKind;
  constructor(readonly id: string) {}

  /** 请求服务（fetch 风格）。失败 / 超时 / 服务卸载时 Promise reject。 */
  request(path: string, options: ServiceRequestOptions = {}): Promise<ServiceResponse> {
    return _request(this.id, path, options);
  }

  /** 订阅服务事件；返回取消函数。插件 unload / hot-reload 时运行时自动清理。 */
  subscribe(eventPath: string, handler: (body: any, eventPath: string) => void): () => void {
    return _subscribe(this.id, eventPath, handler);
  }

  /** 卸载服务。Plugin Service 需属主 token，Native Service 需调用方为属主。 */
  unregister(): Promise<void> {
    return _unregister(this.id);
  }
}

/** 插件服务（JS 服务）。属主句柄（registerService）持有 token；消费者句柄（getService）无 token。 */
export class PluginService extends Service {
  readonly kind = 'plugin' as const;
  readonly token?: string;
  constructor(id: string, token?: string) {
    super(id);
    this.token = token;
  }

  /** 发布事件（仅属主：需 token）。 */
  publish(eventPath: string, body?: any): void {
    if (!this.token) throw new Error('publish requires the owner token of plugin service ' + this.id);
    $send('service', { t: 'publish', token: this.token, eventPath, body: body ?? {} });
  }

  override unregister(): Promise<void> {
    if (!this.token) return Promise.reject(new Error('unregister requires the owner token of plugin service ' + this.id));
    return _unregister(this.id, this.token);
  }
}

/** 原生服务（子进程）。 */
export class NativeService extends Service {
  readonly kind = 'native' as const;
  private _terminateCb: string | null = null;
  constructor(id: string) { super(id); }

  /** 等待原生服务就绪（进程 TCP 就绪消息已收到时 resolve）。 */
  ready(): Promise<void> { return _awaitReady(this.id); }

  /** 注册服务终止钩子（仅属主有效；重复调用替换旧回调）。 */
  onTerminate(handler: (info: NativeTerminateInfo) => void): void {
    if (this._terminateCb) _unregisterCallback(this._terminateCb);
    this._terminateCb = _registerCallback((info: unknown) => handler(info as NativeTerminateInfo), { persistent: true });
    $send('service', { t: 'registerNativeTerminate', serviceId: this.id, cb: this._terminateCb });
  }
}

// ── 注册 / 获取 ───────────────────────────────────────────────────

/**
 * 注册 Plugin Service。成功返回 {@link PluginService}（属主句柄，含 token）；
 * 失败（权限 / 已存在）抛错——已存在时 `error.serviceId` 指向既有服务，应以其降级接入。
 */
export function registerService(refName: string, onRequest: (path: string, body: any) => any, isPublic = true): Promise<PluginService> {
  return new Promise((resolve, reject) => {
    const svcCbId = _registerCallback((payload: any) => {
      if (payload?._svc === 'request') {
        const result = onRequest(payload.path, payload.body ?? null);
        if (result instanceof ServiceReply) {
          $send('service', { t: 'response', requestId: payload.requestId, headers: result.headers, body: result.body });
        } else {
          $send('service', { t: 'response', requestId: payload.requestId, body: result });
        }
      }
    }, { persistent: true });
    const r = $send('service', { t: 'register', refName, onRequest: svcCbId, public: isPublic }) as any;
    if (r?.err) {
      _unregisterCallback(svcCbId);
      const e: any = new Error(r.err);
      if (r.serviceId) e.serviceId = r.serviceId;
      reject(e);
      return;
    }
    resolve(new PluginService(r.serviceId, r.token));
  });
}

/** 注册 Native Service。成功返回 {@link NativeService}；失败抛错。 */
export function registerNativeService(refName: string, platforms: NativePlatforms, isPublic = true): Promise<NativeService> {
  return new Promise((resolve, reject) => {
    const r = $send('service', { t: 'registerNative', refName, platforms, public: isPublic }) as any;
    if (r?.err) {
      const e: any = new Error(r.err);
      if (r.serviceId) e.serviceId = r.serviceId;
      reject(e);
      return;
    }
    resolve(new NativeService(r.serviceId));
  });
}

/** 按 id 获取服务句柄；不存在时抛错。返回的消费者句柄无 token（不能 publish / unregister 插件服务）。 */
export function getService(serviceId: string): Promise<Service> {
  return _info(serviceId).then(i => {
    if (!i.exists) {
      const e: any = new Error('Service not found: ' + serviceId);
      e.serviceId = serviceId;
      throw e;
    }
    return i.kind === 'native' ? new NativeService(serviceId) : new PluginService(serviceId);
  });
}

/**
 * 检查服务是否存在。
 *
 * > **不要用它判断后再注册**：检查与注册**不是原子操作**，并发下会竞态——两个插件可能同时检查到
 * > 不存在，随后一个注册成功、另一个被拒绝。注册必须用 `try-catch` 包裹，并在捕获到「已存在」时用
 * > `error.serviceId` 降级接入既有服务。此方法仅用于展示 / 诊断。
 */
export function hasService(serviceId: string): Promise<boolean> {
  return _info(serviceId).then(i => i.exists);
}
