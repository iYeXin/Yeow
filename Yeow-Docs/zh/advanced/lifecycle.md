# 生命周期与热重载

> 生命周期钩子（onInit/onLoad/onUnload）与触发时序；热重载机制；生产环境 /yeow reload / unload。

## 回调系统

### 统一回调消息

所有 Java→JS 的回调（事件、补全、异步结果）使用同一消息格式：

```json
{"t":"cb", "p":"cb_42", "r":{...}}
```

- `t` — 固定 `"cb"`
- `p` — callbackId，由 `_registerCallback` 生成（格式 `"cb_N"`）
- `r` — 回调数据，内容因场景而异

JS 端 `_hm` 函数只有一个回调处理分支：

```js
if (t === 'cb' || t === 'CALLBACK') {
    const e = _cbs[p];
    if (e) { e.h(r); if (!e.persistent) delete _cbs[p]; }
}
```

Java 端通过 `SyncCallbackHelper` 注册等待，JS 通过 `$_send('task')` 发回响应：

| 场景      | Java 发送                                   | JS 响应                                                                         |
| --------- | ------------------------------------------- | ------------------------------------------------------------------------------- |
| 事件      | `{t:"cb", p:cbId, eventId, r:{event data}}` | `$send('task', {type:'event.complete', params:{eventId, mods}})`                |
| 补全      | `{t:"cb", p:cbId, r:{sender, args}}`        | `$send('task', {type:'command.tabComplete', params:{callbackId, completions}})` |
| 异步 post | `{t:"cb", p:cbId, r:result}`                | 自动 — 回调函数处理 `r`                                                         |

响应消息均经过 Scheduler 的 `Tasks.execute()` → `SyncCallbackHelper.complete()`，不新增 JNI 函数。

### 事件注册

`eventOn()` 在 yeow-api 内部调用 `_registerCallback(fn, {persistent:true})` 注册回调，并将生成的 `cbId` 通过 `$_send('task', {type:'event.subscribe', params:{callbackId, eventType}})` 发送到 Java 端。Java 的 `EventBridge.subs` 维护 `eventType → plugin → cbId` 映射：

```
subs = {
  "blockBreak": { "myPlugin": "cb_42", "otherPlugin": "cb_43" },
  "playerJoin": { "myPlugin": "cb_44" }
}
```

事件触发时，EventBridge 通过 `subs[eventType][plugin]` 获取对应的 `cbId`，直接发送 `{t:"cb", p:cbId, r:data}`。不需要任何前缀或编码。对于每个插件，回调注册表 `_cbs` 中的条目会被调用，触发用户的事件处理器。

### 补全器注册

`registerCommand()` 内 completer 同样通过 `_registerCallback` 注册回调，`cmdId` 通过 `command.register` 任务传给 Java 端 `CommandTasks`。用户在 `complete(result)` 中传入补全结果。

```js
import { onInit, onLoad, onUnload } from 'yeow-api';

onInit(() => {
    // 在 JS 线程消息循环开始后立刻执行
    // 可以注册命令/事件，但不应该操作游戏（不保证执行时是否可用）
});

onLoad(() => {
    // Bukkit onEnable 后通过消息循环触发
    // 可以调所有游戏操作
});

onUnload(() => {
    // 插件禁用或热重载时执行
    // 清理资源、保存数据等
});
```

### 触发时序

| 钩子       | 触发时机                  | 游戏 API 可用 |
| ---------- | ------------------------- | :-----------: |
| `onInit`   | JS 上下文创建、代码加载后 |       ❌       |
| `onLoad`   | Bukkit `onEnable` 后      |       ✅       |
| `onUnload` | 插件禁用或热重载          |       ✅       |

### 热重载

当 dev-server 检测到文件变化时：

```
dev-server → WebSocket hot-reload → Java 主线程
  │
  ├─ command.unregisterAll      ← 清理旧命令
  ├─ eventBridge.unsubscribeAll ← 清理旧事件
  ├─ purgePluginServices        ← 清理旧服务（含 native 子进程）
  └─ pt.reload(newCode)         ← 阻塞等待，最多 5s
       │
       ├─ 发送 RELOAD → JS 队列 → 等待 JS 线程自然退出
       │    ├─ _hm → onUnload 回调
       │    ├─ $send('lifecycle', {type:'unloadDone'})
       │    └─ running = false → 消息循环退出 → 旧上下文销毁
       │
       ├─ 超时未退出 → 强制终止（running=false + ctx.destroy）
       │
       ├─ 清理旧 timer / io / http / 残留任务
       ├─ 清空消息队列
       └─ start() → 新线程 → 新上下文 → 新代码
```

热重载在主线程上同步等待（最多 5s），期间不影响其他 Yeow 插件。

### 生产环境 reload / unload

`/yeow reload` 与 `/yeow unload` 使用与开发模式热重载**相同的卸载步骤**（5s 强制终止）：

```
/yeow unload <plugin|all>        /yeow reload <plugin|all> [path]
        │                                │
        └── unloadPlugin(name)           └── unloadPlugin(name) → registerPlugin(原路径或新 path)
              │
              ├─ command.unregisterAll      ← 清理旧命令
              ├─ eventBridge.unsubscribeAll ← 清理旧事件
              ├─ purgePluginServices        ← 清理旧服务
              ├─ pt.stopAndWait()           ← DISABLE + 5s 等待 + 强制终止
              └─ plugins.remove(name)       ← 移出注册表
```

- `/yeow reload my-plugin` — 从原路径（JAR 或 zip 路径）重新读取磁盘上的包
- `/yeow reload my-plugin plugins/Yeow/other.yeow.zip` — 从新来源加载（URL 亦可，临时不持久化）
- `/yeow reload all` — 全部按原路径重载
- `/yeow unload <plugin|all>` — 卸载（5s 强制终止）
- `/yeow uninstall <plugin>` — 卸载并把 `plugins/Yeow/` 下同名 `.yeow.zip` 移入 `plugins/Yeow/.backup/`（数据目录需手动清理）
- `/yeow load <path|url>` — 临时加载（URL 下载到缓存，重启不保留）
- `/yeow install <url>` — 下载安装到 `plugins/Yeow/<name>-<version>.yeow.zip`（标准格式，下次启动自动扫描）
- `/yeow update <url>` — 扫描 `plugins/Yeow/` 按 `yeow.json` 的 `name` 匹配旧包，旧包移入 `plugins/Yeow/.backup/`，写入新版本；插件运行中则自动重载
- 同名插件在任何场景下重复加载（自动扫描 / 命令 / 模板 JAR）都会被拒绝并输出警告；**同时部署模板 JAR 与 `.yeow.zip` 会产生该冲突，需手动移除其一**
