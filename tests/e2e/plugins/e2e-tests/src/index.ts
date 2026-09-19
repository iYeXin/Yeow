// e2e 测试插件（TypeScript + yeow-api，经 create-yeow 的 build.js 构建）。
// 覆盖：env / 服务器信息 / material / task（同步 + 批量）/ util（UTF-8、gzip；同步 + 异步）/
// fs（读写追加/目录/列举）/ debug.payload 往返 / 命令注册与派发 / 通信层基准 /
// 假玩家加入（playerJoin + player.get + 定向消息）。
// 结果以单行 JSON 哨兵 [YEOW-E2E] {...} 经 log 通道输出。
import {
  onLoad, log, getEnv, Player, eventOn, call, callBatch, registerCommand, dispatchCommandSync,
  getVersionSync, getMaxPlayersSync, getMotdSync, getTpsSync, getBlocks,
  stringToBytesSync, bytesToStringSync, stringToBytes, bytesToString, Gzip,
  writeFileSync, readFileSync, appendFileSync, existsSync, deleteFileSync, listSync, mkdirSync, statSync, isDirectorySync,
} from 'yeow-api';

const NAME = 'e2e-tests';
const BENCH_ITERS = 5000;
const BENCH_WARMUP = 200;
const REPORT_FALLBACK_MS = 15000;

interface Result {
  name: string;
  ok: boolean;
  detail?: string;
  info?: string;
}
interface Bench extends ReturnType<typeof summarize> {
  name: string;
}

const results: Result[] = [];
const joined: string[] = [];
let bench: Bench[] = [];
let suitesDone = false;
let joinSeen = false;
let cmdSeen = false;
let reported = false;

function detail(e: unknown): string {
  const err = e as { stack?: string; message?: string };
  const msg = err?.message ? String(err.message) : '';
  return (msg ? `${msg}\n` : '') + String(err?.stack ?? e);
}
function check(name: string, fn: () => void): void {
  try { fn(); results.push({ name, ok: true }); }
  catch (e) { results.push({ name, ok: false, detail: detail(e) }); }
}
/** 带返回值/信息的断言（成功后记录 info 便于人工核对）。 */
function checkV<T>(name: string, fn: () => T, fmt?: (v: T) => string): void {
  try { const v = fn(); results.push({ name, ok: true, info: fmt ? fmt(v) : undefined }); }
  catch (e) { results.push({ name, ok: false, detail: detail(e) }); }
}
async function checkAsync(name: string, fn: () => Promise<string | void>): Promise<void> {
  try { const info = await fn(); results.push({ name, ok: true, info: info ?? undefined }); }
  catch (e) { results.push({ name, ok: false, detail: detail(e) }); }
}
function report(): void {
  if (reported) return;
  reported = true;
  const ok = results.filter((r) => r.ok).length;
  const fail = results.length - ok;
  log.info(`[YEOW-E2E] ${JSON.stringify({ plugin: NAME, ok, fail, results, bench, joined })}`);
}
function maybeReport(): void {
  if (suitesDone && joinSeen && cmdSeen) report();
}

function round(n: number, d = 3): number {
  const k = 10 ** d;
  return Math.round(n * k) / k;
}
function summarize(samples: number[]) {
  const s = samples.slice().sort((a, b) => a - b);
  const n = s.length;
  const sum = s.reduce((a, b) => a + b, 0);
  const pick = (q: number) => s[Math.min(n - 1, Math.max(0, Math.ceil(q * n) - 1))];
  return { n, mean: round(sum / n), min: round(s[0]), p50: round(pick(0.5)), p99: round(pick(0.99)), max: round(s[n - 1]) };
}
function benchOne(name: string, payload: unknown): Bench {
  for (let i = 0; i < BENCH_WARMUP; i++) $send('debug', { t: 'payload', p: payload });
  const samples = new Array<number>(BENCH_ITERS);
  for (let i = 0; i < BENCH_ITERS; i++) {
    const t0 = performance.now();
    $send('debug', { t: 'payload', p: payload });
    samples[i] = performance.now() - t0;
  }
  return { name, ...summarize(samples) };
}

