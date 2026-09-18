// 编译并运行 QuickJS 桥测试组件。
//   node tests/quickjs/build.mjs [--build] [--filter=<substr>] [--json=<path>] [--quiet]
// --build：先执行 `zig build jar` 重建 wrapper（否则要求 zig-out/yeow-quickjs.jar 已存在）。
import { existsSync, mkdirSync, readdirSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..');
const WRAPPER = join(ROOT, 'quickjs-wrapper');
const JAR = join(WRAPPER, 'zig-out', 'yeow-quickjs.jar');
const SRC = join(HERE, 'java');
const OUT = join(HERE, 'out', 'classes');
const CASES = join(HERE, 'cases');
const WIN = process.platform === 'win32';

const argv = process.argv.slice(2);
const wantBuild = argv.includes('--build');
const passthrough = argv.filter((a) => a !== '--build');

function javaExe(name) {
  const home = process.env.JAVA_HOME;
  const file = WIN ? `${name}.exe` : name;
  return home ? join(home, 'bin', file) : name;
}

function listJava(dir, acc = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) listJava(p, acc);
    else if (e.name.endsWith('.java')) acc.push(p);
  }
  return acc;
}

if (wantBuild || !existsSync(JAR)) {
  if (wantBuild) {
    console.log('[quickjs] zig build jar …');
    const r = spawnSync('zig', ['build', 'jar'], { cwd: WRAPPER, stdio: 'inherit', shell: false });
    if (r.status !== 0) process.exit(r.status ?? 1);
  }
}
if (!existsSync(JAR)) {
  console.error(`[quickjs] missing wrapper jar: ${JAR}\n  build it with: cd quickjs-wrapper && zig build jar`);
  process.exit(1);
}

rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });
const sources = listJava(SRC);
if (sources.length === 0) {
  console.error(`[quickjs] no sources under ${SRC}`);
  process.exit(1);
}
console.log(`[quickjs] javac ${sources.length} file(s) …`);
const javac = spawnSync(javaExe('javac'), ['-cp', JAR, '-d', OUT, ...sources], { stdio: 'inherit', shell: false });
if (javac.status !== 0) process.exit(javac.status ?? 1);

const cp = [JAR, OUT].join(WIN ? ';' : ':');
const runArgs = ['-cp', cp, 'yeow.tests.quickjs.Runner', `--cases=${CASES}`, ...passthrough];
console.log('[quickjs] running …');
const run = spawnSync(javaExe('java'), runArgs, { stdio: 'inherit', shell: false });
process.exit(run.status ?? 1);
