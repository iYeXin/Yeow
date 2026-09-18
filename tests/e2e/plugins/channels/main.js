// e2e 通道测试插件（纯 JS）：util（UTF-8/gzip）、fs（plugin 级读写）、task 批量、
// env、默认权限拒绝。结果以 [YEOW-E2E] {json} 哨兵输出。
(() => {
  const NAME = 'e2e-channels';
  const results = [];

  const assert = (cond, msg) => { if (!cond) throw new Error(msg ?? 'assertion failed'); };
  const eq = (a, b, msg) => {
    if (JSON.stringify(a) !== JSON.stringify(b)) {
      throw new Error(`${msg ? msg + ': ' : ''}expected ${JSON.stringify(b)}, got ${JSON.stringify(a)}`);
    }
  };
  const call = (channel, payload) => {
    const r = $send(channel, payload);
    if (r && typeof r === 'object' && r.err) throw new Error(`${channel}: ${r.err}`);
    return r;
  };
  const check = (name, fn) => {
    try {
      fn();
      results.push({ name, ok: true });
    } catch (e) {
      let detail = String(e?.message ?? e);
      if (e?.stack) detail += `\n${e.stack}`;
      results.push({ name, ok: false, detail });
    }
  };

  const run = () => {
    check('util-utf8', () => {
      const enc = call('util', { t: 'encode.utf8', p: { data: '中😀' } });
      assert(typeof enc?.data === 'string', 'encode.utf8 returned data');
      eq('中😀', call('util', { t: 'decode.utf8', p: { data: enc.data } }).data, 'utf8 roundtrip');
    });

    check('util-gzip', () => {
      const enc = call('util', { t: 'encode.utf8', p: { data: 'hello gzip' } }).data;
      const z = call('util', { t: 'gzip.compress', p: { data: enc, level: 6, raw: false } }).data;
      assert(typeof z === 'string' && z.length > 0, 'gzip output');
      const d = call('util', { t: 'gzip.decompress', p: { data: z, raw: false } }).data;
      eq('hello gzip', call('util', { t: 'decode.utf8', p: { data: d } }).data, 'gzip roundtrip');
    });

    check('fs-write-read', () => {
      call('fs', { t: 'plugin.writeFile', p: { path: 'e2e.txt', data: 'value-123' } });
      eq('value-123', call('fs', { t: 'plugin.readFile', p: { path: 'e2e.txt' } }).data, 'readFile');
    });

    check('fs-exists-list-delete', () => {
      call('fs', { t: 'plugin.writeFile', p: { path: 'e2e-tmp.txt', data: 'x' } });
      eq(true, call('fs', { t: 'plugin.exists', p: { path: 'e2e-tmp.txt' } }), 'exists true');
      assert(Array.isArray(call('fs', { t: 'plugin.list', p: { path: '.' } })), 'list is array');
      call('fs', { t: 'plugin.delete', p: { path: 'e2e-tmp.txt' } });
      eq(false, call('fs', { t: 'plugin.exists', p: { path: 'e2e-tmp.txt' } }), 'exists false after delete');
    });

    check('task-batch', () => {
      const r = call('task', { tasks: [{ type: 'server.getVersion' }, { type: 'server.getVersion' }] });
      assert(Array.isArray(r) && r.length === 2, 'batch result length');
      assert(typeof r[0] === 'string' && r[0] === r[1], 'batch values');
    });

    check('permission-denied', () => {
      // fs:server.* 默认拒绝；应返回 {err}，且插件代码不因此崩溃。
      const r = $send('fs', { t: 'server.list', p: { path: '.' } });
      assert(r && typeof r === 'object' && typeof r.err === 'string', 'expected {err} for fs:server.*');
    });

    const ok = results.filter((r) => r.ok).length;
    const fail = results.length - ok;
    $send('log', {
      level: fail ? 'ERROR' : 'INFO',
      message: `[YEOW-E2E] ${JSON.stringify({ plugin: NAME, ok, fail, results })}`,
    });
  };

  (globalThis.__yeowLoadCbs ?? (globalThis.__yeowLoadCbs = [])).push(run);
})();
