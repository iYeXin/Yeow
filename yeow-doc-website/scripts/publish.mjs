// 发布：把构建产物目录（.vitepress/dist，即整个站点）压缩为 zip 后上传到部署服务器
// （multipart/form-data，x-api-key 鉴权）。由 "npm run publish" 调用（先构建后上传）。
// 配置（.env，不入库；可复制 .env.example）：
//   YEOW_PUBLISH_API_KEY=your-secret-key
//   YEOW_PUBLISH_URL=http://localhost:3000/deploy
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

const distDir = resolve(root, '.vitepress', 'dist');
if (!existsSync(distDir)) {
  console.error(`[publish] Build output not found: ${distDir} — run "npm run build" first`);
  process.exit(1);
}

const zipPath = join(tmpdir(), `yeow-site-${Date.now()}.zip`);
try {
  console.log(`[publish] Zipping build output ${distDir} → ${zipPath}`);
  const zip = new AdmZip();
  zip.addLocalFolder(distDir, '');
  zip.writeZip(zipPath);
  console.log(`[publish] Zip size: ${readFileSync(zipPath).length} bytes`);

  const form = new FormData();
  form.append('file', new Blob([readFileSync(zipPath)]), basename(zipPath));

  console.log(`[publish] Uploading to ${deployUrl}`);
  const res = await fetch(deployUrl, {
    method: 'POST',
    headers: { 'x-api-key': apiKey },
    body: form,
  });
  const body = await res.text();
  if (!res.ok) {
    console.error(`[publish] Deploy failed (HTTP ${res.status}): ${body}`);
    process.exit(1);
  }
  console.log(`[publish] Deploy OK (HTTP ${res.status}): ${body}`);
} catch (e) {
  console.error(`[publish] Deploy error: ${e.message}`);
  process.exit(1);
} finally {
  rmSync(zipPath, { force: true });
}
