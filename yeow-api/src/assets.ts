import type { FsEncoding, ReadFileOptions } from './fs.js';

function _sendAssets(payload: Record<string, unknown>): unknown {
  const r = $send('assets', payload);
  if (r == null) return undefined;
  if ((r as any)?.err) throw new Error((r as any).err);
  return r;
}

function _sendAssetsAsync(payload: Record<string, unknown>): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const cbId = _registerCallback((result: unknown) => {
      if ((result as any)?.err) reject(new Error((result as any).err));
      else resolve(result);
    });
    $send('assets', { ...payload, cb: cbId });
  });
}

/** 归一化 encoding 参数（与 fs 一致：缺省 = Uint8Array；utf8/base64 → 字符串）。 */
function _encoding(options?: FsEncoding | ReadFileOptions): FsEncoding | undefined {
  if (options == null) return undefined;
  const e = typeof options === 'string' ? options : options.encoding;
  if (e != null && e !== 'utf8' && e !== 'base64') {
    throw new Error('Unsupported encoding: ' + String(e));
  }
  return e;
}

// 与 fs.readFile 相同语义：默认返回 Uint8Array；显式 utf8/base64 返回字符串。
type AssetsReadFn = {
  (path: string): Promise<Uint8Array>;
  (path: string, options: FsEncoding | (ReadFileOptions & { encoding: FsEncoding })): Promise<string>;
  (path: string, options: ReadFileOptions): Promise<Uint8Array | string>;
};
type AssetsReadSyncFn = {
  (path: string): Uint8Array;
  (path: string, options: FsEncoding | (ReadFileOptions & { encoding: FsEncoding })): string;
  (path: string, options: ReadFileOptions): Uint8Array | string;
};

const readImpl = async (path: string, options?: FsEncoding | ReadFileOptions): Promise<Uint8Array | string> => {
  const enc = _encoding(options);
  const r = await _sendAssetsAsync({ t: enc === 'utf8' ? 'read' : 'readBase64', p: { path } }) as { data: string };
  if (enc === 'utf8' || enc === 'base64') return r.data;
  return Uint8Array.fromBase64(r.data);
};
const readSyncImpl = (path: string, options?: FsEncoding | ReadFileOptions): Uint8Array | string => {
  const enc = _encoding(options);
  const r = _sendAssets({ t: enc === 'utf8' ? 'read' : 'readBase64', p: { path } }) as { data: string };
  if (enc === 'utf8' || enc === 'base64') return r.data;
  return Uint8Array.fromBase64(r.data);
};

export const read = readImpl as AssetsReadFn;
export const readSync = readSyncImpl as AssetsReadSyncFn;

/**
 * 解压单文件资产到磁盘。**`dest` 必填**，基于插件数据目录（`plugins/<插件名>/`）计算，
 * 最终目标必须位于插件目录内。返回解压后的相对服务器根目录路径。
 */
export async function extract(path: string, dest: string): Promise<string> {
  const r = await _sendAssetsAsync({ t: 'extract', p: { path, dest } }) as { path: string };
  return r.path;
}
export function extractSync(path: string, dest: string): string {
  return (_sendAssets({ t: 'extract', p: { path, dest } }) as { path: string }).path;
}

/** 解压目录树到磁盘。`dest` 可选（默认 `assets/<path>`），基于插件数据目录计算并限定其内。 */
export async function extractDir(path: string, dest?: string): Promise<string> {
  const p: Record<string, unknown> = { path };
  if (dest) p.dest = dest;
  const r = await _sendAssetsAsync({ t: 'extractDir', p }) as { path: string };
  return r.path;
}
export function extractDirSync(path: string, dest?: string): string {
  const p: Record<string, unknown> = { path };
  if (dest) p.dest = dest;
  return (_sendAssets({ t: 'extractDir', p }) as { path: string }).path;
}

export const assets = { read, readSync, extract, extractSync, extractDir, extractDirSync };
