// e2e 假玩家测试插件（纯 JS）：订阅 player.join，校验事件载荷并可经 player.get 取名字。
// 结果以 [YEOW-E2E] {json} 哨兵输出（含 joined 列表，供 harness 与假玩家用户名交叉校验）。
(() => {
  const NAME = 'e2e-players';
  const results = [];
  const joined = [];
  let reportTimer = null;

  const detail = (e) => {
    let d = String(e?.message ?? e);
    if (e?.stack) d += `\n${e.stack}`;
    return d;
  };
  const check = (name, fn) => {
    try { fn(); results.push({ name, ok: true }); }
    catch (e) { results.push({ name, ok: false, detail: detail(e) }); }
  };
  const report = () => {
    const ok = results.filter((r) => r.ok).length;
    const fail = results.length - ok;
    $send('log', {
      level: fail ? 'ERROR' : 'INFO',
      message: `[YEOW-E2E] ${JSON.stringify({ plugin: NAME, ok, fail, results, joined })}`,
    });
  };
  // 事件必须回 event.complete 释放（否则事件桥等待至超时，阻塞玩家加入）。
  const release = (data) => {
    const eventId = data?._eventId;
    if (eventId != null) {
      try { $send('task', { type: 'event.complete', params: { eventId, mods: {} }, cb: '' }); } catch { /* ignore */ }
    }
  };

  const onJoin = (data) => {
    const uuid = data?.player;
    check('player.join', () => {
      if (typeof uuid !== 'string' || !uuid) throw new Error('missing player uuid in event payload');
    });
    check('player.get', () => {
      const p = $send('task', { type: 'player.get', params: { identifier: uuid } });
      if (!p || typeof p.name !== 'string') throw new Error('player.get returned no name');
      joined.push(p.name);
    });
    release(data);
    // 多客户端时按最后一次加入去抖后统一上报。
    if (reportTimer) clearTimeout(reportTimer);
    reportTimer = setTimeout(report, 800);
  };

  (globalThis.__yeowLoadCbs ?? (globalThis.__yeowLoadCbs = [])).push(() => {
    const cbId = _registerCallback(onJoin, { persistent: true });
    $send('task', {
      type: 'event.subscribe',
      params: { pluginName: NAME, eventType: 'playerJoin', callbackId: String(cbId) },
    });
  });
})();
