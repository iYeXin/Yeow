// Paper jar 定位 / 下载缓存。
import { existsSync, statSync, mkdirSync, createWriteStream, readdirSync } from 'node:fs';
import { join, basename } from 'node:path';
import https from 'node:https';

/** 在目录中查找 paper-*.jar（排除 versions/ 里的 patched jar）。 */
function findJarIn(dir) {
  if (!existsSync(dir)) return null;
  for (const n of readdirSync(dir)) {
    if (/^paper-.*\.jar$/.test(n) && statSync(join(dir, n)).size > 1_000_000) return join(dir, n);
  }
  return null;
}

/**
 * 定位 Paper jar：显式路径 → $YEOW_PAPER_JAR → 已知本地缓存 → null。
 * @returns {string|null}
 */
export function locatePaper({ explicit, root }) {
  const files = [explicit, process.env.YEOW_PAPER_JAR].filter(Boolean);
  for (const f of files) {
    if (existsSync(f) && f.endsWith('.jar') && statSync(f).size > 1_000_000) return f;
  }
  for (const dir of [join(root, 'test', 'test', '.yeow', 'dev', 'cache')]) {
    const f = findJarIn(dir);
    if (f) return f;
  }
  return null;
}

function download(url, dest) {
  return new Promise((resolve, reject) => {
    const f = createWriteStream(dest);
    https.get(url, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        f.close();
        download(res.headers.location, dest).then(resolve, reject);
        return;
      }
      if (res.statusCode !== 200) { f.close(); reject(new Error(`HTTP ${res.statusCode}`)); return; }
      res.pipe(f);
      f.on('finish', () => { f.close(); resolve(); });
    }).on('error', (e) => { f.close(); reject(e); });
  });
}

/**
 * 确保有 Paper jar：定位不到时下载到 cacheDir（paper-<version>-<build>.jar）。
 * @returns {Promise<string>}
 */
export async function ensurePaper({ explicit, root, version = '1.21.4', cacheDir }) {
  const located = locatePaper({ explicit, root });
  if (located) return located;
  mkdirSync(cacheDir, { recursive: true });
  const api = `https://api.papermc.io/v2/projects/paper/versions/${version}`;
  const meta = await fetch(api).then((r) => r.json());
  const build = meta.builds[meta.builds.length - 1];
  const name = `paper-${version}-${build}.jar`;
  const dest = join(cacheDir, name);
  if (existsSync(dest) && statSync(dest).size > 1_000_000) return dest;
  const url = `https://api.papermc.io/v2/projects/paper/versions/${version}/builds/${build}/downloads/${name}`;
  await download(url, dest);
  return dest;
}

export { basename };
