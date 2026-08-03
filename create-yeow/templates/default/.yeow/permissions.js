import { readFileSync, writeFileSync } from 'fs';
import { resolve } from 'path';
import { fileURLToPath } from 'url';
import { collectPermissionsWithSources, readMergedPermissions } from './yeow-assets.mjs';

const root = resolve(fileURLToPath(import.meta.url), '..', '..');
const cfg = JSON.parse(readFileSync(resolve(root, 'yeow.config.json'), 'utf-8'));
const pkgJson = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf-8'));

// ── 权限分布统计（每个权限来自哪个依赖项）──
const sources = collectPermissionsWithSources(root, pkgJson);
const computed = readMergedPermissions(root, pkgJson);

console.log('── Permissions by source ─────────────────────────');
let total = 0;
for (const [perm, owners] of sources) {
    const from = [...owners].join(', ');
    console.log('  ' + perm.padEnd(28) + '← ' + from);
    total += owners.size;
}
console.log('  (' + sources.size + ' declarations from ' + new Set([...sources.values()].flatMap(s => [...s])).size + ' packages)');

console.log('\n── Computed permissions (' + computed.length + ') ─────────────────');
for (const p of computed) console.log('  ' + p);
if (computed.length === 0) console.log('  (none)');

// ── 回写 computedPermissions 到 yeow.config.json ──
const cfgPath = resolve(root, 'yeow.config.json');
const current = JSON.stringify(cfg.computedPermissions);
const next = JSON.stringify(computed);
if (current !== next) {
    cfg.computedPermissions = computed;
    writeFileSync(cfgPath, JSON.stringify(cfg, null, 4) + '\n');
    console.log('\n✓ computedPermissions written back to yeow.config.json');
} else {
    console.log('\n✓ computedPermissions unchanged');
}
