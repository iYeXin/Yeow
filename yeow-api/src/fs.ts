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

export async function readFile(path: string): Promise<string> {
  const r = await _sendFsAsync({ t: 'readFile', p: { path } }) as { data: string };
  return r.data;
}
export function readFileSync(path: string): string {
  return (_sendFs({ t: 'readFile', p: { path } }) as { data: string }).data;
}

export async function readFileBase64(path: string): Promise<string> {
  const r = await _sendFsAsync({ t: 'readBase64', p: { path } }) as { data: string };
  return r.data;
}
export function readFileBase64Sync(path: string): string {
  return (_sendFs({ t: 'readBase64', p: { path } }) as { data: string }).data;
}

export async function writeFile(path: string, data: string): Promise<void> {
  await _sendFsAsync({ t: 'writeFile', p: { path, data } });
}
export function writeFileSync(path: string, data: string): void {
  _sendFs({ t: 'writeFile', p: { path, data } });
}

export async function writeFileBase64(path: string, data: string): Promise<void> {
  await _sendFsAsync({ t: 'writeBase64', p: { path, data } });
}
export function writeFileBase64Sync(path: string, data: string): void {
  _sendFs({ t: 'writeBase64', p: { path, data } });
}

export async function appendFile(path: string, data: string): Promise<void> {
  await _sendFsAsync({ t: 'appendFile', p: { path, data } });
}
export function appendFileSync(path: string, data: string): void {
  _sendFs({ t: 'appendFile', p: { path, data } });
}

export async function exists(path: string): Promise<boolean> {
  const r = await _sendFsAsync({ t: 'exists', p: { path } });
  return r === true || String(r) === 'true';
}
export function existsSync(path: string): boolean {
  const r = _sendFs({ t: 'exists', p: { path } });
  return r === true || String(r) === 'true';
}

export async function isDirectory(path: string): Promise<boolean> {
  const r = await _sendFsAsync({ t: 'isDirectory', p: { path } });
  return r === true || String(r) === 'true';
}
export function isDirectorySync(path: string): boolean {
  const r = _sendFs({ t: 'isDirectory', p: { path } });
  return r === true || String(r) === 'true';
}

export async function deleteFile(path: string): Promise<boolean> {
  const r = await _sendFsAsync({ t: 'delete', p: { path } });
  return r === true || String(r) === 'true';
}
export function deleteFileSync(path: string): boolean {
  const r = _sendFs({ t: 'delete', p: { path } });
  return r === true || String(r) === 'true';
}

export async function mkdir(path: string): Promise<void> {
  await _sendFsAsync({ t: 'mkdir', p: { path } });
}
export function mkdirSync(path: string): void {
  _sendFs({ t: 'mkdir', p: { path } });
}

export async function list(path: string): Promise<string[]> {
  return await _sendFsAsync({ t: 'list', p: { path } }) as string[];
}
export function listSync(path: string): string[] {
  return _sendFs({ t: 'list', p: { path } }) as string[];
}

export const fs = {
  readFile, readFileSync, readFileBase64, readFileBase64Sync,
  writeFile, writeFileSync, writeFileBase64, writeFileBase64Sync,
  appendFile, appendFileSync,
  exists, existsSync, isDirectory, isDirectorySync,
  deleteFile, deleteFileSync, mkdir, mkdirSync, list, listSync,
};
