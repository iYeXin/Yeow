import { existsSync, mkdirSync, copyFileSync, writeFileSync, readFileSync, createWriteStream, statSync, watch, readdirSync, rmSync } from 'fs';
import { resolve, dirname, basename } from 'path';
import { spawn, execSync } from 'child_process';
import { fileURLToPath } from 'url';
import { createInterface } from 'readline';
import https from 'https';
import { createServer } from 'http';
import { WebSocketServer } from 'ws';
import { SourceMapConsumer } from 'source-map';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, '..');
const DEVDIR = resolve(ROOT, '.yeow', 'dev');
const CACHE = resolve(DEVDIR, 'cache');
const SERVER = resolve(DEVDIR, 'server');
const WS_PORT = 17368;

const YES = process.argv.includes('-y') || process.env.CI === 'true';
const EULA = process.argv.includes('--eula') || YES;
const PROXY = process.argv.find(a => a.startsWith('--proxy='))?.split('=').slice(1).join('=');
const STOP = (() => { const a = process.argv.find(a => a.startsWith('--stop=')); if (!a) return null; const m = a.split('=')[1].match(/^(\d+)(s|m|h)?$/); return m ? parseInt(m[1]) * (m[2] === 'm' ? 60 : m[2] === 'h' ? 3600 : 1) : null; })();

// ── AI 工作流参数（headless 模式）─────────────────────────────
function parseDur(flag, def) {
  const a = process.argv.find(a => a.startsWith(flag));
  if (!a) return def;
  const m = a.split('=')[1].match(/^(\d+)(s|m|h)?$/);
  return m ? parseInt(m[1]) * (m[2] === 'm' ? 60 : m[2] === 'h' ? 3600 : 1) : def;
}
const TIMEOUT = parseDur('--timeout=', 120);   // 服务器加载超时（秒，默认 2m）
const WAIT = parseDur('--wait=', 30);          // 加载成功后等待（秒，默认 30s）
const OUTFILE = process.argv.find(a => a.startsWith('--outfile='))?.split('=').slice(1).join('=') || null;
const KEEP = process.argv.includes('--keep');
const HEADLESS = process.argv.includes('--eula') || process.argv.includes('--timeout')
  || process.argv.includes('--wait') || process.argv.includes('--outfile') || KEEP;

const cfg = JSON.parse(readFileSync(resolve(ROOT, 'yeow.config.json'), 'utf-8'));
const RUNTIME = resolve(ROOT, '.yeow', 'assets', 'yeow-runtime-0.1.0.jar');

// Dev server config (optional, from yeow.config.json)
const devCfg = cfg.dev || {};
const PAPER_VERSION = devCfg.paperVersion || '1.21.4';
const PAPER_URL = devCfg.paperJar || null;
let PAPER_PATH = null;
let PAPER_JAR = null;

if (PAPER_URL) {
  if (PAPER_URL.startsWith('http://') || PAPER_URL.startsWith('https://')) {
    PAPER_PATH = resolve(CACHE, basename(new URL(PAPER_URL).pathname));
    PAPER_JAR  = basename(new URL(PAPER_URL).pathname);
  } else {
    PAPER_PATH = PAPER_URL;
    PAPER_JAR  = basename(PAPER_URL);
  }
} else {
  PAPER_JAR  = `paper-${PAPER_VERSION}.jar`;
  PAPER_PATH = resolve(CACHE, PAPER_JAR);
}

// Config hash — detect changes and recreate dev server
const CONFIG_HASH_FILE = resolve(DEVDIR, '.config-hash');
function configHash() { return JSON.stringify({ paperUrl: PAPER_URL, paperVersion: PAPER_VERSION }); }

function checkConfigChanged() {
  if (!existsSync(CONFIG_HASH_FILE)) return true;
  try {
    return readFileSync(CONFIG_HASH_FILE, 'utf-8').trim() !== configHash();
  } catch { return true; }
}

