// util 通道：gzip 压缩/解压 + UTF-8 ↔ 字节转换。
//
// 协议层字节数据以 base64 字符串承载（引擎原生 Uint8Array.toBase64()/fromBase64()
// 负责转换）——本模块输入输出一律 Uint8Array / string，**不暴露 base64**。
// encode/decode 的语义是 buffer ↔ 字符串；无流式接口（一次性整体处理）。

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

// ── gzip ──────────────────────────────────────────────────────────

/** gzip 压缩（level 0-9，默认引擎默认级别）。输入 string 视为 UTF-8 文本。 */
export function gzipCompress(data: Uint8Array | string, level?: number): Promise<Uint8Array> {
  return sendAsync<{ data: string }>('gzip.compress', { data: toB64(data), level })
    .then((r) => Uint8Array.fromBase64(r.data));
}

/** gzip 压缩（同步版）。 */
export function gzipCompressSync(data: Uint8Array | string, level?: number): Uint8Array {
  return Uint8Array.fromBase64(send<{ data: string }>('gzip.compress', { data: toB64(data), level }).data);
}

/** gzip 解压（输出上限 256 MiB，超限报错——防压缩炸弹）。 */
export function gzipDecompress(data: Uint8Array | string): Promise<Uint8Array> {
  return sendAsync<{ data: string }>('gzip.decompress', { data: toB64(data) })
    .then((r) => Uint8Array.fromBase64(r.data));
}

/** gzip 解压（同步版）。 */
export function gzipDecompressSync(data: Uint8Array | string): Uint8Array {
  return Uint8Array.fromBase64(send<{ data: string }>('gzip.decompress', { data: toB64(data) }).data);
}
