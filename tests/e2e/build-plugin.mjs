// 用 create-yeow 的 build.js 构建测试插件（TS + yeow-api）：把模板 .yeow 工具链
// 复制进项目、链接 yeow-api，运行 build.js，返回产出的 .yeow.zip。
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, symlinkSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..');
export const TEMPLATE_YEOW = join(ROOT, 'create-yeow', 'templates', 'default', '.yeow');

/** 把模板的构建工具链（build.js / yeow-assets.mjs / 模板 jar）复制进项目，并链接 yeow-api。 */
export function prepareToolchain(pluginDir) {
  const yeowDir = join(pluginDir, '.yeow');
  mkdirSync(yeowDir, { recursive: true });
  for (const f of ['build.js', 'yeow-assets.mjs']) {
    cpSync(join(TEMPLATE_YEOW, f), join(yeowDir, f));
  }
  const assetsDir = join(yeowDir, 'assets');
  mkdirSync(assetsDir, { recursive: true });
  for (const n of readdirSync(join(TEMPLATE_YEOW, 'assets'))) {
    if (/^yeow-template-.*\.jar$/.test(n)) cpSync(join(TEMPLATE_YEOW, 'assets', n), join(assetsDir, n));
  }
  const nm = join(pluginDir, 'node_modules');
  mkdirSync(nm, { recursive: true });
  const link = join(nm, 'yeow-api');
  if (!existsSync(link)) {
    symlinkSync(join(ROOT, 'yeow-api'), link, process.platform === 'win32' ? 'junction' : 'dir');
  }
}

/**
 * 构建一个测试插件项目，返回 `.yeow.zip` 路径。
 * @param {string} pluginDir
 * @param {(msg: string) => void} [log]
 */
export function buildPlugin(pluginDir, log = console.log) {
  prepareToolchain(pluginDir);
  const cfg = JSON.parse(readFileSync(join(pluginDir, 'yeow.config.json'), 'utf8'));
  log(`[build] ${cfg.name}: node .yeow/build.js`);
  const r = spawnSync(process.execPath, [join(pluginDir, '.yeow', 'build.js')], { cwd: pluginDir, stdio: 'inherit' });
  if (r.status !== 0) throw new Error(`build failed for ${cfg.name} (exit ${r.status})`);
  const zip = join(pluginDir, 'dist', `${cfg.name}-${cfg.version}.yeow.zip`);
  if (!existsSync(zip)) throw new Error(`build produced no zip for ${cfg.name} (expected ${basename(zip)})`);
  return zip;
}