function saveConfigHash() {
  mkdirSync(DEVDIR, { recursive: true });
  writeFileSync(CONFIG_HASH_FILE, configHash());
}

if (checkConfigChanged()) {
  if (existsSync(SERVER)) {
    console.log('  Paper config changed — recreating dev server...');
    rmSync(SERVER, { recursive: true, force: true });
  }
  saveConfigHash();
}

const c = { r: '\x1b[0m', b: '\x1b[1m', d: '\x1b[2m', g: '\x1b[32m', y: '\x1b[33m', B: '\x1b[34m', C: '\x1b[36m', R: '\x1b[31m', ok: '\x1b[32m✓\x1b[0m', fail: '\x1b[31m✗\x1b[0m', info: '\x1b[36mⓘ\x1b[0m', warn: '\x1b[33m⚠\x1b[0m' };
const log = (msg, color = '') => console.log(`${c.d}[${new Date().toLocaleTimeString()}]${c.r} ${color}${msg}${c.r}`);
const ok = msg => log(`${c.ok} ${msg}`, c.g);
const fail = msg => log(`${c.fail} ${msg}`, c.R);
const info = msg => log(`${c.info} ${msg}`, c.C);
const warn = msg => log(`${c.warn} ${msg}`, c.y);

let proc = null;
let wss = null;

// ── Graceful shutdown ────────────────────────────────────────────
let cleaning = false;
function cleanup() {
  if (cleaning) return;
  cleaning = true;
  if (wss) { try { wss.close(); } catch {} }
  if (proc && !proc.killed) {
    try { proc.stdin.write('stop\n'); } catch {}
    const killer = setTimeout(() => { if (proc && !proc.killed) try { proc.kill('SIGKILL'); } catch {} }, 10000);
    proc.on('close', () => { clearTimeout(killer); process.exit(0); });
  } else {
    process.exit(0);
  }
}
process.on('SIGINT', cleanup);
process.on('SIGTERM', cleanup);
process.on('beforeExit', () => { if (proc && !proc.killed) try { proc.kill(); } catch {} });

// ── WebSocket Server ────────────────────────────────────────────
function startWebSocket() {
    const server = createServer();
    wss = new WebSocketServer({ server });
    wss.on('connection', (ws) => {
        info('Java runtime connected');
        ws.on('message', async (data) => {
            try {
                const msg = JSON.parse(data.toString());
                if (msg.type === 'js-error') {
                    await printFormattedError(msg);
                }
            } catch (e) { warn('Error processing message: ' + (e?.message || e)); }
        });
        ws.on('close', () => info('Java runtime disconnected'));
    });
    server.listen(WS_PORT, () => {
        info(`WebSocket server on port ${WS_PORT}`);
    });
}

function broadcast(msg) {
    if (!wss) return;
    const data = JSON.stringify(msg);
    wss.clients.forEach((ws) => {
        if (ws.readyState === 1) ws.send(data);
    });
}

// ── Download ────────────────────────────────────────────────────
function download(url, dest, agent) {
    return new Promise((resolve, reject) => {
        const f = createWriteStream(dest);
        const o = agent ? { agent } : {};
        https.get(url, o, res => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) { f.close(); download(res.headers.location, dest, agent).then(resolve).catch(reject); return; }
            if (res.statusCode !== 200) { f.close(); reject(new Error(`HTTP ${res.statusCode}`)); return; }
            const total = parseInt(res.headers['content-length'], 10);
            let dl = 0;
            res.on('data', chunk => { dl += chunk.length; if (total && !HEADLESS) process.stdout.write(`\r   ${c.B}Downloading...${c.r} ${(dl / 1024 / 1024).toFixed(1)}MB / ${(total / 1024 / 1024).toFixed(1)}MB`); });
            res.pipe(f);
            f.on('finish', () => { f.close(); if (!HEADLESS) process.stdout.write('\n'); resolve(); });
        }).on('error', e => { f.close(); reject(e); });
    });
}

