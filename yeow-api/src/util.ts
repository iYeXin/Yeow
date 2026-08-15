// util 通道：gzip 压缩/解压（一次性 + 流式分块）+ UTF-8 ↔ 字节转换。
//
// 协议层字节数据以 base64 字符串承载（引擎原生 Uint8Array.toBase64()/fromBase64()
// 负责转换）——本模块输入输出一律 Uint8Array / string，**不暴露 base64**。
// 流式 API：背压基于**显式响应**——每个操作 await 结果后才发起下一块；
// 块大小由调用方决定（建议 ≥256 KiB，摊销跨线程往返开销）。

/** util 通道同步调用：err → 抛 Error。 */
function send<T>(t: string, p: Record<string, unknown>): T {
  const r = $send('util', { t, p }) as T | { err?: string } | null;
  if (r == null) return undefined as T;
  if ((r as { err?: string }).err) throw new Error((r as { err?: string }).err);
  return r as T;
}

/** util 通道异步调用（ioExecutor 上执行，不阻塞 JS 线程）。 */
function sendAsync<T>(t: string, p: Record<string, unknown>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const cbId = _registerCallback((r: T | { err?: string }) => {
      if ((r as { err?: string })?.err) reject(new Error((r as { err?: string }).err));
      else resolve(r as T);
    });
    $send('util', { t, p, cb: cbId });
  });
}

/** 输入规范化：string 视为 UTF-8 文本（经 encode.utf8 字节化）；Uint8Array 直接转换。 */
function toB64(data: Uint8Array | string): string {
  return typeof data === 'string'
    ? send<{ data: string }>('encode.utf8', { data }).data
    : data.toBase64();
}

// ── 字符串 ↔ 字节（UTF-8）────────────────────────────────────────

/** UTF-8 字符串 → 字节（同步）。 */
export function stringToBytes(text: string): Uint8Array {
  return Uint8Array.fromBase64(send<{ data: string }>('encode.utf8', { data: text }).data);
}

/** 字节 → UTF-8 字符串（同步；非法序列替换为 U+FFFD，不抛错）。 */
export function bytesToString(bytes: Uint8Array): string {
  return send<{ data: string }>('decode.utf8', { data: bytes.toBase64() }).data;
}

/** UTF-8 字符串 → 字节（异步，ioExecutor 执行）。 */
export function stringToBytesAsync(text: string): Promise<Uint8Array> {
  return sendAsync<{ data: string }>('encode.utf8', { data: text }).then((r) => Uint8Array.fromBase64(r.data));
}

/** 字节 → UTF-8 字符串（异步）。 */
export function bytesToStringAsync(bytes: Uint8Array): Promise<string> {
  return sendAsync<{ data: string }>('decode.utf8', { data: bytes.toBase64() }).then((r) => r.data);
}

// ── Gzip 命名空间（一次性 + 流式）────────────────────────────────

/** 分块压缩器：write(chunk) → 该块的压缩输出（可能为空）；finish() → 剩余输出（含 GZIP 尾）。 */
export interface GzipCompressor {
  /** 压缩一块输入，返回输出块（可能为空）。 */
  write(chunk: Uint8Array | string): Promise<Uint8Array>;
  /** 结束压缩，返回剩余输出（含 GZIP 尾）；此后 write 不可再调用。 */
  finish(): Promise<Uint8Array>;
  close(): Promise<void>;
}

/** 分块解压器：write(chunk) → 该块可解出的输出（可能为空）；finish() → 剩余输出。 */
export interface GzipDecompressor {
  /** 喂入一块压缩数据，返回解压输出块（可能为空）。 */
  write(chunk: Uint8Array | string): Promise<Uint8Array>;
  /** 标记输入结束，返回剩余解压输出（数据不完整会 reject）。 */
  finish(): Promise<Uint8Array>;
  close(): Promise<void>;
}

/** 压缩选项：level 0-9（默认引擎默认级别）；raw = 原始 deflate（无 GZIP 头/尾/CRC）。 */
export interface GzipCompressOptions {
  level?: number;
  raw?: boolean;
}

