type FsLevel = 'plugin' | 'server' | 'outer';

import { stringToBytesSync, bytesToStringSync } from './util.js';

/** 文件编码：utf8 = 文本；base64 = 将字符串视为 Base64 编码的二进制数据。 */
export type FsEncoding = 'utf8' | 'base64';

/** readFile 选项（Node 风格：缺省 encoding = 返回 Uint8Array）。 */
export interface ReadFileOptions {
  encoding?: FsEncoding;
}

/** writeFile / appendFile 选项：字符串默认 utf8；可指定 base64 将字符串视为二进制。 */
export interface WriteFileOptions {
  encoding?: FsEncoding;
}

export type FsData = string | Uint8Array;

function _sendFs(payload: Record<string, unknown>): unknown {
  const r = $send('fs', payload);
  if (r == null) return undefined;
  if ((r as any)?.err) throw new Error((r as any).err);
  return r;
}

function _sendFsAsync(payload: Record<string, unknown>): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const cbId = _registerCallback((result: unknown) => {
      if ((result as any)?.err) reject(new Error((result as any).err));
      else resolve(result);
    });
    $send('fs', { ...payload, cb: cbId });
  });
}

/** 归一化 encoding 参数（string | { encoding }）；未知编码抛错。 */
function _encoding(options?: FsEncoding | { encoding?: FsEncoding }): FsEncoding | undefined {
  if (options == null) return undefined;
  const e = typeof options === 'string' ? options : options.encoding;
  if (e != null && e !== 'utf8' && e !== 'base64') {
    throw new Error('Unsupported encoding: ' + String(e));
  }
  return e;
}

/** UTF-8 流式解码器：跨块保留不完整的多字节序列，EOF 时冲刷（非法序列替换为 U+FFFD）。 */
function _makeUtf8StreamDecoder() {
  let pending: Uint8Array = new Uint8Array(0);

  const seqLen = (b: number) => {
    if (b < 0x80) return 1;
    if ((b & 0xE0) === 0xC0) return 2;
    if ((b & 0xF0) === 0xE0) return 3;
    if ((b & 0xF8) === 0xF0) return 4;
    return 1; // 非法起始字节：交给 bytesToStringSync 以替换字符处理
  };

  return {
    push(chunk: Uint8Array): string | null {
      const buf = pending.length
        ? (() => { const b = new Uint8Array(pending.length + chunk.length); b.set(pending, 0); b.set(chunk, pending.length); return b; })()
        : chunk;
      if (!buf.length) return null;

      let cut = buf.length;
      const last = buf.length - 1;
      if ((buf[last] & 0x80) !== 0) {
        // 回溯找到可能的多字节序列起点（最多 3 个续字节）
        let start = last;
        let cont = 0;
        while (cont < 3 && start > 0 && (buf[start] & 0xC0) === 0x80) { start--; cont++; }
        const need = seqLen(buf[start]);
        const have = buf.length - start;
        if (need > 1 && have < need && need <= 4) {
          // 仅当确实是合法起始字节时视为“序列未结束”，留给下一块
          const validLead =
            (need === 2 && (buf[start] & 0xE0) === 0xC0) ||
            (need === 3 && (buf[start] & 0xF0) === 0xE0) ||
            (need === 4 && (buf[start] & 0xF8) === 0xF0);
          if (validLead) cut = start;
        }
      }

      pending = cut === buf.length ? new Uint8Array(0) : buf.slice(cut);
      const complete = cut === buf.length ? buf : buf.subarray(0, cut);
      return complete.length ? bytesToStringSync(complete) : null;
    },
    /** EOF：把剩余的不完整序列交给 Java 解码（通常产出 U+FFFD）。 */
    flush(): string | null {
      if (!pending.length) return null;
      const s = bytesToStringSync(pending);
      pending = new Uint8Array(0);
      return s;
    },
  };
}