async function ensurePaper() {
    if (PAPER_URL) {
        // User-specified URL or file path
        if (PAPER_URL.startsWith('http://') || PAPER_URL.startsWith('https://')) {
            if (existsSync(PAPER_PATH) && statSync(PAPER_PATH).size > 1_000_000) { if (!HEADLESS) ok('Paper found in cache'); return; }
            mkdirSync(CACHE, { recursive: true });
            let agent = null;
            if (PROXY) { info(`Proxy: ${PROXY}`); try { const { HttpsProxyAgent } = await import('https-proxy-agent'); agent = new HttpsProxyAgent(PROXY); } catch {} }
            if (!HEADLESS) info('Downloading Paper...');
            try { await download(PAPER_URL, PAPER_PATH, agent); if (!HEADLESS) ok('Paper downloaded'); }
            catch (e) { fail('Download failed: ' + e.message); console.log('  Manually: ' + PAPER_PATH); process.exit(1); }
        } else {
            if (!existsSync(PAPER_PATH)) { fail(`Paper JAR not found: ${PAPER_PATH}`); process.exit(1); }
            if (!HEADLESS) ok('Paper found at specified path');
        }
    } else {
        if (existsSync(PAPER_PATH) && statSync(PAPER_PATH).size > 1_000_000) { if (!HEADLESS) ok('Paper found in cache'); return; }
        mkdirSync(CACHE, { recursive: true });
        info('Downloading Paper...');
        try {
            const apiUrl = `https://api.papermc.io/v2/projects/paper/versions/${PAPER_VERSION}`;
            const versions = await fetch(apiUrl).then(r => r.json());
            const latestBuild = versions.builds[versions.builds.length - 1];
            const dlUrl = `https://api.papermc.io/v2/projects/paper/versions/${PAPER_VERSION}/builds/${latestBuild}/downloads/paper-${PAPER_VERSION}-${latestBuild}.jar`;
            await download(dlUrl, PAPER_PATH, null);
            if (!HEADLESS) ok('Paper downloaded');
        } catch (e) {
            fail('Download failed: ' + e.message);
            console.log('  Manually download to: ' + PAPER_PATH);
            console.log('  Or set "paperJar" in yeow.config.json dev section');
            process.exit(1);
        }
    }
}

function buildPlugin() {
    info('Building plugin...');
    try { execSync('node .yeow/build.js', { cwd: ROOT, stdio: 'inherit', env: { ...process.env, YEOW_DEV: 'true' } }); ok('Plugin built'); }
    catch (e) { fail('Build failed: ' + e.message); process.exit(1); }
}

function copyToPlugins(src, label) {
    const d = resolve(SERVER, 'plugins'); mkdirSync(d, { recursive: true });
    copyFileSync(src, resolve(d, basename(src))); ok(`${label} copied`);
}

function copyToYeowDir(src, label) {
    const d = resolve(SERVER, 'plugins', 'Yeow'); mkdirSync(d, { recursive: true });
    copyFileSync(src, resolve(d, basename(src))); ok(`${label} copied`);
}

/** Remove a previously deployed dev plugin JAR so it cannot conflict with the .yeow.zip in plugins/Yeow/. */
function removeStaleDevJar() {
    const stale = resolve(SERVER, 'plugins', `${cfg.name}-${cfg.version}.jar`);
    if (existsSync(stale)) {
        rmSync(stale, { force: true });
        warn(`Removed stale dev JAR: ${basename(stale)} (conflicts with plugins/Yeow .yeow.zip)`);
    }
}

