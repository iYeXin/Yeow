// e2e 测试插件（TypeScript + yeow-api，经 create-yeow 的 build.js 构建）。
// 覆盖：env / task（同步 + 批量）/ util（UTF-8、gzip）/ fs / debug.payload 往返 /
// 通信层基准（debug.payload 同步往返）/ 假玩家加入（playerJoin + player.get）。
// 结果以单行 JSON 哨兵 [YEOW-E2E] {...} 经 log 通道输出。
import {
  onLoad, log, getEnv, Player, eventOn, call, callBatch,
  stringToBytesSync, bytesToStringSync, Gzip,
  writeFileSync, readFileSync, existsSync, deleteFileSync, listSync,
} from 'yeow-api';

const NAME = 'e2e-tests';
const PLAYER_WAIT_MS = 25000;
const BENCH_ITERS = 5000;
const BENCH_WARMUP = 200;

interface Result {
  name: string;
  ok: boolean;
  detail?: string;
}
interface Bench extends ReturnType<typeof summarize> {
  name: string;
}

const results: Result[] = [];
const joined: string[] = [];
let bench: Bench[] = [];
let reported = false;

function detail(e: unknown): string {
  const err = e as { stack?: string; message?: string };
  const msg = err?.message ? String(err.message) : '';
  return (msg ? `${msg}\n` : '') + String(err?.stack ?? e);
}
function check(name: string, fn: () => void): void {
  try {
    fn();
    results.push({ name, ok: true });
  } catch (e) {
    results.push({ name, ok: false, detail: detail(e) });
  }
}
function report(): void {
  if (reported) return;
  reported = true;
  const ok = results.filter((r) => r.ok).length;
  const fail = results.length - ok;
  log.info(`[YEOW-E2E] ${JSON.stringify({ plugin: NAME, ok, fail, results, bench, joined })}`);
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

function runSuites(): void {
  check('env', () => {
    const e = getEnv();
    if (typeof e.timestamp !== 'number') throw new Error('env.timestamp is not a number');
    if (typeof e.minecraftVersion !== 'string') throw new Error('env.minecraftVersion');
    if (!e.yeow || typeof e.yeow.version !== 'string') throw new Error('env.yeow.version');
  });
  check('task', () => {
    const v = call<string>('server.getVersion');
    if (typeof v !== 'string' || v.length === 0) throw new Error('server.getVersion');
  });
  check('task-batch', () => {
    const r = callBatch([{ type: 'server.getVersion' }, { type: 'server.getVersion' }]);
    if (!Array.isArray(r) || r.length !== 2 || r[0] !== r[1]) throw new Error('batch mismatch');
  });
  check('util-utf8', () => {
    if (bytesToStringSync(stringToBytesSync('中😀')) !== '中😀') throw new Error('utf8 roundtrip');
  });
  check('util-gzip', () => {
    const z = Gzip.compressSync(stringToBytesSync('hello gzip'));
    if (bytesToStringSync(Gzip.decompressSync(z)) !== 'hello gzip') throw new Error('gzip roundtrip');
  });
  check('fs', () => {
    writeFileSync('e2e.txt', 'value-123');
    if (readFileSync('e2e.txt', 'utf8') !== 'value-123') throw new Error('readFile');
    writeFileSync('e2e-tmp.txt', 'x');
    if (!existsSync('e2e-tmp.txt') || !Array.isArray(listSync('.'))) throw new Error('exists/list');
    deleteFileSync('e2e-tmp.txt');
    if (existsSync('e2e-tmp.txt')) throw new Error('delete');
  });
  check('debug-payload', () => {
    const p = { type: 'world.setBlock', params: { world: 'world', x: 100, y: 60, z: 100, blockType: 'minecraft:air' } };
    const echo = $send('debug', { t: 'payload', p });
    if (JSON.stringify(echo) !== JSON.stringify(p)) throw new Error('echo mismatch');
  });
}

onLoad(() => {
  eventOn('playerJoin', (e) => {
    check('player.join', () => {
      const name = e.player?.name;
      if (typeof name !== 'string' || name.length === 0) throw new Error('player.name unavailable');
      joined.push(name);
    });
    check('player.get', () => {
      const name = joined[joined.length - 1];
      const p = Player.getSync(name);
      if (!p || p.name !== name) throw new Error('Player.getSync mismatch');
    });
    report();
  });

  runSuites();
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

  // 假玩家未在窗口内加入：以失败上报，避免 harness 等到超时。
  setTimeout(() => {
    if (joined.length === 0) {
      results.push({ name: 'player.join', ok: false, detail: `no player joined within ${PLAYER_WAIT_MS}ms` });
      report();
    }
  }, PLAYER_WAIT_MS);
});
