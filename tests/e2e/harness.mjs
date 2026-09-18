// Paper e2e harness：准备服务器 → 部署 runtime + 测试插件 → 启动 → 收集断言 → 汇总。
//   node tests/e2e/harness.mjs [--server=<dir>] [--paper=<jar>] [--runtime=<jar>]
//                              [--build-only] [--keep] [--outfile=<path>] [--timeout=<sec>]
//
// 测试插件位于 tests/e2e/plugins/<name>/（main.js + yeow.json），由本脚本打包为
// plugins/Yeow/<name>-<version>.yeow.zip。插件在 LOAD 阶段输出 [YEOW-E2E] {json} 哨兵。
import { existsSync, mkdirSync, writeFileSync, readFileSync, copyFileSync, rmSync, readdirSync, statSync, appendFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { makeZip } from '../lib/zip.mjs';
import { ensurePaper } from '../lib/paper.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..');
const PLUGINS_SRC = join(HERE, 'plugins');

function arg(name, def) {
  const a = process.argv.find((x) => x.startsWith(`--${name}=`));
  return a ? a.slice(name.length + 3) : def;
}
const has = (n) => process.argv.includes(`--${n}`) || process.argv.some((x) => x.startsWith(`--${n}=`));

const SERVER = resolve(arg('server', join(HERE, '.work', 'server')));
const RUNTIME = resolve(arg('runtime', join(ROOT, 'create-yeow', 'templates', 'default', '.yeow', 'assets', 'yeow-runtime-0.6.1.jar')));
const PAPER_ARG = arg('paper', null);
const OUT = arg('outfile', null);
const TIMEOUT = parseInt(arg('timeout', '240'), 10);
const KEEP = has('keep');
const BUILD_ONLY = has('build-only');
const WIN = process.platform === 'win32';

function log(line) {
  process.stdout.write(line + '\n');
  if (OUT) appendFileSync(OUT, line + '\n');
}
function fail(msg, code = 1) {
  log(`✗ ${msg}`);
  process.exit(code);
}

// ── 测试插件 ──────────────────────────────────────────────────────
function listPlugins() {
  if (!existsSync(PLUGINS_SRC)) return [];
  return readdirSync(PLUGINS_SRC, { withFileTypes: true })
    .filter((e) => e.isDirectory() && existsSync(join(PLUGINS_SRC, e.name, 'main.js')) && existsSync(join(PLUGINS_SRC, e.name, 'yeow.json')))
    .map((e) => e.name)
    .sort();
}

function buildPluginZips() {
  const outDir = join(SERVER, 'plugins', 'Yeow');
  mkdirSync(outDir, { recursive: true });
  const names = listPlugins();
  if (names.length === 0) fail(`no test plugins under ${PLUGINS_SRC}`);
  const expected = [];
  for (const name of names) {
    const dir = join(PLUGINS_SRC, name);
    const cfg = JSON.parse(readFileSync(join(dir, 'yeow.json'), 'utf8'));
    expected.push(cfg.name);
    for (const n of readdirSync(outDir)) {
      if (n.startsWith(cfg.name + '-')) rmSync(join(outDir, n), { force: true });
    }
    const zip = makeZip([
      { name: '.yeow/main.js', data: readFileSync(join(dir, 'main.js')) },
      { name: 'yeow.json', data: Buffer.from(JSON.stringify(cfg)) },
    ]);
    writeFileSync(join(outDir, `${cfg.name}-${cfg.version}.yeow.zip`), zip);
  }
  return expected;
}

async function prepare() {
  if (!existsSync(RUNTIME)) fail(`runtime jar not found: ${RUNTIME}\n  build: cd yeow-runtime/jvm && mvn -DskipTests install && copy paper/target/yeow-runtime-0.6.1.jar ...`);
  mkdirSync(SERVER, { recursive: true });
  let paperDest = null;
  if (!BUILD_ONLY) {
    const paper = await ensurePaper({ explicit: PAPER_ARG, root: ROOT, cacheDir: join(HERE, '.paper') });
    paperDest = join(SERVER, paper.split(/[\\/]/).pop());
    if (resolve(paper) !== resolve(paperDest) && !existsSync(paperDest)) copyFileSync(paper, paperDest);
  }
  if (!existsSync(join(SERVER, 'eula.txt'))) writeFileSync(join(SERVER, 'eula.txt'), 'eula=true\n');
  if (!existsSync(join(SERVER, 'server.properties'))) {
    writeFileSync(join(SERVER, 'server.properties'),
      'online-mode=false\nserver-port=17367\nspawn-protection=0\nmax-players=10\ndifficulty=easy\n');
  }
  const plugins = join(SERVER, 'plugins');
  mkdirSync(plugins, { recursive: true });
  const runtimeDest = join(plugins, 'yeow-runtime-0.6.1.jar');
  if (!existsSync(runtimeDest) || statSync(runtimeDest).size !== statSync(RUNTIME).size) copyFileSync(RUNTIME, runtimeDest);

  const expected = buildPluginZips();
  log(`[e2e] server   ${SERVER}`);
  log(`[e2e] runtime  ${runtimeDest}`);
  log(`[e2e] plugins  ${expected.join(', ')}`);
  return { paperDest, expected };
}

