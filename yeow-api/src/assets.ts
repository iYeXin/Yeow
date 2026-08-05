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

export async function read(path: string): Promise<string> {
  const r = await _sendAssetsAsync({ t: 'read', p: { path } }) as { data: string };
  return r.data;
}
export function readSync(path: string): string {
  return (_sendAssets({ t: 'read', p: { path } }) as { data: string }).data;
}

export async function readBase64(path: string): Promise<string> {
  const r = await _sendAssetsAsync({ t: 'readBase64', p: { path } }) as { data: string };
  return r.data;
}
export function readBase64Sync(path: string): string {
  return (_sendAssets({ t: 'readBase64', p: { path } }) as { data: string }).data;
}

export async function extract(path: string, dest?: string): Promise<string> {
  const p: Record<string, unknown> = { path };
  if (dest) p.dest = dest;
  const r = await _sendAssetsAsync({ t: 'extract', p }) as { path: string };
  return r.path;
}
export function extractSync(path: string, dest?: string): string {
  const p: Record<string, unknown> = { path };
  if (dest) p.dest = dest;
  return (_sendAssets({ t: 'extract', p }) as { path: string }).path;
}

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

export const assets = { read, readSync, readBase64, readBase64Sync, extract, extractSync, extractDir, extractDirSync };
