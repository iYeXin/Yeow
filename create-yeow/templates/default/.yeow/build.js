import * as esbuild from 'esbuild';
import { readFileSync, writeFileSync, mkdirSync, existsSync, statSync, readdirSync, rmSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import AdmZip from 'adm-zip';
import { execSync } from 'child_process';
import { makeAssetPlugin, makeDedupePlugin, assetsOutDirFor, readMergedPermissions, prepareAssets, computeNativeManifest } from './yeow-assets.mjs';

const root = resolve(fileURLToPath(import.meta.url), '..', '..');
const cfg = JSON.parse(readFileSync(resolve(root, 'yeow.config.json'), 'utf-8'));
const pkgJson = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf-8'));
const { name, version } = cfg;
const isTs = existsSync(resolve(root, 'src', 'index.ts'));
const entry = isTs ? 'src/index.ts' : 'src/index.js';

// ── 类型检查（TS 项目）──
if (isTs && cfg.typecheck !== false) {
    try {
        execSync('npx -p typescript tsc --noEmit', { cwd: root, stdio: 'pipe' });
        console.log('  \u2713 Type check passed');
    } catch (e) {
        const out = e.stdout?.toString().trim();
        const err = e.stderr?.toString().trim();
        if (out) console.error(out);
        if (err) console.error(err);
        if (!out && !err) console.error('  \u2717 Type check failed');
        process.exit(1);
    }
}

const apiVer = cfg.api || '1.18';
const isDev = process.env.YEOW_DEV === 'true';
const outDir = isDev ? resolve(root, 'dist', '.dev') : resolve(root, 'dist', '.yeow');
const assetsOut = assetsOutDirFor(outDir);

async function main() {
    // 清空资产输出，避免旧哈希目录残留
    rmSync(assetsOut, { recursive: true, force: true });

    // ── 计算最终权限（computedPermissions：合并 + 通配归一化）──
    const mergedPerms = readMergedPermissions(root, pkgJson);
    // 回写 computedPermissions 到主项目 yeow.config.json（开发者声明的 permissions 保持原样）
    try {
        const cfgPath = resolve(root, 'yeow.config.json');
        const cfgFile = JSON.parse(readFileSync(cfgPath, 'utf-8'));
        if (JSON.stringify(cfgFile.computedPermissions) !== JSON.stringify(mergedPerms)) {
            cfgFile.computedPermissions = mergedPerms;
            writeFileSync(cfgPath, JSON.stringify(cfgFile, null, 4) + '\n');
            console.log('  \u2713 computedPermissions written back to yeow.config.json');
        }
    } catch (e) { /* 写回失败不阻塞构建 */ }
    if (mergedPerms.length > 0) {
        console.log('  \u2713 Computed permissions (' + mergedPerms.length + '): ' + mergedPerms.join(', '));
    } else {
        console.log('  \u2713 No permissions declared');
    }

    // ── 资产准备（id 分配 + 部署；与插件共享，保证路径一致）──
    const prepared = prepareAssets(root, pkgJson, outDir);

    // ── 原生服务可信性声明（native manifest：打包后路径 → SHA-256）──
    const nativeManifest = computeNativeManifest(prepared);
    if (nativeManifest.length > 0) {
        const fileCount = nativeManifest.reduce((n, e) => n + e.files.length, 0);
        console.log('  \u2713 Native manifest (' + nativeManifest.length + ' services, ' + fileCount + ' files, SHA-256)');
    }

    // ── 打包 ──
    await esbuild.build({
        entryPoints: [resolve(root, entry)],
        outfile: resolve(outDir, 'main.js'),
        bundle: true,
        format: 'iife',
        target: 'es2023',
        platform: 'neutral',
        mainFields: ['module', 'main'],
        conditions: ['import', 'browser'],
        treeShaking: true,
        minify: false,
        sourcemap: isDev ? 'linked' : false,
        plugins: [
            makeDedupePlugin(root),
            makeAssetPlugin({ root, pkgJson, outDir, prepared }),
        ],
    });
    console.log('  \u2713 Bundled (' + (statSync(resolve(outDir, 'main.js')).size / 1024).toFixed(1) + ' KB)');

    // ── 组装 JAR ──
    const zip = new AdmZip(resolve(root, '.yeow', 'assets', 'yeow-template-0.1.0.jar'));
    zip.updateFile('plugin.yml', Buffer.from(
        'name: ' + name + '\n' +
        'version: ' + version + '\n' +
        'main: yeow.template.Bootstrap\n' +
        'api-version: \'' + apiVer + '\'\n' +
        'depend:\n  - Yeow\n'));

    if (isDev) {
        const devInfo = {
            name,
            codeFile: resolve(outDir, 'main.js').replace(/\\/g, '/'),
            assetsDir: assetsOut.replace(/\\/g, '/'),
        };
        writeFileSync(resolve(outDir, 'dev.json'), JSON.stringify(devInfo, null, 2));
        zip.addFile('.yeow/dev.json', readFileSync(resolve(outDir, 'dev.json')));
        console.log('  \u2713 Dev info written');
    } else {
        zip.addFile('.yeow/main.js', readFileSync(resolve(outDir, 'main.js')));
    }

    // 资产（esbuild 插件已写入 assetsOut）
    if (existsSync(assetsOut)) {
        const files = readdirSync(assetsOut, { recursive: true }).filter(f => statSync(resolve(assetsOut, f)).isFile());
        for (const f of files) {
            zip.addFile('assets/' + f.replace(/\\/g, '/'), readFileSync(resolve(assetsOut, f)));
        }
        console.log('  \u2713 Assets included (' + files.length + ' files)');
    }

    zip.addFile('yeow.json', Buffer.from(JSON.stringify({ ...cfg, computedPermissions: mergedPerms, native: nativeManifest })));
    const outJar = resolve(root, 'dist', isDev ? 'plugins' : '', name + '-' + version + '.jar');
    mkdirSync(dirname(outJar), { recursive: true });
    zip.writeZip(outJar);
    console.log('  \u2713 Packaged ' + outJar + '\n');

    // ── 组装 .yeow.zip ──
    // 平台无关插件包：生产放入 plugins/Yeow/ 被自动扫描加载（或 /yeow load/install）；
    // 开发模式同样生成（含 .yeow/dev.json 指向编译产物路径），由 dev-server 部署到 plugins/Yeow/。
    {
        const pkgZip = new AdmZip();
        if (isDev) {
            pkgZip.addFile('.yeow/dev.json', readFileSync(resolve(outDir, 'dev.json')));
        } else {
            pkgZip.addFile('.yeow/main.js', readFileSync(resolve(outDir, 'main.js')));
        }
        if (existsSync(assetsOut)) {
            const files = readdirSync(assetsOut, { recursive: true }).filter(f => statSync(resolve(assetsOut, f)).isFile());
            for (const f of files) {
                pkgZip.addFile('assets/' + f.replace(/\\/g, '/'), readFileSync(resolve(assetsOut, f)));
            }
        }
        pkgZip.addFile('yeow.json', Buffer.from(JSON.stringify({ ...cfg, computedPermissions: mergedPerms, native: nativeManifest })));
        const outZip = resolve(root, 'dist', isDev ? 'plugins' : '', name + '-' + version + '.yeow.zip');
        mkdirSync(dirname(outZip), { recursive: true });
        pkgZip.writeZip(outZip);
        console.log('  \u2713 Packaged ' + outZip + '\n');
    }
}

main().catch(e => { console.error(e); process.exit(1); });