async function initServer() {
    const jar = resolve(SERVER, PAPER_JAR);
    if (!existsSync(jar)) copyFileSync(PAPER_PATH, jar);
    const eula = resolve(SERVER, 'eula.txt');
    if (existsSync(eula) && readFileSync(eula, 'utf-8').includes('eula=true')) return;

    if (EULA) {
        if (!existsSync(resolve(SERVER, 'server.properties'))) {
            info('Initializing...');
            await new Promise(r => { const p = spawn('java', ['-Xmx4G', '-Xms4G', '-jar', jar, '--nogui'], { cwd: SERVER, stdio: ['pipe', 'inherit', 'inherit'] }); setTimeout(() => { p.kill(); r(); }, 120000); p.on('exit', r); p.on('error', r); });
        }
        writeFileSync(eula, 'eula=true\n'); ok('EULA auto-accepted');
    } else {
        console.log(`\n   Press Enter to accept Mojang EULA:`);
        await new Promise(r => process.stdin.once('data', () => { writeFileSync(eula, 'eula=true\n'); ok('EULA accepted'); r(); }));
    }
}

function serverProps(port) {
    const f = resolve(SERVER, 'server.properties');
    const m = { 'server-port': String(port), 'online-mode': 'false', 'spawn-protection': '0', 'enable-command-block': 'true', 'max-players': '10', 'difficulty': 'easy', 'motd': cfg.name || 'Yeow Dev' };
    if (existsSync(f)) {
        for (const l of readFileSync(f, 'utf-8').split('\n')) { const eq = l.indexOf('='); if (eq > 0 && !l.startsWith('#')) { const k = l.substring(0, eq).trim(); if (!m[k]) m[k] = l.substring(eq + 1).trim(); } }
    }
    let out = ''; for (const [k, v] of Object.entries(m)) out += `${k}=${v}\n`;
    writeFileSync(f, out);
}

// ── Hot Reload via WebSocket ────────────────────────────────────
function startHotReload() {
    const srcDir = resolve(ROOT, 'src');
    const assetsDir = resolve(ROOT, 'assets');
    if (!existsSync(srcDir)) return;

    let timer = null;
    let building = false;
    const ext = existsSync(resolve(srcDir, 'index.ts')) ? 'ts' : 'js';

    const rebuildAndNotify = () => {
        if (timer) clearTimeout(timer);
        timer = setTimeout(() => {
            if (building) return;
            building = true;
            info('Source changed, rebuilding...');
            try {
                execSync('node .yeow/build.js', { cwd: ROOT, stdio: 'pipe', env: { ...process.env, YEOW_DEV: 'true' } });
                const compiled = resolve(ROOT, 'dist', '.dev', 'main.js');
                if (existsSync(compiled)) {
                    const devAssets = resolve(ROOT, 'dist', '.dev', '.assets');
                    broadcast({
                        type: 'hot-reload',
                        plugin: cfg.name,
                        codeFile: compiled.replace(/\\/g, '/'),
                        assetsDir: existsSync(devAssets) ? devAssets.replace(/\\/g, '/') : null,
                    });
                    _consumer = null;
                    ok('Hot reload sent via WebSocket');
                }
            } catch (e) {
                fail('Build failed: ' + e.message);
                broadcast({ type: 'build-error', plugin: cfg.name, error: e.message });
            }
            building = false;
        }, 300);
    };

    watch(srcDir, { recursive: true }, (event, file) => {
        if (!file || !file.endsWith('.' + ext)) return;
        rebuildAndNotify();
    });

    if (existsSync(assetsDir)) {
        watch(assetsDir, { recursive: true }, (event, file) => {
            rebuildAndNotify();
        });
    }

    // Worker 源码目录（dev.worker[].entry 所在目录）变化 → 重建（worker 随主插件热重载重建）
    const workerCfg = (cfg.dev && cfg.dev.worker) || [];
    const watchedWorkerDirs = new Set();
    for (const w of workerCfg) {
        if (!w?.entry) continue;
        const dir = resolve(ROOT, dirname(w.entry));
        if (!existsSync(dir) || watchedWorkerDirs.has(dir)) continue;
        watchedWorkerDirs.add(dir);
        watch(dir, { recursive: true }, (event, file) => {
            if (!file || !/\.(ts|js|mjs)$/.test(file)) return;
            rebuildAndNotify();
        });
    }

    info(`Watching src/ + assets/${watchedWorkerDirs.size > 0 ? ' + worker dirs' : ''} for changes (WebSocket hot reload)`);
}