/** 解压选项：raw = 原始 deflate（无 GZIP 头/尾/CRC 校验）。 */
export interface GzipDecompressOptions {
  raw?: boolean;
}

/** 兼容旧式数字 level 参数：Gzip.compress(data, 6) 仍可用。 */
type LevelArg = number | GzipCompressOptions | undefined;

function normalizeOptions(o?: LevelArg): GzipCompressOptions {
  return typeof o === 'number' ? { level: o } : (o ?? {});
}

export const Gzip = {
  /** 一次性压缩（level 0-9；raw=true 输出原始 deflate 流）。输入 string 视为 UTF-8 文本。 */
  compress(data: Uint8Array | string, options?: LevelArg): Promise<Uint8Array> {
    const o = normalizeOptions(options);
    return sendAsync<{ data: string }>('gzip.compress', { data: toB64(data), level: o.level, raw: o.raw })
      .then((r) => Uint8Array.fromBase64(r.data));
  },
  /** 一次性压缩（同步版）。 */
  compressSync(data: Uint8Array | string, options?: LevelArg): Uint8Array {
    const o = normalizeOptions(options);
    return Uint8Array.fromBase64(send<{ data: string }>('gzip.compress', { data: toB64(data), level: o.level, raw: o.raw }).data);
  },
  /** 一次性解压（输出上限 256 MiB，超限报错——防压缩炸弹；上限可在 config.yml util 段调整）。 */
  decompress(data: Uint8Array | string, options?: GzipDecompressOptions): Promise<Uint8Array> {
    return sendAsync<{ data: string }>('gzip.decompress', { data: toB64(data), raw: options?.raw })
      .then((r) => Uint8Array.fromBase64(r.data));
  },
  /** 一次性解压（同步版）。 */
  decompressSync(data: Uint8Array | string, options?: GzipDecompressOptions): Uint8Array {
    return Uint8Array.fromBase64(send<{ data: string }>('gzip.decompress', { data: toB64(data), raw: options?.raw }).data);
  },
  /** 创建分块压缩器（流式管道）：create → write×n → finish → close。 */
  async createCompressor(options?: GzipCompressOptions): Promise<GzipCompressor> {
    const r = await sendAsync<{ id: string }>('gzip.compressor.create', { level: options?.level, raw: options?.raw });
    let closed = false;
    const check = () => { if (closed) throw new Error('compressor closed'); };
    return {
      write: (chunk) => {
        check();
        return sendAsync<{ data: string }>('gzip.compressor.write', { id: r.id, data: toB64(chunk) })
          .then((x) => Uint8Array.fromBase64(x.data));
      },
      finish: () => {
        check();
        return sendAsync<{ data: string }>('gzip.compressor.finish', { id: r.id })
          .then((x) => Uint8Array.fromBase64(x.data));
      },
      close: async () => {
        if (closed) return;
        closed = true;
        await sendAsync('gzip.compressor.close', { id: r.id });
      },
    };
  },
  /** 创建分块解压器（流式管道）：create → write×n → finish → close。 */
  async createDecompressor(options?: GzipDecompressOptions): Promise<GzipDecompressor> {
    const r = await sendAsync<{ id: string }>('gzip.decompressor.create', { raw: options?.raw });
    let closed = false;
    const check = () => { if (closed) throw new Error('decompressor closed'); };
    return {
      write: (chunk) => {
        check();
        return sendAsync<{ data: string }>('gzip.decompressor.write', { id: r.id, data: toB64(chunk) })
          .then((x) => Uint8Array.fromBase64(x.data));
      },
      finish: () => {
        check();
        return sendAsync<{ data: string }>('gzip.decompressor.finish', { id: r.id })
          .then((x) => Uint8Array.fromBase64(x.data));
      },
      close: async () => {
        if (closed) return;
        closed = true;
        await sendAsync('gzip.decompressor.close', { id: r.id });
      },
    };
  },
};