/** 按 fs 级别（plugin/server/outer）生成全套文件操作。 */
function _makeFs(level: FsLevel) {
  const t = (op: string) => `${level}.${op}`;

  // ── 读文件：默认 Uint8Array；encoding 指定后返回字符串 ──────────
  type ReadFileFn = {
    (path: string): Promise<Uint8Array>;
    (path: string, options: FsEncoding | (ReadFileOptions & { encoding: FsEncoding })): Promise<string>;
    (path: string, options: ReadFileOptions): Promise<Uint8Array | string>;
  };
  type ReadFileSyncFn = {
    (path: string): Uint8Array;
    (path: string, options: FsEncoding | (ReadFileOptions & { encoding: FsEncoding })): string;
    (path: string, options: ReadFileOptions): Uint8Array | string;
  };

  const readFileImpl = async (path: string, options?: FsEncoding | ReadFileOptions): Promise<Uint8Array | string> => {
    const enc = _encoding(options);
    const r = await _sendFsAsync({ t: t(enc === 'utf8' ? 'readFile' : 'readBase64'), p: { path } }) as { data: string };
    if (enc === 'utf8' || enc === 'base64') return r.data;
    return Uint8Array.fromBase64(r.data);
  };
  const readFileSyncImpl = (path: string, options?: FsEncoding | ReadFileOptions): Uint8Array | string => {
    const enc = _encoding(options);
    const r = _sendFs({ t: t(enc === 'utf8' ? 'readFile' : 'readBase64'), p: { path } }) as { data: string };
    if (enc === 'utf8' || enc === 'base64') return r.data;
    return Uint8Array.fromBase64(r.data);
  };
  const readFile = readFileImpl as ReadFileFn;
  const readFileSync = readFileSyncImpl as ReadFileSyncFn;

  // ── 写文件：string 默认 utf8（可指定 base64），Uint8Array 写原始字节 ──
  const writeFile = async (path: string, data: FsData, options?: FsEncoding | WriteFileOptions): Promise<void> => {
    const enc = _encoding(options);
    if (typeof data === 'string') {
      if (enc === 'base64') await _sendFsAsync({ t: t('writeBase64'), p: { path, data } });
      else await _sendFsAsync({ t: t('writeFile'), p: { path, data } });
    } else {
      await _sendFsAsync({ t: t('writeBase64'), p: { path, data: data.toBase64() } });
    }
  };
  const writeFileSync = (path: string, data: FsData, options?: FsEncoding | WriteFileOptions): void => {
    const enc = _encoding(options);
    if (typeof data === 'string') {
      if (enc === 'base64') _sendFs({ t: t('writeBase64'), p: { path, data } });
      else _sendFs({ t: t('writeFile'), p: { path, data } });
    } else {
      _sendFs({ t: t('writeBase64'), p: { path, data: data.toBase64() } });
    }
  };

  // ── 追加：与 writeFile 相同的数据/编码语义 ────────────────────────
  const appendFile = async (path: string, data: FsData, options?: FsEncoding | WriteFileOptions): Promise<void> => {
    const enc = _encoding(options);
    if (typeof data === 'string') {
      if (enc === 'base64') await _sendFsAsync({ t: t('appendBase64'), p: { path, data } });
      else await _sendFsAsync({ t: t('appendFile'), p: { path, data } });
    } else {
      await _sendFsAsync({ t: t('appendBase64'), p: { path, data: data.toBase64() } });
    }
  };
  const appendFileSync = (path: string, data: FsData, options?: FsEncoding | WriteFileOptions): void => {
    const enc = _encoding(options);
    if (typeof data === 'string') {
      if (enc === 'base64') _sendFs({ t: t('appendBase64'), p: { path, data } });
      else _sendFs({ t: t('appendFile'), p: { path, data } });
    } else {
      _sendFs({ t: t('appendBase64'), p: { path, data: data.toBase64() } });
    }
  };

  async function exists(path: string): Promise<boolean> {
    const r = await _sendFsAsync({ t: t('exists'), p: { path } });
    return r === true || String(r) === 'true';
  }
  function existsSync(path: string): boolean {
    const r = _sendFs({ t: t('exists'), p: { path } });
    return r === true || String(r) === 'true';
  }

  async function stat(path: string): Promise<FileStat> {
    return await _sendFsAsync({ t: t('stat'), p: { path } }) as FileStat;
  }
  function statSync(path: string): FileStat {
    return _sendFs({ t: t('stat'), p: { path } }) as FileStat;
  }

  async function isDirectory(path: string): Promise<boolean> {
    const r = await _sendFsAsync({ t: t('isDirectory'), p: { path } });
    return r === true || String(r) === 'true';
  }
  function isDirectorySync(path: string): boolean {
    const r = _sendFs({ t: t('isDirectory'), p: { path } });
    return r === true || String(r) === 'true';
  }

  async function deleteFile(path: string): Promise<boolean> {
    const r = await _sendFsAsync({ t: t('delete'), p: { path } });
    return r === true || String(r) === 'true';
  }
  function deleteFileSync(path: string): boolean {
    const r = _sendFs({ t: t('delete'), p: { path } });
    return r === true || String(r) === 'true';
  }

  async function mkdir(path: string): Promise<void> {
    await _sendFsAsync({ t: t('mkdir'), p: { path } });
  }
  function mkdirSync(path: string): void {
    _sendFs({ t: t('mkdir'), p: { path } });
  }

  async function list(path: string): Promise<string[]> {
    return await _sendFsAsync({ t: t('list'), p: { path } }) as string[];
  }
  function listSync(path: string): string[] {
    return _sendFs({ t: t('list'), p: { path } }) as string[];
  }

  async function systemPaths(): Promise<{ home: string; desktop: string; temp: string }> {
    return await _sendFsAsync({ t: t('systemPaths') }) as { home: string; desktop: string; temp: string };
  }
  function systemPathsSync(): { home: string; desktop: string; temp: string } {
    return _sendFs({ t: t('systemPaths') }) as { home: string; desktop: string; temp: string };
  }

  /** 服务器根目录（Java 进程工作目录）的绝对路径。 */
  async function getServerPath(): Promise<string> {
    return (await _sendFsAsync({ t: t('getServerPath') }) as { path: string }).path;
  }
  function getServerPathSync(): string {
    return (_sendFs({ t: t('getServerPath') }) as { path: string }).path;
  }

  // ── 读流：encoding 在创建时固定（默认 Uint8Array；utf8/base64 → string）──
  type CreateReadStreamFn = {
    (path: string): Promise<ReadStream<Uint8Array>>;
    (path: string, options: ReadStreamOptions & { encoding?: undefined }): Promise<ReadStream<Uint8Array>>;
    (path: string, options: ReadStreamOptions & { encoding: FsEncoding }): Promise<ReadStream<string>>;
    (path: string, options: ReadStreamOptions): Promise<ReadStream<Uint8Array> | ReadStream<string>>;
  };

  const createReadStreamImpl = async (path: string, options?: ReadStreamOptions): Promise<ReadStream<Uint8Array> | ReadStream<string>> => {
    const enc = _encoding(options);
    const r = await _sendFsAsync({ t: t('openRead'), p: { path, start: options?.start, end: options?.end } }) as { id: string };
    if (enc === 'base64') {
      return _makeReadStream((op, p2) => _sendFsAsync({ t: t(op), p: p2 }), r.id, {
        push: (b64: string) => b64,
        flush: () => null,
      });
    }
    if (enc === 'utf8') {
      const dec = _makeUtf8StreamDecoder();
      return _makeReadStream((op, p2) => _sendFsAsync({ t: t(op), p: p2 }), r.id, {
        push: (b64: string) => dec.push(Uint8Array.fromBase64(b64)),
        flush: () => dec.flush(),
      });
    }
    return _makeReadStream((op, p2) => _sendFsAsync({ t: t(op), p: p2 }), r.id, {
      push: (b64: string) => Uint8Array.fromBase64(b64),
      flush: () => null,
    });
  };
  const createReadStream = createReadStreamImpl as CreateReadStreamFn;

  // ── 写流：与 writeFile 相同的数据/编码语义（encoding 创建时固定）──
  async function createWriteStream(path: string, options?: WriteStreamOptions): Promise<WriteStream> {
    const enc = _encoding(options);
    const r = await _sendFsAsync({ t: t('openWrite'), p: { path, flags: options?.flags } }) as { id: string };
    return _makeWriteStream((op, p2) => _sendFsAsync({ t: t(op), p: p2 }), r.id, enc);
  }

  return {
    readFile, readFileSync,
    writeFile, writeFileSync,
    appendFile, appendFileSync,
    exists, existsSync, stat, statSync,
    isDirectory, isDirectorySync,
    deleteFile, deleteFileSync, mkdir, mkdirSync, list, listSync,
    createReadStream, createWriteStream,
    // outer 专属能力（systemPaths / getServerPath）
    ...(level === 'outer' ? { systemPaths, systemPathsSync, getServerPath, getServerPathSync } : {}),
  };
}