async function runSuites(): Promise<void> {
  checkV('env', () => {
    const e = getEnv();
    if (typeof e.timestamp !== 'number') throw new Error('env.timestamp is not a number');
    if (typeof e.minecraftVersion !== 'string') throw new Error('env.minecraftVersion');
    if (!e.yeow || typeof e.yeow.version !== 'string') throw new Error('env.yeow.version');
    return e;
  }, (e) => `mc=${e.minecraftVersion} yeow=${e.yeow.version} arch=${e.arch}`);

  checkV('server', () => {
    const version = getVersionSync();
    const max = getMaxPlayersSync();
    const motd = getMotdSync();
    const tps = getTpsSync();
    if (typeof version !== 'string' || version.length === 0) throw new Error('server.getVersion');
    if (typeof max !== 'number' || max <= 0) throw new Error('server.getMaxPlayers');
    if (typeof motd !== 'string') throw new Error('server.getMotd');
    if (!tps || typeof tps.tps1m !== 'number') throw new Error('server.getTps');
    return { version, max, motd, tps1m: tps.tps1m };
  }, (v) => `version=${v.version} max=${v.max} tps1m=${v.tps1m} motd=${JSON.stringify(v.motd).slice(0, 40)}`);

  checkV('task', () => call<string>('server.getVersion'), (v) => `version=${v}`);
  checkV('task-batch', () => {
    const r = callBatch([{ type: 'server.getVersion' }, { type: 'server.getVersion' }]);
    if (!Array.isArray(r) || r.length !== 2 || r[0] !== r[1]) throw new Error('batch mismatch');
    return r[0] as string;
  }, (v) => `[${v}, ${v}]`);

  checkV('util-utf8', () => bytesToStringSync(stringToBytesSync('中😀')), (s) => JSON.stringify(s));
  checkV('util-gzip', () => {
    const z = Gzip.compressSync(stringToBytesSync('hello gzip'));
    return bytesToStringSync(Gzip.decompressSync(z));
  }, (s) => `${JSON.stringify(s)} (sync)`);

  await checkAsync('material', async () => {
    const blocks = await getBlocks();
    if (!Array.isArray(blocks) || blocks.length === 0) throw new Error('getBlocks() empty');
    return `${blocks.length} blocks`;
  });

  await checkAsync('util-async', async () => {
    const s = await bytesToString(await stringToBytes('中😀 async'));
    if (s !== '中😀 async') throw new Error('async utf8 roundtrip');
    const z = await Gzip.compress('hello async gzip');
    const d = await bytesToString(await Gzip.decompress(z));
    if (d !== 'hello async gzip') throw new Error('async gzip roundtrip');
    return 'utf8 + gzip (async)';
  });

  checkV('fs', () => {
    writeFileSync('e2e.txt', 'value-123');
    appendFileSync('e2e.txt', '-appended');
    if (readFileSync('e2e.txt', 'utf8') !== 'value-123-appended') throw new Error('readFile/appendFile');
    mkdirSync('e2e-dir');
    if (!isDirectorySync('e2e-dir')) throw new Error('isDirectory');
    const st = statSync('e2e.txt');
    if (typeof st.size !== 'number' || st.size <= 0) throw new Error('stat.size');
    writeFileSync('e2e-dir/x.txt', 'x');
    const list = listSync('e2e-dir');
    if (!Array.isArray(list) || !list.includes('x.txt')) throw new Error('list');
    deleteFileSync('e2e-dir/x.txt');
    deleteFileSync('e2e.txt');
    if (existsSync('e2e.txt')) throw new Error('delete');
    return st.size;
  }, (size) => `size=${size}, dir/list/delete ok`);

  checkV('debug-payload', () => {
    const p = { type: 'world.setBlock', params: { world: 'world', x: 100, y: 60, z: 100, blockType: 'minecraft:air' } };
    const echo = $send('debug', { t: 'payload', p });
    if (JSON.stringify(echo) !== JSON.stringify(p)) throw new Error('echo mismatch');
    return Object.keys(p.params).length;
  }, (n) => `${n} params echoed`);

  await checkAsync('command', async () => {
    let got: { label: string; args: string[]; sender: string } | null = null;
    registerCommand('e2ecmd', {
      description: 'e2e command',
      executor: (payload) => {
        got = {
          label: payload.label,
          args: payload.args,
          sender: payload.sender === 'CONSOLE' ? 'CONSOLE' : (payload.sender as Player).name,
        };
        cmdSeen = true;
        maybeReport();
      },
    });
    dispatchCommandSync('e2ecmd hello world');
    // 命令执行在服务端线程，回调异步回到 JS——等待至多 5s。
    for (let i = 0; i < 50 && !got; i++) await new Promise((r) => setTimeout(r, 100));
    if (!got) throw new Error('command executor not invoked');
    const g = got as { label: string; args: string[]; sender: string };
    if (g.label !== 'e2ecmd' || g.args.join(',') !== 'hello,world') throw new Error(`unexpected payload ${JSON.stringify(g)}`);
    return `label=${g.label} args=[${g.args.join(',')}] sender=${g.sender}`;
  });

  bench = [
    benchOne('empty', {}),
    benchOne('nested', {
      id: 'a1b2c3d4-e5f6', n: 12345, f: 3.5, flag: true, nil: null,
      arr: [1, 2, 3, 4, 5, 6, 7, 8], obj: { a: 'x', b: 'y', c: { d: 'z' } },
    }),
    benchOne('flat8', { a: 1, b: 2, c: 3, d: 'x', e: 'y', f: true, g: false, h: null }),
    benchOne('array x100', Array.from({ length: 100 }, (_, i) => i)),
    benchOne('string 1KB', 'a'.repeat(1024)),
    benchOne('world.setBlock', { type: 'world.setBlock', params: { world: 'world', x: 100, y: 60, z: 100, blockType: 'minecraft:air' } }),
  ];

  suitesDone = true;
  maybeReport();
}

onLoad(() => {
  eventOn('playerJoin', (e) => {
    checkV('player.join', () => {
      const name = e.player?.name;
      if (typeof name !== 'string' || name.length === 0) throw new Error('player.name unavailable');
      joined.push(name);
      return name;
    }, (n) => n);
    checkV('player.get', () => {
      const name = joined[joined.length - 1];
      const p = Player.getSync(name);
      if (!p || p.name !== name) throw new Error('Player.getSync mismatch');
      return p.uuid;
    }, (uuid) => `uuid=${uuid}`);
    check('player.message', () => {
      const p = Player.getSync(joined[joined.length - 1]);
      if (!p) throw new Error('player unavailable');
      p.sendMessage(`E2E-MSG:${joined[joined.length - 1]}`);
    });
    joinSeen = true;
    maybeReport();
  });

  void runSuites();

  // 兜底：若部分信号未到达，超时后按已有结果上报（缺失项记为失败）。
  setTimeout(() => {
    if (reported) return;
    if (!joinSeen) results.push({ name: 'player.join', ok: false, detail: `no player joined within ${REPORT_FALLBACK_MS}ms` });
    if (!cmdSeen) results.push({ name: 'command', ok: false, detail: `command not dispatched within ${REPORT_FALLBACK_MS}ms` });
    report();
  }, REPORT_FALLBACK_MS);
});
