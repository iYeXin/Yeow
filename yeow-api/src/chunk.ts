import { call, post } from './task.js';

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

/** base64(little-endian short[]) → Uint16Array：直接视图，零拷贝零遍历。 */
function decodeShortArray(b64: string): Uint16Array {
  const bytes = Uint8Array.fromBase64(b64);
  return new Uint16Array(bytes.buffer, bytes.byteOffset, bytes.length >>> 1);
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
 * 请求时带 withHeight 可同时获得 heightMap（每列最高方块的世界高度，同布局）。
 * 索引仅当前运行时有效，不可持久化。
 */
export class ChunkTopSnapshot {
  constructor(
    readonly data: Uint16Array,
    readonly blocks: string[],
    readonly height?: Uint16Array,
  ) {}

  getTop(x: number, z: number): string {
    if (x < 0 || x > 15 || z < 0 || z > 15) return 'minecraft:air';
    return this.blocks[this.data[z * 16 + x]] ?? 'minecraft:air';
  }

  getTopIndex(x: number, z: number): number {
    if (x < 0 || x > 15 || z < 0 || z > 15) return 0;
    return this.data[z * 16 + x];
  }

  /** 列最高方块的世界高度（需 withHeight 请求；未请求时返回 null）。 */
  getTopHeight(x: number, z: number): number | null {
    if (!this.height || x < 0 || x > 15 || z < 0 || z > 15) return null;
    return this.height[z * 16 + x];
  }

  static async fromRaw(raw: { data: string; height?: string }): Promise<ChunkTopSnapshot> {
    return new ChunkTopSnapshot(
      decodeShortArray(raw.data),
      await ensureBlocks(),
      raw.height ? decodeShortArray(raw.height) : undefined,
    );
  }

  static fromRawSync(raw: { data: string; height?: string }): ChunkTopSnapshot {
    return new ChunkTopSnapshot(
      decodeShortArray(raw.data),
      ensureBlocksSync(),
      raw.height ? decodeShortArray(raw.height) : undefined,
    );
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

  /** 顶部方块快照（256 元素，每列最高非空气方块，z 外层 → x 内层）。
   *  传 withHeight=true 时同时返回 heightMap（每列最高方块的世界高度）。 */
  getTopSnapshot(withHeight?: boolean): Promise<ChunkTopSnapshot> {
    const params: Record<string, unknown> = { world: this.world, x: this.x, z: this.z };
    if (withHeight) params.withHeight = true;
    return post<{ data: string; height?: string }>('chunk.getTopSnapshot', params).then(ChunkTopSnapshot.fromRaw);
  }
  getTopSnapshotSync(withHeight?: boolean): ChunkTopSnapshot {
    const params: Record<string, unknown> = { world: this.world, x: this.x, z: this.z };
    if (withHeight) params.withHeight = true;
    return ChunkTopSnapshot.fromRawSync(call<{ data: string; height?: string }>('chunk.getTopSnapshot', params));
  }
}
