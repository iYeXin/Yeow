# 事件与回调

> 事件桥（EventBridge）：并发/串行模式、事件数据、处理器操作与模式选择；统一回调系统。

### EventBridge

```
Bukkit 事件触发
  → EventBridge: 检查 JS 订阅
  → eventData() 提取字段（基本类型，无 Bukkit 引用）
  → SyncCallbackHelper.register(cbId)
  → queue.sendJs({t:"cb", p:cbId, r:{事件数据}}) → JS 线程
  → 主线程自旋等待:
      while (!pend.isDone()) {
          runtime.getScheduler().tick();
      }
  → JS $hm → _hm → _cbs[cbId].h(r)
  → yeow-api 回调内:
      自动模式:
        同步 handler: 执行完 → $send('task', {type:'event.complete', params:{eventId, mods}})
        返回 Promise: → 立即 $send('event.complete')，只有同步段修改生效
      手动模式:
        handler(e, complete) → 用户调用 complete(mods)
  → Scheduler → Tasks.execute('event.complete')
  → SyncCallbackHelper.complete(cbId, mods)
  → applyMods(): if (cancelled) event.setCancelled(true)
```

### 并发事件处理

当多个插件订阅同一事件时，Yeow 支持串行（默认）和并发两种模式：

| 模式           | 行为                                           | runtime/config.yml 配置           |
| -------------- | ---------------------------------------------- | --------------------------------- |
| 串行           | 逐个发送事件给每个插件，等待一个完成再发下一个 | `concurrent-events: false`        |
| 并发（实验性） | 同时发送给所有订阅插件，等待最慢的完成         | `concurrent-events: true`（默认） |

并发模式下，每个插件使用独立的 callbackId，`CountDownLatch` 等待所有插件完成。事件数据对所有插件共享同一份快照。由于游戏操作通过调度器串行化，不会产生竞态。`cancelled` 合并策略：任一插件取消则取消。

### 事件数据

所有事件字段是基本类型（string/number/boolean/object），JS 端 yeow-api 的 `adaptEvent()` 自动包装：
- `player` UUID → `Player.getSync(uuid)` 对象（同步转换，不影响事件处理）
- `from`/`to`/`respawnLocation` → `Location` 对象

### 事件处理器中的操作

```js
// 自动模式（默认）
eventOn('blockBreak', (e) => {
    // ✅ 同步 call — 自旋循环会处理
    const p = Player.getSync(e.player);

    // ✅ 取消事件 — 即时生效
    e.cancelled = true;

    // ✅ 异步操作（事件已释放，但异步 API 调用不受影响）
    post('player.sendMessage', {...});
});

// async handler — 返回 Promise 即立即 complete
eventOn('blockBreak', async (e) => {
    e.cancelled = true;  // ✅ 同步段生效
    await someTask();    // 事件已结束，此后设值无效
    e.cancelled = false; // ❌ 不生效
});

// 手动模式 — 完全控制 complete 时机
eventOn('blockBreak', { manualRelease: true }, (e, complete) => {
    doAsyncCheck(result => {
        e.cancelled = result;
        complete({ cancelled: result });
    });
});
```

### 事件处理模式选择

自动模式下，事件处理器返回 Promise 时立即释放事件。这意味着 async handler 中的 `await` 之后设置 `event.cancelled` 无效。

**但这不意味着 async handler 没有价值。** 绝大多数事件的用途是**触发逻辑**而非**修改结果**：

```
eventOn('playerJoin', async (e) => {
    // 查询数据库 → 发送欢迎消息 → 记录日志
    // 这些操作不需要阻塞主线程等待
    const msg = await db.getWelcome(e.player.uuid);
    e.player.sendMessage(msg);
    log.info(e.player.name + ' joined');
});
```

上面这个例子中，事件并不需要被取消，也不需要返回任何 mods。async handler 让插件自由使用异步 API，**不阻塞主线程自旋**。这正是推荐的做法：

| 场景                                     | 推荐模式                | 原因                                      |
| ---------------------------------------- | ----------------------- | ----------------------------------------- |
| 仅需触发逻辑（发消息、改数据、记录日志） | 自动模式 + async        | 不阻塞主线程，代码简洁                    |
| 需要同步决定结果（取消、修改掉落等）     | 自动模式 + 同步 handler | `await` 前设值即可                        |
| 需要异步获取数据后决定结果               | 手动模式 + `complete()` | 由用户控制 `$send('event.complete')` 时机 |

> **规则**：如果你的事件处理器逻辑不需要阻塞主线程等待结果，大胆使用 async。主线程自旋等待 JS 结果期间无法处理 tick、AI、物理等，长时间自旋会影响服务器性能。
