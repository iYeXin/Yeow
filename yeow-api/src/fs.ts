type FsLevel = 'plugin' | 'server' | 'outer';

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

  return {
    readFile, readFileSync, readFileBase64, readFileBase64Sync,
    writeFile, writeFileSync, writeFileBase64, writeFileBase64Sync,
    appendFile, appendFileSync,
    exists, existsSync, isDirectory, isDirectorySync,
    deleteFile, deleteFileSync, mkdir, mkdirSync, list, listSync,
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