// ── Source-Mapped Error Display ─────────────────────────────────
let _consumer = null;
async function getSourceMapConsumer() {
    if (_consumer) return _consumer;
    const mapFile = resolve(ROOT, 'dist', '.dev', 'main.js.map');
    if (!existsSync(mapFile)) return null;
    try {
        const raw = JSON.parse(readFileSync(mapFile, 'utf-8'));
        _consumer = await new SourceMapConsumer(raw);
        return _consumer;
    } catch { return null; }
}

/** Worker 的 source-map（产物位于 dist/.dev/.assets/<id>/worker/<name>.js(.map)）。 */
let _workerConsumers = {};
async function getWorkerSourceMapConsumer(workerName) {
    if (_workerConsumers[workerName]) return _workerConsumers[workerName];
    const assetsRoot = resolve(ROOT, 'dist', '.dev', '.assets');
    if (!existsSync(assetsRoot)) return null;
    try {
        for (const id of readdirSync(assetsRoot)) {
            const mapFile = resolve(assetsRoot, id, 'worker', workerName + '.js.map');
            if (existsSync(mapFile)) {
                const raw = JSON.parse(readFileSync(mapFile, 'utf-8'));
                _workerConsumers[workerName] = await new SourceMapConsumer(raw);
                return _workerConsumers[workerName];
            }
        }
    } catch { /* 未找到 */ }
    _workerConsumers[workerName] = null;
    return null;
}

