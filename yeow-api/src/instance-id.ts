let _seq = 0;
// 每个插件 QuickJS 上下文独立（模块级变量彼此隔离）；随机种子保证
// 跨插件/跨 Worker 生成的 id 全局唯一——id 是不透明句柄，不携带任何业务信息。
const _seed = Math.random().toString(36).slice(2, 12);
const _gcQueue: string[] = [];
const _gcReg = typeof FinalizationRegistry !== 'undefined'
  ? new FinalizationRegistry<string>((raw: string) => { _gcQueue.push(raw); })
  : null;
(globalThis as any).__yeowGcQueue = _gcQueue;

export class InstanceId {
  readonly _raw: string;
  readonly _managed: boolean;

  constructor() {
    this._raw = _seed + '_' + (++_seq);
    this._managed = true;
    _gcReg?.register(this, this._raw);
  }

  static adopt(raw: string): InstanceId {
    const id = Object.create(InstanceId.prototype) as InstanceId;
    (id as any)._raw = raw;
    (id as any)._managed = false;
    return id;
  }

  toString(): string { return this._raw; }
}

export class GUIHandle extends InstanceId {}
export class BossBarHandle extends InstanceId {}
export class InventoryHandle extends InstanceId {}
