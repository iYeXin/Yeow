import { call, post } from './task.js';

// ES2026 引擎能力（QuickJS 2026-06-04）：TS 库类型尚未收录
declare global {
  interface Uint8ArrayConstructor {
    fromBase64(b64: string): Uint8Array;
  }
}

export interface ChunkData {
  x: number;
  z: number;
  world: string;
}

/** 模块级缓存：方块 key 列表（= Java 侧索引基准，运行时内稳定）。 */
let _blocksCache: string[] | null = null;

async function ensureBlocks(): Promise<string[]> {
  if (_blocksCache) return _blocksCache;
  const blocks = await post<string[]>('server.getBlocks', {});
  _blocksCache = blocks;
  return blocks;
}

function ensureBlocksSync(): string[] {
  if (_blocksCache) return _blocksCache;
  const blocks = call<string[]>('server.getBlocks', {});
  _blocksCache = blocks;
  return blocks;
}

/** base64(big-endian short[]) → Uint16Array。引擎无 atob，用 ES2026 `Uint8Array.fromBase64`。 */
function decodeShortArray(b64: string): Uint16Array {
  const bytes = Uint8Array.fromBase64(b64);
  const out = new Uint16Array(bytes.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = (bytes[i * 2] << 8) | bytes[i * 2 + 1];
  }
  return out;
}

/**
 * 完整区块快照（3D）：方块类型索引数组，与 `getBlocks()` 的数组下标对应。
 * 索引仅当前运行时有效（重启后可能变化），不可持久化。
 */
export class ChunkSnapshot {
  constructor(
    readonly data: Uint16Array,
    readonly minY: number,
    readonly height: number,
    readonly blocks: string[],
  ) {}

  /** 绝对高度 y 处的方块 key（越界/未知索引回退 minecraft:air）。 */
  getBlock(x: number, y: number, z: number): string {
    const ry = y - this.minY;
    if (x < 0 || x > 15 || z < 0 || z > 15 || ry < 0 || ry >= this.height) return 'minecraft:air';
    return this.blocks[this.data[(ry * 16 + z) * 16 + x]] ?? 'minecraft:air';
  }

  /** 绝对高度 y 处的原始方块索引。 */
  getBlockIndex(x: number, y: number, z: number): number {
    const ry = y - this.minY;
    if (x < 0 || x > 15 || z < 0 || z > 15 || ry < 0 || ry >= this.height) return 0;
    return this.data[(ry * 16 + z) * 16 + x];
  }

  static async fromRaw(raw: { data: string; minY: number; height: number }): Promise<ChunkSnapshot> {
    return new ChunkSnapshot(decodeShortArray(raw.data), raw.minY, raw.height, await ensureBlocks());
  }

  static fromRawSync(raw: { data: string; minY: number; height: number }): ChunkSnapshot {
    return new ChunkSnapshot(decodeShortArray(raw.data), raw.minY, raw.height, ensureBlocksSync());
  }
}

/**
 * 顶部方块快照（2D，256 元素）：每列最高非空气方块的类型索引，顺序 z 外层 → x 内层。
 * 索引仅当前运行时有效，不可持久化。
 */
export class ChunkTopSnapshot {
  constructor(
    readonly data: Uint16Array,
    readonly blocks: string[],
  ) {}

  getTop(x: number, z: number): string {
    if (x < 0 || x > 15 || z < 0 || z > 15) return 'minecraft:air';
    return this.blocks[this.data[z * 16 + x]] ?? 'minecraft:air';
  }

  getTopIndex(x: number, z: number): number {
    if (x < 0 || x > 15 || z < 0 || z > 15) return 0;
    return this.data[z * 16 + x];
  }

  static async fromRaw(raw: { data: string }): Promise<ChunkTopSnapshot> {
    return new ChunkTopSnapshot(decodeShortArray(raw.data), await ensureBlocks());
  }

  static fromRawSync(raw: { data: string }): ChunkTopSnapshot {
    return new ChunkTopSnapshot(decodeShortArray(raw.data), ensureBlocksSync());
  }
}

export class Chunk {
  constructor(
    public readonly x: number,
    public readonly z: number,
    public readonly world: string,
  ) {}

  static from(d: ChunkData): Chunk {
    return new Chunk(d.x, d.z, d.world);
  }

  /** 完整方块快照（16×16×世界高度，y 外层 → z → x）。重量级操作，适合低频/批量场景。 */
  getSnapshot(): Promise<ChunkSnapshot> {
    return post<{ data: string; minY: number; height: number }>('chunk.getSnapshot', { world: this.world, x: this.x, z: this.z }).then(ChunkSnapshot.fromRaw);
  }
  getSnapshotSync(): ChunkSnapshot {
    return ChunkSnapshot.fromRawSync(call<{ data: string; minY: number; height: number }>('chunk.getSnapshot', { world: this.world, x: this.x, z: this.z }));
  }

  /** 顶部方块快照（256 元素，每列最高非空气方块，z 外层 → x 内层）。 */
  getTopSnapshot(): Promise<ChunkTopSnapshot> {
    return post<{ data: string }>('chunk.getTopSnapshot', { world: this.world, x: this.x, z: this.z }).then(ChunkTopSnapshot.fromRaw);
  }
  getTopSnapshotSync(): ChunkTopSnapshot {
    return ChunkTopSnapshot.fromRawSync(call<{ data: string }>('chunk.getTopSnapshot', { world: this.world, x: this.x, z: this.z }));
  }
}
