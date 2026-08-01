import { existsSync, mkdirSync, copyFileSync, writeFileSync, readFileSync, createWriteStream, statSync, watch, readdirSync, rmSync } from 'fs';
import { resolve, dirname, basename } from 'path';
import { spawn, execSync } from 'child_process';
import { fileURLToPath } from 'url';
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
const PROXY = process.argv.find(a => a.startsWith('--proxy='))?.split('=').slice(1).join('=');
const STOP = (() => { const a = process.argv.find(a => a.startsWith('--stop=')); if (!a) return null; const m = a.split('=')[1].match(/^(\d+)(s|m|h)?$/); return m ? parseInt(m[1]) * (m[2] === 'm' ? 60 : m[2] === 'h' ? 3600 : 1) : null; })();

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
            res.on('data', chunk => { dl += chunk.length; if (total) process.stdout.write(`\r   ${c.B}Downloading...${c.r} ${(dl / 1024 / 1024).toFixed(1)}MB / ${(total / 1024 / 1024).toFixed(1)}MB`); });
            res.pipe(f);
            f.on('finish', () => { f.close(); process.stdout.write('\n'); resolve(); });
        }).on('error', e => { f.close(); reject(e); });
    });
}

async function ensurePaper() {
    if (PAPER_URL) {
        // User-specified URL or file path
        if (PAPER_URL.startsWith('http://') || PAPER_URL.startsWith('https://')) {
            if (existsSync(PAPER_PATH) && statSync(PAPER_PATH).size > 1_000_000) { ok('Paper found in cache'); return; }
            mkdirSync(CACHE, { recursive: true });
            let agent = null;
            if (PROXY) { info(`Proxy: ${PROXY}`); try { const { HttpsProxyAgent } = await import('https-proxy-agent'); agent = new HttpsProxyAgent(PROXY); } catch {} }
            info('Downloading Paper...');
            try { await download(PAPER_URL, PAPER_PATH, agent); ok('Paper downloaded'); }
            catch (e) { fail('Download failed: ' + e.message); console.log('  Manually: ' + PAPER_PATH); process.exit(1); }
        } else {
            if (!existsSync(PAPER_PATH)) { fail(`Paper JAR not found: ${PAPER_PATH}`); process.exit(1); }
            ok('Paper found at specified path');
        }
    } else {
        if (existsSync(PAPER_PATH) && statSync(PAPER_PATH).size > 1_000_000) { ok('Paper found in cache'); return; }
        mkdirSync(CACHE, { recursive: true });
        info('Downloading Paper...');
        try {
            const apiUrl = `https://api.papermc.io/v2/projects/paper/versions/${PAPER_VERSION}`;
            const versions = await fetch(apiUrl).then(r => r.json());
            const latestBuild = versions.builds[versions.builds.length - 1];
            const dlUrl = `https://api.papermc.io/v2/projects/paper/versions/${PAPER_VERSION}/builds/${latestBuild}/downloads/paper-${PAPER_VERSION}-${latestBuild}.jar`;
            await download(dlUrl, PAPER_PATH, null);
            ok('Paper downloaded');
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

    if (YES) {
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

    info(`Watching src/ + assets/ for changes (WebSocket hot reload)`);
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

async function printFormattedError(err) {
    const c = { r: '\x1b[0m', R: '\x1b[31m', Y: '\x1b[33m', C: '\x1b[36m', B: '\x1b[1m', D: '\x1b[2m', g: '\x1b[32m' };
    let out = `\n${c.R}${c.B}  JS Error [${err.plugin}]${c.r}\n`;
    if (err.context) out += `  ${c.D}context: ${err.context}${c.r}\n`;
    out += `  ${c.Y}${err.message}${c.r}\n`;

    const hasMainJs = err.stack?.match(/main\.js:\d+:\d+/) || err.fileName === 'main.js';
    let consumer = null;
    if (hasMainJs) {
        consumer = await getSourceMapConsumer();
    }
    if (hasMainJs && !consumer) {
    const mapFile = resolve(ROOT, 'dist', '.dev', 'main.js.map');
        out += `  ${c.D}(source-map not found: ${existsSync(mapFile) ? 'exists but failed to parse' : 'missing at ' + mapFile})${c.r}\n`;
    }

    const frames = [];
    if (err.stack) {
        for (const rawLine of err.stack.split('\n')) {
            const m = rawLine.match(/at\s+(?:\S+\s+)?\(?main\.js:(\d+):(\d+)\)?/);
            if (m && consumer) {
                const orig = consumer.originalPositionFor({ line: parseInt(m[1]), column: parseInt(m[2]) });
                if (!orig?.source) {
                    const orig2 = consumer.originalPositionFor({ line: parseInt(m[1]), column: parseInt(m[2]) - 1 });
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
    const jvmArgs = ['-Xmx4G', '-Xms4G', '-Dyeow.dev=true', '-Dyeow.ws.port=' + WS_PORT];
    info(`\nStarting Paper ${PAPER_VERSION} server...`);
    proc = spawn('java', [...jvmArgs, '-jar', resolve(SERVER, PAPER_JAR), '--nogui'], { cwd: SERVER, stdio: ['pipe', 'inherit', 'inherit'] });
    proc.on('exit', code => { warn(`Server exited (${code})`); if (wss) wss.close(); process.exit(0); });
    process.stdin.on('data', d => { if (proc && !proc.killed) try { proc.stdin.write(d); } catch {} });
    if (STOP) { info(`Auto-stop in ${STOP}s`); setTimeout(() => { warn('Auto-stop'); cleanup(); }, STOP * 1000); }
}

async function main() {
    console.log(`\n${c.b}${c.B}  Yeow Dev Server${c.r}\n`);
    if (!existsSync(RUNTIME)) { fail(`Runtime JAR not found: ${RUNTIME}`); process.exit(1); }

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

main().catch(e => { fail(e.message); process.exit(1); });
