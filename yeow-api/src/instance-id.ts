let _seq = 0;
const _gcQueue: string[] = [];
const _gcReg = typeof FinalizationRegistry !== 'undefined'
  ? new FinalizationRegistry<string>((raw: string) => { _gcQueue.push(raw); })
  : null;
(globalThis as any).__yeowGcQueue = _gcQueue;

export class InstanceId {
  readonly _raw: string;
  readonly _managed: boolean;

  constructor(prefix: string) {
    this._raw = prefix + '_' + (++_seq);
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

export class GUIHandle extends InstanceId { constructor() { super('gui'); } }
export class BossBarHandle extends InstanceId { constructor() { super('boss'); } }
export class InventoryHandle extends InstanceId { constructor() { super('inv'); } }