async function printFormattedError(err) {
    const c = { r: '\x1b[0m', R: '\x1b[31m', Y: '\x1b[33m', C: '\x1b[36m', B: '\x1b[1m', D: '\x1b[2m', g: '\x1b[32m' };
    const isWorker = err.origin && err.origin !== 'main';
    let out = isWorker
        ? `\n${c.R}${c.B}  JS Error in Worker [${err.origin}]${c.r}\n`
        : `\n${c.R}${c.B}  JS Error [${err.plugin}]${c.r}\n`;
    if (err.context) out += `  ${c.D}context: ${err.context}${c.r}\n`;
    out += `  ${c.Y}${err.message}${c.r}\n`;

    // 产物文件名：主插件 main.js；Worker <name>.js
    const bundleName = isWorker ? err.origin + '.js' : 'main.js';
    const bundleRe = new RegExp(bundleName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ':(\\d+):(\\d+)');
    const hasBundle = err.stack?.match(bundleRe) || err.fileName === bundleName;
    let consumer = null;
    if (hasBundle) {
        consumer = isWorker ? await getWorkerSourceMapConsumer(err.origin) : await getSourceMapConsumer();
    }
    if (hasBundle && !consumer) {
        const mapFile = isWorker
            ? resolve(ROOT, 'dist', '.dev', '.assets', '**', 'worker', err.origin + '.js.map')
            : resolve(ROOT, 'dist', '.dev', 'main.js.map');
        out += `  ${c.D}(source-map not found: ${existsSync(mapFile) ? 'exists but failed to parse' : 'missing at ' + mapFile})${c.r}\n`;
    }

    const frames = [];
    if (err.stack) {
        for (const rawLine of err.stack.split('\n')) {
            const m = rawLine.match(/at\s+(?:\S+\s+)?\(?([^\\/\s()]+\.js):(\d+):(\d+)\)?/);
            if (m && consumer && (isWorker ? m[1] === bundleName : m[1] === 'main.js')) {
                const orig = consumer.originalPositionFor({ line: parseInt(m[2]), column: parseInt(m[3]) });
                if (!orig?.source) {
                    const orig2 = consumer.originalPositionFor({ line: parseInt(m[2]), column: parseInt(m[3]) - 1 });
                    if (orig2?.source) { orig.source = orig2.source; orig.line = orig2.line; orig.column = orig2.column; }
                }
                frames.push({ orig, raw: rawLine });
            } else {
                frames.push({ raw: rawLine });
            }
        }
    }

    let ctxFrame = frames.find(f => f.orig?.source?.match(/[\\/]src[\\/]/) && !f.orig.source.includes('node_modules'))?.orig || null;

    if (ctxFrame?.source && ctxFrame.line && consumer) {
        const srcPath = ctxFrame.source.replace(/^\.\.\/\.\.\//, '');
        const ctxRaw = frames.find(f => f.orig === ctxFrame)?.raw || '';
        const fnM = ctxRaw.match(/at\s+(\S+)\s+\(/);
        const fnS = fnM ? fnM[1] + ' ' : '';
        out += `  ${c.C}at ${fnS}(${srcPath}:${ctxFrame.line}:${ctxFrame.column})${c.r}\n`;
        const content = consumer.sourceContentFor(ctxFrame.source);
        if (content) {
            const lines = content.split('\n');
            const start = Math.max(0, ctxFrame.line - 3);
            const end = Math.min(lines.length, ctxFrame.line + 2);
            for (let i = start; i < end; i++) {
                const prefix = i === ctxFrame.line - 1 ? c.R + '  →' : '   ';
                out += `  ${prefix} ${c.D}${String(i + 1).padStart(4)}|${c.r} ${lines[i]}\n`;
                if (i === ctxFrame.line - 1 && ctxFrame.column > 0) {
                    const col = Math.max(0, ctxFrame.column);
                    const indent = ' '.repeat(String(i + 1).padStart(4).length) + '| ';
                    const caret = ' '.repeat(col) + c.R + '^' + c.r;
                    out += `  ${c.R}  →${c.r} ${indent}${caret}\n`;
                }
            }
        }
    }

    if (frames.length > 0) {
        out += `  ${c.D}Stack:${c.r}\n`;
        for (const f of frames) {
            if (f.orig?.source) {
                const srcPath = f.orig.source.replace(/^\.\.\/\.\.\//, '');
                const fnMatch = f.raw.match(/at\s+(\S+)\s+\(/);
                const fn = fnMatch ? fnMatch[1] + ' ' : '';
                const isUser = srcPath.startsWith('src/');
                const style = isUser ? c.B + c.g : c.D;
                out += `  ${style}    at ${fn}(${srcPath}:${f.orig.line}:${f.orig.column})${c.r}\n`;
            } else {
                const internal = f.raw.includes('init.js') || f.raw.includes('unknown.js') ? ' (internal)' : '';
                out += `${c.D}  ${f.raw}${internal}${c.r}\n`;
            }
        }
    }
    console.log(out);
}

function startServer() {
    const jvmArgs = ['-Xmx4G', '-Xms4G', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dyeow.dev=true', '-Dyeow.ws.port=' + WS_PORT];
    info(`\nStarting Paper ${PAPER_VERSION} server...`);
    proc = spawn('java', [...jvmArgs, '-jar', resolve(SERVER, PAPER_JAR), '--nogui'], { cwd: SERVER, stdio: ['pipe', 'inherit', 'inherit'] });
    proc.on('exit', code => { warn(`Server exited (${code})`); if (wss) wss.close(); process.exit(0); });
    process.stdin.on('data', d => { if (proc && !proc.killed) try { proc.stdin.write(d); } catch {} });
    if (STOP) { info(`Auto-stop in ${STOP}s`); setTimeout(() => { warn('Auto-stop'); cleanup(); }, STOP * 1000); }
}

async function main() {
    console.log(`\n${c.b}${c.B}  Yeow Dev Server${c.r}\n`);
    if (!existsSync(RUNTIME)) { fail(`Runtime JAR not found: ${RUNTIME}`); process.exit(1); }

    if (HEADLESS) { await runHeadless(); return; }

    startWebSocket();
    await ensurePaper();
    mkdirSync(SERVER, { recursive: true });
    await initServer();
    serverProps(cfg.dev?.port || 17367);
    buildPlugin();
    removeStaleDevJar();
    copyToYeowDir(resolve(ROOT, 'dist', 'plugins', `${cfg.name}-${cfg.version}.yeow.zip`), 'Plugin (.yeow.zip → plugins/Yeow/)');
    copyToPlugins(RUNTIME, 'Runtime');

    startHotReload();
    startServer();
}

// ── AI 工作流（headless）─────────────────────────────────────────
// 适合 AI 代理/CI：--eula 自动接受 → 下载 → 启动 → 检测加载完成 →
// 等待 --wait 秒后命令自动结束（--keep 保留服务器子进程，日志见 --outfile）。
async function runHeadless() {
    if (!EULA) {
        fail('AI 模式需要 --eula（自动接受 EULA）');
        process.exit(1);
    }
    const log = OUTFILE ? createWriteStream(OUTFILE, { flags: 'a' }) : null;
    const out = (line) => { if (log) log.write(line + '\n'); else console.log(line); };

    info('正在下载/准备服务端…');
    await ensurePaper();
    mkdirSync(SERVER, { recursive: true });
    await initServer();
    serverProps(cfg.dev?.port || 17367);
    buildPlugin();
    removeStaleDevJar();
    copyToYeowDir(resolve(ROOT, 'dist', 'plugins', `${cfg.name}-${cfg.version}.yeow.zip`), 'Plugin (.yeow.zip → plugins/Yeow/)');
    copyToPlugins(RUNTIME, 'Runtime');

    // 编码：确保子进程 stdout 按 UTF-8 输出（Windows 下避免中文字符乱码）
    const jvmArgs = ['-Xmx4G', '-Xms4G', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
        '-Dyeow.dev=true', '-Dyeow.ws.port=' + WS_PORT];
    info(`正在启动 Paper ${PAPER_VERSION}...`);
    proc = spawn('java', [...jvmArgs, '-jar', resolve(SERVER, PAPER_JAR), '--nogui'], { cwd: SERVER, stdio: ['ignore', 'pipe', 'pipe'] });
    info(`Server PID: ${proc.pid}`);

    let started = false, done = false, waitTimer = null;
    const finish = (code) => {
        if (waitTimer) clearTimeout(waitTimer);
        try { if (log) log.end(); } catch {}
        process.exit(code);
    };
    const failTimer = setTimeout(() => {
        if (done) return;
        fail(`服务器在 ${TIMEOUT}s 内未完成加载——请检查网络/依赖下载，或加大超时（--timeout=3m）`);
        killProc();
        finish(1);
    }, TIMEOUT * 1000);

    const onLine = (line) => {
        out(line);
        if (!started && line.includes('Starting org.bukkit.craftbukkit.Main')) {
            started = true;
            info('开始加载（Starting org.bukkit.craftbukkit.Main）');
        }
        if (!done && line.includes('Done (') && line.includes('For help')) {
            done = true;
            clearTimeout(failTimer);
            info(`加载完成——等待 ${WAIT}s 后命令结束${KEEP ? '（--keep 保留服务器进程）' : '（关闭服务器进程）'}…`);
            waitTimer = setTimeout(() => {
                info(`等待结束。日志${OUTFILE ? '：' + OUTFILE : '输出于上方'}；PID=${proc.pid}${KEEP ? '（服务器仍在运行，按需 kill）' : ''}`);
                if (!KEEP) killProc();
                finish(0);
            }, WAIT * 1000);
        }
    };
    createInterface({ input: proc.stdout }).on('line', onLine);
    if (proc.stderr) createInterface({ input: proc.stderr }).on('line', (l) => out('[err] ' + l));

    proc.on('exit', (code) => {
        if (!done) fail(`服务器提前退出（code ${code}）——见${OUTFILE ? '日志 ' + OUTFILE : '上方输出'}`);
        finish(1);
    });
}

function killProc() {
    if (proc && !proc.killed) { try { proc.kill('SIGKILL'); } catch {} }
}

main().catch(e => { fail(e.message); process.exit(1); });
