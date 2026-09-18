#!/usr/bin/env node
// Yeow 统一测试入口。
//   node tests/run.mjs simple [--build] [--filter=...] [--json=...] [--api]
//   node tests/run.mjs full   [--keep] [--outfile=...] [--fresh]
//
// simple：离线快速 —— quickjs 桥测试组件 + runtime JUnit（maven）；--api 追加 yeow-api 类型检查。
// full  ：实机 Paper（缓存 jar）+ 测试插件 + 断言（P2，待实现）
import { spawnSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const [tier = 'simple', ...rest] = process.argv.slice(2);
const has = (f) => rest.includes(f) || rest.some((a) => a.startsWith(f + '='));
const WIN = process.platform === 'win32';

function step(title, fn) {
  console.log(`\n── ${title} ──`);
  const code = fn();
  if (code !== 0) {
    console.error(`\n✗ ${title} (exit ${code})`);
    process.exit(code);
  }
  console.log(`✓ ${title}`);
}

function nodeScript(rel, args) {
  const r = spawnSync(process.execPath, [resolve(ROOT, rel), ...args], { cwd: ROOT, stdio: 'inherit', shell: false });
  return r.status ?? 1;
}

function sh(cmd, args, cwd) {
  const r = spawnSync(cmd, args, { cwd, stdio: 'inherit', shell: WIN });
  return r.status ?? 1;
}

async function runSimple(args) {
  const quickjsArgs = args.filter((a) => a !== '--api');
  step('quickjs bridge tests', () => nodeScript('tests/quickjs/build.mjs', quickjsArgs));
  step('runtime JUnit (maven)', () => sh('mvn', ['-q', 'test'], join(ROOT, 'yeow-runtime', 'jvm')));
  if (has('--api')) {
    step('yeow-api typecheck (tsc)', () =>
      sh('npx', ['--yes', 'typescript@5', 'tsc', '-p', 'yeow-api'], ROOT));
  }
  return 0;
}

async function runFull(args) {
  step('paper e2e', () => nodeScript('tests/e2e/harness.mjs', args));
  return 0;
}

const tiers = { simple: runSimple, full: runFull };
const fn = tiers[tier];
if (!fn) {
  console.error(`unknown tier: ${tier} (expected: simple | full)`);
  process.exit(2);
}
process.exit(await fn(rest));
