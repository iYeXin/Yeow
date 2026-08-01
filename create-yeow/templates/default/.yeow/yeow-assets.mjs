import { readFileSync, readdirSync, existsSync, statSync, mkdirSync, copyFileSync } from 'fs';
import { resolve, dirname, basename, extname } from 'path';
import { createRequire } from 'module';
import { createHash } from 'crypto';

const require = createRequire(import.meta.url);

// ── 哈希树 ─────────────────────────────────────────────────────────
// 哈希规则：
//   - 根级文件：独立哈希 <name>.<contentHash><ext>
//   - 顶层目录（assets/ 直接子目录）：整体哈希 <name>.<dirHash>/，内部一切保持原名
// 保证目录内部（含深层子目录）的相对引用始终有效。
function buildTree(absDir) {
    const children = {};
    for (const e of readdirSync(absDir, { withFileTypes: true })) {
        const p = resolve(absDir, e.name);
        if (e.isDirectory()) {
            children[e.name] = buildTree(p);
        } else {
            children[e.name] = {
                isFile: true,
                contentHash: createHash('md5').update(readFileSync(p)).digest('hex').slice(0, 8),
            };
        }
    }
    const parts = Object.keys(children).sort().map(k => {
        const c = children[k];
        return k + ':' + (c.isFile ? c.contentHash : c.hash);
    });
    return { children, isFile: false, hash: createHash('md5').update(parts.join(',')).digest('hex').slice(0, 8) };
}

// ── 部署 + map ─────────────────────────────────────────────────────
// overwrite=false 时（依赖包）不覆盖主项目已有的 map 条目与文件
// topLevel=true：当前目录是 assets/ 直接子目录，需要哈希；内部目录保持原名
function deploy(node, absSrc, outDir, map, origRel, hashedRel, overwrite, topLevel) {
    if (!node.isFile && hashedRel) mkdirSync(resolve(outDir, hashedRel), { recursive: true });
    for (const [name, child] of Object.entries(node.children || {})) {
        if (child.isFile) {
            if (hashedRel === '') {
                const ext = extname(name);
                const base = basename(name, ext);
                const dest = base + '.' + child.contentHash + ext;
                const dst = resolve(outDir, dest);
                if (overwrite || !existsSync(dst)) copyFileSync(resolve(absSrc, name), dst);
                if (overwrite || !map[name]) map[name] = 'assets/' + dest;
            } else {
                const dst = resolve(outDir, hashedRel, name);
                if (overwrite || !existsSync(dst)) {
                    mkdirSync(resolve(outDir, hashedRel), { recursive: true });
                    copyFileSync(resolve(absSrc, name), dst);
                }
                if (overwrite || !map[origRel + name]) map[origRel + name] = 'assets/' + hashedRel + name;
            }
        } else {
            const subOrig = origRel + name + '/';
            // 仅顶层目录哈希；内部目录保持原名（保护深层相对引用）
            const subHashed = topLevel
                ? name + '.' + child.hash + '/'
                : hashedRel + name + '/';
            deploy(child, resolve(absSrc, name), outDir, map, subOrig, subHashed, overwrite, false);
            if (overwrite || !map[subOrig]) map[subOrig] = 'assets/' + subHashed;
        }
    }
}

// ── 收集所有资产来源（主项目 + 依赖包）───────────────────────────
function collectSources(root, pkgJson) {
    const sources = [];
    const own = resolve(root, 'assets');
    if (existsSync(own)) sources.push({ tree: buildTree(own), absSrc: own, overwrite: true });

    for (const pkgName of Object.keys(pkgJson.dependencies || {})) {
        try {
            const pkgJsonPath = require.resolve(pkgName + '/package.json', { paths: [root] });
            const pkgAssets = resolve(dirname(pkgJsonPath), 'assets');
            if (existsSync(pkgAssets)) {
                sources.push({ tree: buildTree(pkgAssets), absSrc: pkgAssets, overwrite: false });
            }
        } catch { /* 非 Yeow 包或未安装，跳过 */ }
    }
    return sources;
}

// ── esbuild 插件：__yeow-assets 虚拟模块 ──────────────────────────
export function makeAssetPlugin({ root, pkgJson, outDir }) {
    const assetsOutDir = resolve(outDir, '.assets');
    return {
        name: 'yeow-assets',
        setup(build) {
            build.onResolve({ filter: /^__yeow-assets$/ }, args => ({
                path: args.path,
                namespace: 'yeow-assets',
            }));
            build.onLoad({ filter: /.*/, namespace: 'yeow-assets' }, async () => {
                const map = {};
                mkdirSync(assetsOutDir, { recursive: true });
                for (const src of collectSources(root, pkgJson)) {
                    deploy(src.tree, src.absSrc, assetsOutDir, map, '', '', src.overwrite, true);
                }
                return {
                    contents:
                        'const _map = ' + JSON.stringify(map) + ';\n' +
                        'function _norm(p) { return p.endsWith("/") ? p.slice(0, -1) : p; }\n' +
                        'export function getPath(p) {\n' +
                        '  if (_map[p]) return _map[p];\n' +
                        '  const n = _norm(p);\n' +
                        '  if (_map[n]) return _map[n];\n' +
                        '  if (_map[n + "/"]) return _map[n + "/"];\n' +
                        '  return p;\n' +
                        '}\n',
                    loader: 'js',
                };
            });
        },
    };
}

// ── esbuild 插件：统一 yeow-api/yeow-utils 到主项目实例 ──────────
// 避免依赖包自带副本导致全局状态分裂（如 __yeowInitCbs 被覆盖）
// 注意：返回路径必须用正斜杠，否则 Windows 下 esbuild 解析失败
export function makeDedupePlugin(root) {
    return {
        name: 'dedupe-yeow-core',
        setup(build) {
            build.onResolve({ filter: /^yeow-api$/ }, () => ({
                path: resolve(root, 'node_modules', 'yeow-api', 'src', 'index.ts').replace(/\\/g, '/'),
            }));
            build.onResolve({ filter: /^yeow-utils$/ }, () => ({
                path: resolve(root, 'node_modules', 'yeow-utils', 'src', 'index.ts').replace(/\\/g, '/'),
            }));
        },
    };
}

// ── 资产输出目录（供 build.js 打包 JAR）────────────────────────────
export function assetsOutDirFor(outDir) {
    return resolve(outDir, '.assets');
}

export { buildTree };
