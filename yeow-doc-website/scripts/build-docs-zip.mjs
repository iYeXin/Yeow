// 打包全部文档（Yeow-Docs/zh 下所有 .md，排除 public/）为 docs.zip 放入 public/，
// VitePress 构建后输出到站点根（/v1/docs.zip）；同时同步 sitemap.md 到 create-yeow 模板项目。
import { readdirSync, readFileSync, statSync, writeFileSync, mkdirSync, copyFileSync } from 'fs';
import { join, relative, resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import AdmZip from 'adm-zip';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DOCS = resolve(root, '..', 'Yeow-Docs', 'zh');
const PUBLIC = join(DOCS, 'public');
const TEMPLATE_SITEMAP = resolve(root, '..', 'create-yeow', 'templates', 'default', 'sitemap.md');

function walk(dir) {
  const out = [];
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) {
      if (e === 'public') continue; // 排除 public（含打包产物，避免循环）
      out.push(...walk(p));
    } else if (e.endsWith('.md')) {
      out.push(p);
    }
  }
  return out;
}

const files = walk(DOCS);
const zip = new AdmZip();
for (const p of files) {
  zip.addFile(relative(DOCS, p).replace(/\\/g, '/'), readFileSync(p));
}
const buf = zip.toBuffer();
mkdirSync(PUBLIC, { recursive: true });
const out = join(PUBLIC, 'docs.zip');
writeFileSync(out, buf);
console.log(`docs.zip: ${files.length} markdown files → ${out} (${buf.length} bytes)`);

// 同步 sitemap.md 到 create-yeow 模板项目（避免手动复制遗漏）
copyFileSync(join(DOCS, 'sitemap.md'), TEMPLATE_SITEMAP);
console.log(`sitemap.md → template synced (${TEMPLATE_SITEMAP})`);
