// 发布：把各语言站点的构建产物（dist 目录）逐个压缩为 zip 后上传到部署服务器。
// 每个站点一个部署路径（.env 中 `*_PATH`，配置到即发布）：
//   POST <YEOW_PUBLISH_URL>?path=<站点路径>，multipart/form-data（file） + x-api-key 鉴权。
// 由 "npm run publish" 调用（先 "npm run build" 构建全部语言，再逐个上传）。
// 配置（.env，不入库；可复制 .env.example）：
//   YEOW_PUBLISH_API_KEY=your-secret-key
//   YEOW_PUBLISH_URL=http://your-server:17492/deploy
//   CN_PATH=
//   EN_PATH=
import { readFileSync, existsSync, rmSync } from 'fs';
import { resolve, dirname, join, basename } from 'path';
import { tmpdir } from 'os';
import { fileURLToPath } from 'url';
import AdmZip from 'adm-zip';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** 极简 .env 解析（KEY=VALUE；# 注释；引号包裹去引号）。 */
function loadEnv(file) {
  if (!existsSync(file)) return {};
  const env = {};
  for (const raw of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    env[key] = value;
  }
  return env;
}

const env = loadEnv(join(root, '.env'));
const apiKey = env.YEOW_PUBLISH_API_KEY || '';
const deployUrl = env.YEOW_PUBLISH_URL || '';

if (!apiKey) {
  console.error('[publish] Missing YEOW_PUBLISH_API_KEY in .env');
  process.exit(1);
}
if (!deployUrl) {
  console.error('[publish] Missing YEOW_PUBLISH_URL in .env');
  process.exit(1);
}

/** 各语言站点：dist 产物 + 部署路径（`*_PATH` 配置到即发布，可只配其一）。 */
const sites = [
  { name: 'cn', dist: resolve(root, 'vp-roots', 'cn', '.vitepress', 'dist'), path: env.CN_PATH },
  { name: 'en', dist: resolve(root, 'vp-roots', 'en', '.vitepress', 'dist'), path: env.EN_PATH },
].filter((s) => s.path && s.path.trim());

if (sites.length === 0) {
  console.error(
    '[publish] No site path configured — set CN_PATH / EN_PATH in .env (e.g. CN_PATH=cn.yexin.wiki/index/yeow/v1/)'
  );
  process.exit(1);
}

/** 发布单个站点：压缩 dist 并 POST 到 <deployUrl>?path=<站点路径>。返回是否成功。 */
async function publishSite(site) {
  if (!existsSync(site.dist)) {
    console.error(`[publish][${site.name}] Build output not found: ${site.dist} — run "npm run build" first`);
    return false;
  }
  const target = `${deployUrl}?path=${site.path.trim()}`;
  const zipPath = join(tmpdir(), `yeow-site-${site.name}-${Date.now()}.zip`);
  try {
    console.log(`[publish][${site.name}] Zipping build output ${site.dist} → ${zipPath}`);
    const zip = new AdmZip();
    zip.addLocalFolder(site.dist, '');
    zip.writeZip(zipPath);
    const size = readFileSync(zipPath).length;
    console.log(`[publish][${site.name}] Zip size: ${size} bytes`);

    console.log(`[publish][${site.name}] Uploading to ${target}`);
    const form = new FormData();
    form.append('file', new Blob([readFileSync(zipPath)]), basename(zipPath));
    const res = await fetch(target, {
      method: 'POST',
      headers: { 'x-api-key': apiKey },
      body: form,
    });
    const body = await res.text();
    if (!res.ok) {
      console.error(`[publish][${site.name}] Deploy failed (HTTP ${res.status}): ${body}`);
      return false;
    }
    console.log(`[publish][${site.name}] Deploy OK (HTTP ${res.status}): ${body}`);
    return true;
  } catch (e) {
    console.error(`[publish][${site.name}] Deploy error: ${e.message}`);
    return false;
  } finally {
    rmSync(zipPath, { force: true });
  }
}

let ok = true;
for (const site of sites) {
  if (!(await publishSite(site))) ok = false;
}
process.exit(ok ? 0 : 1);
