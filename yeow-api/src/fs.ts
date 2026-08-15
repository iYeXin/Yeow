type FsLevel = 'plugin' | 'server' | 'outer';

import { stringToBytes } from './util.js';

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

/** 按 fs 级别（plugin/server/outer）生成全套文件操作。 */
function _makeFs(level: FsLevel) {
  const t = (op: string) => `${level}.${op}`;

  async function readFile(path: string): Promise<string> {
    const r = await _sendFsAsync({ t: t('readFile'), p: { path } }) as { data: string };
    return r.data;
  }
  function readFileSync(path: string): string {
    return (_sendFs({ t: t('readFile'), p: { path } }) as { data: string }).data;
  }

  async function readFileBase64(path: string): Promise<string> {
    const r = await _sendFsAsync({ t: t('readBase64'), p: { path } }) as { data: string };
    return r.data;
  }
  function readFileBase64Sync(path: string): string {
    return (_sendFs({ t: t('readBase64'), p: { path } }) as { data: string }).data;
  }

  async function writeFile(path: string, data: string): Promise<void> {
    await _sendFsAsync({ t: t('writeFile'), p: { path, data } });
  }
  function writeFileSync(path: string, data: string): void {
    _sendFs({ t: t('writeFile'), p: { path, data } });
  }

  async function writeFileBase64(path: string, data: string): Promise<void> {
    await _sendFsAsync({ t: t('writeBase64'), p: { path, data } });
  }
  function writeFileBase64Sync(path: string, data: string): void {
    _sendFs({ t: t('writeBase64'), p: { path, data } });
  }

  async function appendFile(path: string, data: string): Promise<void> {
    await _sendFsAsync({ t: t('appendFile'), p: { path, data } });
  }
  function appendFileSync(path: string, data: string): void {
    _sendFs({ t: t('appendFile'), p: { path, data } });
  }

  async function exists(path: string): Promise<boolean> {
    const r = await _sendFsAsync({ t: t('exists'), p: { path } });
    return r === true || String(r) === 'true';
  }
  function existsSync(path: string): boolean {
    const r = _sendFs({ t: t('exists'), p: { path } });
    return r === true || String(r) === 'true';
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

  async function createReadStream(path: string, options?: ReadStreamOptions): Promise<ReadStream> {
    const r = await _sendFsAsync({ t: t('openRead'), p: { path, ...options } }) as { id: string };
    return _makeReadStream((op, p2) => _sendFsAsync({ t: t(op), p: p2 }), r.id);
  }

  async function createWriteStream(path: string, options?: WriteStreamOptions): Promise<WriteStream> {
    const r = await _sendFsAsync({ t: t('openWrite'), p: { path, ...options } }) as { id: string };
    return _makeWriteStream((op, p2) => _sendFsAsync({ t: t(op), p: p2 }), r.id);
  }

  return {
    readFile, readFileSync, readFileBase64, readFileBase64Sync,
    writeFile, writeFileSync, writeFileBase64, writeFileBase64Sync,
    appendFile, appendFileSync,
    exists, existsSync, isDirectory, isDirectorySync,
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
export const readFileBase64 = fs.readFileBase64;
export const readFileBase64Sync = fs.readFileBase64Sync;
export const writeFile = fs.writeFile;
export const writeFileSync = fs.writeFileSync;
export const writeFileBase64 = fs.writeFileBase64;
export const writeFileBase64Sync = fs.writeFileBase64Sync;
export const appendFile = fs.appendFile;
export const appendFileSync = fs.appendFileSync;
export const exists = fs.exists;
export const existsSync = fs.existsSync;
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

/** 读流选项：字节偏移区间（start 含、end 含；缺省 = 全文件）。 */
export interface ReadStreamOptions {
  start?: number;
  end?: number;
}

/** 写流选项：打开模式——w 覆盖（默认）/ a 追加 / wx 排他创建（已存在报错）。 */
export interface WriteStreamOptions {
  flags?: 'w' | 'a' | 'wx';
}

/** 文件读流：read() 一次返回一块（默认 1 MiB），null = EOF；可 for await。 */
export interface ReadStream {
  read(maxBytes?: number): Promise<Uint8Array | null>;
  close(): Promise<void>;
  [Symbol.asyncIterator](): AsyncIterator<Uint8Array>;
}

/** 文件写流：write(chunk) 等到写入完成（显式响应背压），end() 冲刷并关闭。 */
export interface WriteStream {
  write(chunk: Uint8Array | string): Promise<void>;
  /** 冲刷缓冲并关闭（调用后不可再 write）。 */
  end(): Promise<void>;
  close(): Promise<void>;
}

function _makeReadStream(
  op: (name: string, p: Record<string, unknown>) => Promise<unknown>,
  id: string,
): ReadStream {
  let closed = false;
  const check = () => { if (closed) throw new Error('read stream closed'); };
  return {
    async read(maxBytes?: number) {
      check();
      const r = await op('read', { id, maxBytes }) as { data?: string; eof?: boolean };
      if (r.eof) return null;
      return Uint8Array.fromBase64(r.data as string);
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
): WriteStream {
  let closed = false;
  const check = () => { if (closed) throw new Error('write stream closed'); };
  return {
    async write(chunk: Uint8Array | string) {
      check();
      const data = typeof chunk === 'string' ? stringToBytes(chunk) : chunk;
      await op('write', { id, data: data.toBase64() });
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
