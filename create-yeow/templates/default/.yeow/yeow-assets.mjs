import { readFileSync, readdirSync, existsSync, statSync, mkdirSync, copyFileSync, realpathSync } from 'fs';
import { resolve } from 'path';
import { createRequire } from 'module';
import { randomBytes } from 'crypto';

const require = createRequire(import.meta.url);

const slash = p => p.replace(/\\/g, '/');
const normKey = p => slash(resolve(p)).toLowerCase();

/** 读取 yeow.config.json 的 permissions（缺失/解析失败 → 空数组）。 */
function readPerms(configPath) {
    try {
        const j = JSON.parse(readFileSync(configPath, 'utf-8'));
        if (Array.isArray(j.permissions)) return j.permissions.filter(x => typeof x === 'string');
    } catch { /* 无 yeow.config.json 或解析失败 */ }
    return [];
}

// ── 依赖项收集（node_modules 扫描）─────────────────────────────
// 规则：
//   - 主项目无条件参与（始终分配 id，保证 getAssetsPath 恒可用；有 assets/ 才复制）
//   - 依赖包：node_modules 顶层目录（含 @scope/name 两级），要求
//     assets/ 目录存在 且 peerDependencies 含 yeow-api 键
//   - 每个候选同时读取其 yeow.config.json 的 permissions（依赖包可自行声明权限）
// 键：<name>-<version>。npm/pnpm 扁平布局支持良好；yarn 的 hoisting
// 差异可能导致依赖不在预期位置（见文档说明）。
function collectCandidates(root, pkgJson) {
    const candidates = [];
    const ownAssets = resolve(root, 'assets');
    candidates.push({
        key: pkgJson.name + '-' + pkgJson.version,
        pkgDir: root,
        absSrc: ownAssets,
        hasAssets: existsSync(ownAssets),
        perms: readPerms(resolve(root, 'yeow.config.json')),
    });

    const nm = resolve(root, 'node_modules');
    if (existsSync(nm)) {
        const names = [];
        for (const entry of readdirSync(nm)) {
            const p = resolve(nm, entry);
            let st;
            try { st = statSync(p); } catch { continue; } // statSync 跟随 symlink（pnpm）
            if (!st.isDirectory()) continue;
            if (entry.startsWith('@')) {
                for (const sub of readdirSync(p)) {
                    const sp = resolve(p, sub);
                    try { if (statSync(sp).isDirectory()) names.push(entry + '/' + sub); } catch { /* 跳过 */ }
                }
            } else {
                names.push(entry);
            }
        }
        for (const name of names) {
            const pkgDir = resolve(nm, ...name.split('/'));
            let meta;
            try { meta = JSON.parse(readFileSync(resolve(pkgDir, 'package.json'), 'utf-8')); } catch { continue; }
            if (!meta.peerDependencies || !meta.peerDependencies['yeow-api']) continue;
            const pkgAssets = resolve(pkgDir, 'assets');
            if (!existsSync(pkgAssets)) continue;
            candidates.push({
                key: meta.name + '-' + (meta.version || '0.0.0'),
                pkgDir,
                absSrc: pkgAssets,
                hasAssets: true,
                perms: readPerms(resolve(pkgDir, 'yeow.config.json')),
            });
        }
    }
    return candidates;
}

/**
 * 合并主项目与全部依赖包的权限声明（yeow.config.json 的 permissions）。
 * 主项目在前，依赖包按收集顺序追加，去重保持首个出现顺序。
 * 通配归一化：存在 `X:*` 时移除其余 `X:xxx` 子节点（通配已覆盖，无需冗余声明）。
 */