// fs.* 为 plugin 级别（插件数据目录 plugins/<name>/，无需声明权限）；
// fs.server.* / fs.outer.* 需在 yeow.config.json 声明 fs:server.* / fs:outer.*。
export const fs = {
  ..._makeFs('plugin'),
  server: _makeFs('server'),
  outer: _makeFs('outer'),
};

// 顶层函数 = plugin 级别（与 fs.* 一致）
export const readFile = fs.readFile;
export const readFileSync = fs.readFileSync;
export const writeFile = fs.writeFile;
export const writeFileSync = fs.writeFileSync;
export const appendFile = fs.appendFile;
export const appendFileSync = fs.appendFileSync;
export const exists = fs.exists;
export const existsSync = fs.existsSync;
export const stat = fs.stat;
export const statSync = fs.statSync;
export const isDirectory = fs.isDirectory;
export const isDirectorySync = fs.isDirectorySync;
export const deleteFile = fs.deleteFile;
export const deleteFileSync = fs.deleteFileSync;
export const mkdir = fs.mkdir;
export const mkdirSync = fs.mkdirSync;
export const list = fs.list;
export const listSync = fs.listSync;
export const createReadStream = fs.createReadStream;
export const createWriteStream = fs.createWriteStream;

// ── 流式读写（有状态句柄；背压 = 显式响应——每个操作 await 结果后才发起下一块）──

