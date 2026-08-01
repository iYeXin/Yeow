import { post } from './task.js';

export interface ServiceResult {
  serviceId: string;
  token: string;
}

export interface NativeTerminateInfo {
  serviceId: string;
  reason: string;
  exitCode?: number;
  output?: string;
}

export interface NativeServiceResult {
  serviceId: string;
  ready: () => Promise<void>;
  onTerminate: (handler: (info: NativeTerminateInfo) => void) => void;
}

// ── Register Plugin Service ──

export async function registerService(refName: string, onRequest: (path: string, body: any) => any, isPublic = true): Promise<ServiceResult> {
  const svcCbId = _registerCallback((payload: any) => {
    if (payload?._svc === 'request') {
      let body: any = null;
      try { body = JSON.parse(payload.body); } catch { body = {}; }
      const result = onRequest(payload.path, body);
      $send('service', { t: 'response', requestId: payload.requestId, body: result });
    }
  }, { persistent: true });

  const r = $send('service', { t: 'register', refName, onRequest: svcCbId, public: isPublic }) as ServiceResult & { err?: string; serviceId?: string };
  if (r?.err) {
    _unregisterCallback(svcCbId);
    const e: any = new Error(r.err);
    if (r.serviceId) e.serviceId = r.serviceId;
    throw e;
  }
  return r;
}

// ── Register Native Service ──

type NativePlatform = string | { file: string } | { dir: string; entry: string };
type NativePlatforms = Record<string, NativePlatform>;

function _serviceReady(serviceId: string): Promise<void> {
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

export async function registerNativeService(refName: string, platforms: NativePlatforms, isPublic = true): Promise<NativeServiceResult> {
  const r = $send('service', { t: 'registerNative', refName, platforms, public: isPublic }) as { serviceId: string; err?: string };
  if (r.err) {
    const e: any = new Error(r.err);
    if ((r as any).serviceId) e.serviceId = (r as any).serviceId;
    throw e;
  }
  let terminateCb: string | null = null;
  return {
    serviceId: r.serviceId,
    ready: () => _serviceReady(r.serviceId),
    onTerminate(handler) {
      if (terminateCb) _unregisterCallback(terminateCb);
      terminateCb = _registerCallback((info: unknown) => handler(info as NativeTerminateInfo), { persistent: true });
      $send('service', { t: 'registerNativeTerminate', serviceId: r.serviceId, cb: terminateCb });
    },
  };
}

// ── Request ──

export function request(serviceId: string, path: string, body?: any): Promise<any> {
  return new Promise((resolve, reject) => {
    const cbId = _registerCallback((result: any) => {
      if (result?.err) { reject(new Error(result.err)); } else resolve(result);
    });
    $send('service', { t: 'request', serviceId, path, body: body || {}, requestId: cbId });
  });
}

// ── Subscribe ──

export function subscribe(serviceId: string, eventPath: string, handler: (body: any, eventPath: string) => void): () => void {
  const cbId = _registerCallback((payload: any) => {
    handler(payload.body, payload.eventPath);
  }, { persistent: true });
  $send('service', { t: 'subscribe', serviceId, eventPath, cb: cbId });
  return () => {
    $send('service', { t: 'unsubscribe', serviceId, eventPath });
    _unregisterCallback(cbId);
  };
}

// ── Publish ──

export function publish(token: string, eventPath: string, body?: any): void {
  $send('service', { t: 'publish', token, eventPath, body: body || {} });
}
