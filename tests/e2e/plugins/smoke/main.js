// e2e 冒烟测试插件（纯 JS，无构建步骤）：在 LOAD 阶段跑断言，并把结果以
// 单行 JSON 哨兵 [YEOW-E2E] {...} 经 log 通道输出，供 harness 解析。
(() => {
  const NAME = 'e2e-smoke';
  const results = [];

  const assert = (cond, msg) => { if (!cond) throw new Error(msg ?? 'assertion failed'); };
  const eq = (a, b, msg) => {
    if (JSON.stringify(a) !== JSON.stringify(b)) {
      throw new Error(`${msg ? msg + ': ' : ''}expected ${JSON.stringify(b)}, got ${JSON.stringify(a)}`);
    }
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
    check('send-defined', () => assert(typeof $send === 'function', '$send missing'));

    check('plugin-meta', () => {
      assert(typeof __plugin === 'object' && __plugin, '__plugin missing');
      eq(NAME, __plugin.name, '__plugin.name');
      assert(typeof __plugin.version === 'string', '__plugin.version');
    });

    check('env-channel', () => {
      const env = $send('env', {});
      assert(env && typeof env === 'object', 'env not an object');
      assert(typeof env.minecraftVersion === 'string', 'minecraftVersion');
      assert(env.yeow && typeof env.yeow.version === 'string', 'yeow.version');
      assert(typeof env.pluginDir === 'string', 'pluginDir');
      assert(typeof env.timestamp === 'number', 'timestamp');
    });

    check('task-sync', () => {
      const v = $send('task', { type: 'server.getVersion' });
      assert(typeof v === 'string' && v.length > 0, `server.getVersion -> ${v}`);
    });

    check('debug-payload-roundtrip', () => {
      const payload = {
        type: 'world.setBlock',
        params: { world: 'world', x: 100, y: 60, z: 100, blockType: 'minecraft:air' },
      };
      eq(payload, $send('debug', { t: 'payload', p: payload }), 'echo mismatch');
    });

    check('debug-payload-array', () => {
      const arr = Array.from({ length: 200 }, (_, i) => i);
      eq(arr, $send('debug', { t: 'payload', p: arr }), 'array echo mismatch');
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