/** 读流选项：字节偏移区间（start 含、end 含）；encoding 创建时固定（默认 Uint8Array）。 */
export interface ReadStreamOptions {
  start?: number;
  end?: number;
  encoding?: FsEncoding;
}

/** 写流选项：打开模式 + 编码（encoding 创建时固定，运行时不可修改）。 */
export interface WriteStreamOptions {
  flags?: 'w' | 'a' | 'wx';
  encoding?: FsEncoding;
}

/** 文件状态（stat）。mtimeMs / ctimeMs 为 epoch 毫秒。 */
export interface FileStat {
  isFile: boolean;
  isDirectory: boolean;
  size: number;
  mtimeMs: number;
  ctimeMs: number;
}

/** 文件读流：read() 一次返回一块（默认 1 MiB），null = EOF；可 for await。 */
export interface ReadStream<Chunk = Uint8Array> {
  read(maxBytes?: number): Promise<Chunk | null>;
  close(): Promise<void>;
  [Symbol.asyncIterator](): AsyncIterator<Chunk>;
}

/** 文件写流：write(chunk) 等到写入完成（显式响应背压），end() 冲刷并关闭。 */
export interface WriteStream {
  /** 创建时固定的编码；未指定时字符串 chunk 按 UTF-8 编码。运行时不可修改。 */
  readonly encoding?: FsEncoding;
  write(chunk: Uint8Array | string): Promise<void>;
  /** 冲刷缓冲并关闭（调用后不可再 write）。 */
  end(): Promise<void>;
  close(): Promise<void>;
}

function _makeReadStream<Chunk>(
  op: (name: string, p: Record<string, unknown>) => Promise<unknown>,
  id: string,
  decoder: { push(b64: string): Chunk | null; flush(): Chunk | null },
): ReadStream<Chunk> {
  let closed = false;
  const check = () => { if (closed) throw new Error('read stream closed'); };
  return {
    async read(maxBytes?: number): Promise<Chunk | null> {
      check();
      while (true) {
        const r = await op('read', { id, maxBytes }) as { data?: string; eof?: boolean };
        if (r.eof) return decoder.flush();
        const chunk = decoder.push(r.data as string);
        // UTF-8 解码器可能因跨块多字节序列暂未产出——继续读下一块
        if (chunk !== null) return chunk;
      }
    },
    async close() {
      if (closed) return;
      closed = true;
      await op('close', { id });
    },
    async *[Symbol.asyncIterator]() {
      while (true) {
        const chunk = await this.read();
        if (chunk === null) break;
        yield chunk;
      }
    },
  };
}

function _makeWriteStream(
  op: (name: string, p: Record<string, unknown>) => Promise<unknown>,
  id: string,
  encoding?: FsEncoding,
): WriteStream {
  let closed = false;
  const check = () => { if (closed) throw new Error('write stream closed'); };
  const toBytes = (chunk: Uint8Array | string): Uint8Array => {
    if (typeof chunk !== 'string') return chunk; // Uint8Array 始终按原始字节写入
    return encoding === 'base64' ? Uint8Array.fromBase64(chunk) : stringToBytesSync(chunk);
  };
  return {
    encoding,
    async write(chunk: Uint8Array | string) {
      check();
      await op('write', { id, data: toBytes(chunk).toBase64() });
    },
    async end() {
      check();
      closed = true;
      await op('end', { id });
    },
    async close() {
      if (closed) return;
      closed = true;
      await op('close', { id });
    },
  };
}