export function readMergedPermissions(root, pkgJson) {
    const merged = [];
    const seen = new Set();
    for (const c of collectCandidates(root, pkgJson)) {
        for (const p of c.perms) {
            if (!seen.has(p)) { seen.add(p); merged.push(p); }
        }
    }
    const wildcardChannels = new Set();
    for (const p of merged) {
        if (p.endsWith(':*')) wildcardChannels.add(p.slice(0, -2));
    }
    if (wildcardChannels.size === 0) return merged;
    return merged.filter(p => {
        if (p.endsWith(':*')) return true;
        const idx = p.lastIndexOf(':');
        if (idx <= 0) return true;
        return !wildcardChannels.has(p.slice(0, idx));
    });
}

// ── id 分配（8 位 hex，不哈希内容，仅保证构建内唯一）──────────
// 每个依赖项的资产部署到 .assets/<id>/，id 唯一 → 无同名冲突。
function assignIds(candidates) {
    const used = new Set();
    const ids = new Map(); // 路径（realpath + 原始路径，lowercase）→ id
    for (const c of candidates) {
        let id;
        do { id = randomBytes(4).toString('hex'); } while (used.has(id));
        used.add(id);
        c.id = id;
        ids.set(normKey(c.pkgDir), id);
        try { ids.set(normKey(realpathSync(c.pkgDir)), id); } catch { /* 保持原始路径 */ }
    }
    return ids;
}

// ── 原样部署到 .assets/<id>/（无改名，相对引用天然有效）────────
function copyDir(src, dst) {
    mkdirSync(dst, { recursive: true });
    for (const entry of readdirSync(src, { withFileTypes: true })) {
        const s = resolve(src, entry.name);
        const d = resolve(dst, entry.name);
        if (entry.isDirectory()) copyDir(s, d);
        else copyFileSync(s, d);
    }
}

function deployAll(candidates, assetsOutDir) {
    for (const c of candidates) {
        if (!c.hasAssets) continue;
        copyDir(c.absSrc, resolve(assetsOutDir, c.id));
    }
}

// ── esbuild 插件：yeow-dev 虚拟模块 ────────────────────────────
// yeow-dev 是构建期模块（发布为空包）：getAssetsPath 由构建器按
// importer 所属依赖项注入对应命名空间 id，运行时无需任何改动。
export function makeAssetPlugin({ root, pkgJson, outDir }) {
    const assetsOutDir = resolve(outDir, '.assets');
    return {
        name: 'yeow-assets',
        setup(build) {
            const candidates = collectCandidates(root, pkgJson);
            const ids = assignIds(candidates);
            const rootId = ids.get(normKey(root));
            deployAll(candidates, assetsOutDir);

            // importer → 所属依赖项 id（最长路径前缀匹配；未匹配归主项目）
            const idForImporter = importer => {
                const imp = normKey(importer);
                let best = null;
                let bestLen = -1;
                for (const [dir, id] of ids) {
                    if (dir === imp || imp.startsWith(dir + '/')) {
                        if (dir.length > bestLen) { best = id; bestLen = dir.length; }
                    }
                }
                return best || rootId;
            };

            build.onResolve({ filter: /^yeow-dev$/ }, args => ({
                path: 'yeow-dev?id=' + idForImporter(args.importer),
                namespace: 'yeow-assets',
            }));
            build.onLoad({ filter: /.*/, namespace: 'yeow-assets' }, args => {
                const m = /yeow-dev\?id=([0-9a-f]+)/.exec(args.path);
                const id = m ? m[1] : rootId;
                return {
                    contents:
                        'const _id = ' + JSON.stringify(id) + ';\n' +
                        'export function getAssetsPath(p) {\n' +
                        '  const parts = String(p).replace(/\\\\/g, "/").split("/");\n' +
                        '  const out = [];\n' +
                        '  for (const s of parts) {\n' +
                        '    if (!s || s === ".") continue;\n' +
                        '    if (s === "..") { if (out.length) out.pop(); continue; }\n' +
                        '    out.push(s);\n' +
                        '  }\n' +
                        '  const trailing = /[\\/\\\\]$/.test(String(p)) ? "/" : "";\n' +
                        '  return "assets/" + _id + (out.length ? "/" + out.join("/") : "") + trailing;\n' +
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