function runServer(paperDest, expected) {
  return new Promise((resolvePromise) => {
    const jvm = ['-Xmx2G', '-Xms1G', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-jar', paperDest.split(/[\\/]/).pop(), '--nogui'];
    log(`[e2e] starting: java ${jvm.join(' ')}`);
    const proc = spawn('java', jvm, { cwd: SERVER, stdio: ['pipe', 'pipe', 'pipe'], shell: WIN });
    const reports = new Map();
    let loaded = false;
    let buf = '';
    let stopping = false;

    const finish = (code) => {
      if (stopping) return;
      stopping = true;
      clearTimeout(timer);
      resolvePromise({ code, reports: [...reports.values()], loaded });
    };

    const onData = (chunk) => {
      const text = chunk.toString('utf8');
      if (OUT) appendFileSync(OUT, text);
      else process.stdout.write(text);
      buf += text;
      let idx;
      while ((idx = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, idx).replace(/\r$/, '');
        buf = buf.slice(idx + 1);
        if (!loaded && line.includes('Done (') && line.includes('For help')) { loaded = true; log('[e2e] server loaded'); }
        const m = line.match(/\[YEOW-E2E\]\s*(\{.*\})\s*$/);
        if (m) {
          try {
            const r = JSON.parse(m[1]);
            if (r && r.plugin && !reports.has(r.plugin)) {
              reports.set(r.plugin, r);
              const done = expected.every((n) => reports.has(n));
              log(`[e2e] report: ${r.plugin} (${reports.size}/${expected.length})`);
              if (done) {
                if (KEEP) finish(0);
                else { try { proc.stdin.write('stop\n'); } catch {} }
              }
            }
          } catch (e) { log(`[e2e] bad report json: ${e.message}`); }
        }
      }
    };
    proc.stdout.on('data', onData);
    proc.stderr.on('data', onData);
    proc.on('exit', (code) => finish(code ?? -1));

    const timer = setTimeout(() => {
      log(`[e2e] timeout after ${TIMEOUT}s`);
      try { proc.kill('SIGKILL'); } catch {}
      finish(-1);
    }, TIMEOUT * 1000);
  });
}

const prepared = await prepare();
if (BUILD_ONLY) {
  log('[e2e] --build-only: packaging OK');
  process.exit(0);
}

const { code, reports, loaded } = await runServer(prepared.paperDest, prepared.expected);

if (reports.length === 0) fail(`no test report received (server exit ${code}, loaded=${loaded})`);

let totalOk = 0;
let totalFail = 0;
for (const rep of reports) {
  log(`\n[${rep.plugin}]`);
  for (const r of rep.results) log(`  ${r.ok ? 'ok  ' : 'FAIL'} ${r.name}${r.ok ? '' : '  — ' + r.detail}`);
  if (Array.isArray(rep.bench)) {
    log('  bench (debug.payload 同步往返, ms/op):');
    log('    payload           n      mean       min       p50       p99       max');
    for (const b of rep.bench) {
      log('    ' + String(b.name).padEnd(16) + String(b.n).padStart(5) + '  '
        + String(b.mean).padStart(8) + '  ' + String(b.min).padStart(8) + '  '
        + String(b.p50).padStart(8) + '  ' + String(b.p99).padStart(8) + '  '
        + String(b.max).padStart(8));
    }
  }
  totalOk += rep.ok;
  totalFail += rep.fail;
}
log(`\n[e2e] ${totalOk} passed, ${totalFail} failed (plugins: ${reports.length}/${prepared.expected.length})`);
if (reports.length < prepared.expected.length) {
  log(`[e2e] missing reports: ${prepared.expected.filter((n) => !reports.some((r) => r.plugin === n)).join(', ')}`);
  process.exit(1);
}
if (totalFail > 0) process.exit(1);
