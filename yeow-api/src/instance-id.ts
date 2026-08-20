let _seq = 0;
// 每个插件 QuickJS 上下文独立（模块级变量彼此隔离）；随机种子保证
// 跨插件/跨 Worker 生成的 id 全局唯一——id 是不透明句柄，不携带任何业务信息。
const _seed = Math.random().toString(36).slice(2, 12);
// GC 队列必须复用运行时（init.js）创建的全局数组（读已有 / 否则创建）：
// 1) init.js 的 _flushGC 在每次消息分发后冲刷该数组并上报 gc-collect；
//    若此处新建数组覆盖全局，FinalizationRegistry 回调推入的 id 永远不会被冲刷。
// 2) 多 yeow-api 副本共存时（peer 范围不重叠、npm 安装独立副本），共享同一
//    数组可保证所有副本的句柄回收都进入同一条上报通道。
const _g = globalThis as any;
const _gcQueue: string[] = _g.__yeowGcQueue || (_g.__yeowGcQueue = []);
const _gcReg = typeof FinalizationRegistry !== 'undefined'
  ? new FinalizationRegistry<string>((raw: string) => { _gcQueue.push(raw); })
  : null;

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

export class BossBarHandle extends InstanceId {}
export class InventoryHandle extends InstanceId {}
