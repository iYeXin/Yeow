// e2e 通信层性能基准（full 层）：基于 debug.payload 的同步往返。
// 规模：每载荷 200 预热 + 5000 采样，全部载荷约 1s。
// 结果随 [YEOW-E2E] 报告以 `bench` 数组回传；另有一条宽松上限断言用于回归。
(() => {
  const NAME = 'e2e-bench';
  const WARMUP = 200;
  const ITERS = 5000;
  const SANITY_MAX_MEAN_MS = 10; // 宽松上限：仅捕捉数量级退化，避免环境抖动误报

  const round = (n, d = 3) => { const k = 10 ** d; return Math.round(n * k) / k; };

  const summarize = (samples) => {
    const s = samples.slice().sort((a, b) => a - b);
    const n = s.length;
    const sum = s.reduce((a, b) => a + b, 0);
    const pick = (q) => s[Math.min(n - 1, Math.max(0, Math.ceil(q * n) - 1))];
    return { n, mean: round(sum / n), min: round(s[0]), p50: round(pick(0.5)), p99: round(pick(0.99)), max: round(s[n - 1]) };
  };

  const bench = (name, payload) => {
    for (let i = 0; i < WARMUP; i++) $send('debug', { t: 'payload', p: payload });
    const samples = new Array(ITERS);
    for (let i = 0; i < ITERS; i++) {
      const t0 = performance.now();
      $send('debug', { t: 'payload', p: payload });
      samples[i] = performance.now() - t0;
    }
    return { name, ...summarize(samples) };
  };

  const run = () => {
    const payloads = [
      ['empty', {}],
      ['nested', {
        id: 'a1b2c3d4-e5f6', n: 12345, f: 3.5, flag: true, nil: null,
        arr: [1, 2, 3, 4, 5, 6, 7, 8], obj: { a: 'x', b: 'y', c: { d: 'z' } },
      }],
      ['flat8', { a: 1, b: 2, c: 3, d: 'x', e: 'y', f: true, g: false, h: null }],
      ['array x100', Array.from({ length: 100 }, (_, i) => i)],
      ['string 1KB', 'a'.repeat(1024)],
      ['world.setBlock', { type: 'world.setBlock', params: { world: 'world', x: 100, y: 60, z: 100, blockType: 'minecraft:air' } }],
    ];

    let detail = null;
    let benchRows = [];
    try {
      benchRows = payloads.map(([name, payload]) => bench(name, payload));
    } catch (e) {
      detail = String(e?.message ?? e);
    }

    const over = benchRows.filter((b) => b.mean > SANITY_MAX_MEAN_MS);
    const ok = detail == null && over.length === 0 ? 1 : 0;
    const results = [{
      name: 'bench-sanity',
      ok: ok === 1,
      detail: detail ?? (over.length ? `mean > ${SANITY_MAX_MEAN_MS}ms: ${over.map((b) => `${b.name}=${b.mean}`).join(', ')}` : undefined),
    }];

    $send('log', {
      level: ok ? 'INFO' : 'ERROR',
      message: `[YEOW-E2E] ${JSON.stringify({ plugin: NAME, ok, fail: ok ? 0 : 1, results, bench: benchRows })}`,
    });
  };

  (globalThis.__yeowLoadCbs ?? (globalThis.__yeowLoadCbs = [])).push(run);
})();
