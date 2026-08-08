export interface WorkerOptions {
  /** Worker 名（必填；不允许 'main'；同一主插件内不重复，全局可重复）。 */
  name: string;
  /** 资源路径（通过 `getAssetsPath` 获取）；与 `code` 互斥（不可同时传递）。 */
  entry?: string;
  /** 代码字符串；与 `entry` 互斥。 */
  code?: string;
}

let _seq = 0;
/** 本主插件已创建的 worker 名（同主插件内唯一校验；unload 后允许重建）。 */
const _created: Record<string, boolean> = {};
/** 主插件侧：每个 worker 的 onMessage 回调 id。 */
const _msgCbs: Record<string, string> = {};

function _sendWorker(payload: Record<string, unknown>): unknown {
  const r = $send('worker', payload);
  if (r == null) return undefined;
  if ((r as any)?.err) throw new Error((r as any).err);
  return r;
}

/** 内部：异步调用 worker 通道（cb 回调）。 */
function _sendWorkerAsync(t: string, p: Record<string, unknown>): Promise<void> {
  return new Promise((resolve, reject) => {
    const cbId = _registerCallback((result: unknown) => {
      if ((result as any)?.err) reject(new Error((result as any).err));
      else resolve();
    });
    _sendWorker({ t, p: { ...p, cb: cbId } });
  });
}

/**
 * Worker —— 虚拟插件（独立 QuickJS 上下文 + 线程）。
 *
 * - 事件/命令/服务以独立实体注册；调度器任务独立统计
 * - 共享主插件的**数据目录**与**权限**
 * - 不能创建新的 Worker（嵌套被拒绝）
 * - **创建后无法销毁，只能卸载**（卸载物理销毁 JS 上下文，句柄保留——可重新 load）
 * - 主插件卸载时连带卸载；/yeow 管理命令不覆盖 Worker；profiler 会统计（标记 created by 主插件）
 * - Worker 的 JS 错误与主插件同样回传（dev 模式经 source-map 定位）
 */
export class Worker {
  private readonly name: string;
  private readonly entry?: string;
  private readonly code?: string;
  private readonly key: string;
  private _loaded = false;
  private _onMessage: ((msg: any) => void) | null = null;
  /** 内部 workerId（主插件 JS 侧分配；跨主插件可重复）。 */
  readonly id: string;

  constructor(name: string, entry: string | undefined, code: string | undefined) {
    this.name = name;
    this.entry = entry;
    this.code = code;
    this.id = 'worker_' + (++_seq);
    this.key = (__plugin?.name || 'unknown') + ':' + name;
    // 主插件侧 onMessage 回调（worker → main 时 Java 投递到这里）
    _msgCbs[this.id] = _registerCallback((msg: unknown) => {
      const cb = this._onMessage;
      if (cb) { try { cb(msg); } catch (e) { console.error('[worker] onMessage error', e); } }
    }, { persistent: true });
  }

  /** 注册接收 Worker 发来的消息。 */
  onMessage(cb: (msg: any) => void): void {
    this._onMessage = cb;
  }

  /** 启动 Worker：执行 init.js → worker-inject.js → Worker 代码 → INIT → LOAD（已加载为 no-op）。 */
  load(): Promise<void> {
    if (this._loaded) return Promise.resolve();
    this._loaded = true;
    return _sendWorkerAsync('load', { name: this.name });
  }

  /** 卸载 Worker（物理销毁 JS 上下文并清理其事件/命令/服务/任务；句柄保留，可重新 load）。 */
  unload(): Promise<void> {
    this._loaded = false;
    return _sendWorkerAsync('unload', { name: this.name });
  }

  /** 向 Worker 发送消息（其 onMessage 回调接收；未 load 时抛错）。 */
  postMessage(msg: Record<string, unknown>): Promise<void> {
    return _sendWorkerAsync('post', { name: this.name, msg });
  }

  /** 重载 Worker 代码（需已 load；旧上下文销毁、新代码重新加载）。 */
  reload(): Promise<void> {
    const p: Record<string, unknown> = { name: this.name };
    if (this.entry) p.entry = this.entry;
    else p.code = this.code;
    return _sendWorkerAsync('reload', p);
  }
}

/**
 * 创建 Worker（虚拟插件）：**仅注册到注册表并返回句柄**——`worker.load()` 才真正启动
 * （执行 init.js → worker-inject.js → Worker 代码 → INIT → LOAD）。
 *
 * `entry`（资源路径，经 getAssetsPath）与 `code` 二选一，同时传递抛错；
 * `name` 必填、非 'main'、同主插件内不重复。
 */
export function createWorker(options: WorkerOptions): Worker {
  const name = options?.name;
  if (!name || typeof name !== 'string' || name.trim() === '') {
    throw new Error('createWorker: name is required');
  }
  if (name === 'main') {
    throw new Error('createWorker: name must not be "main"');
  }
  if (options.entry && options.code) {
    throw new Error('createWorker: entry and code cannot be passed together');
  }
  if (!options.entry && !options.code) {
    throw new Error('createWorker: either entry or code is required');
  }
  const key = (__plugin?.name || 'unknown') + ':' + name;
  if (_created[key]) {
    throw new Error('createWorker: duplicate worker name "' + name + '" in plugin ' + (__plugin?.name || 'unknown'));
  }
  _created[key] = true;
  const w = new Worker(name, options.entry, options.code);
  // 注册到运行时注册表（同步；重复/非法名抛错）
  const p: Record<string, unknown> = { name, msgCb: _msgCbs[w.id] };
  if (options.entry) p.entry = options.entry;
  else p.code = options.code;
  _sendWorker({ t: 'create', p });
  return w;
}

// ── Worker 侧 API（仅 Worker 环境可用）──────────────────────────────

const _isWorker = typeof (globalThis as any).__workerId !== 'undefined';

/** Worker 侧：注册消息接收回调（主插件 `worker.postMessage` 触发）。主插件环境调用抛错。 */
export function onMessage(cb: (msg: any) => void): void {
  if (!_isWorker) throw new Error('onMessage is only available inside a Worker');
  (globalThis as any)._workerOnMessage = (msg: any) => {
    try { cb(msg); } catch (e) { reportError(e); }
  };
}

/** Worker 侧：向主插件发送消息（主插件侧 `worker.onMessage` 回调接收）。主插件环境调用抛错。 */
export function postMessage(msg: Record<string, unknown>): void {
  if (!_isWorker) throw new Error('postMessage is only available inside a Worker');
  _sendWorker({ t: 'postToMain', p: { msg } });
}
