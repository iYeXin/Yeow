# 环境能力与通道

> 环境能力注入：$_send 底层桥、$send 封装、各通道说明、运行时配置（plugins/Yeow/runtime/config.yml）。

## 环境能力注入

PluginThread 在 JS 上下文只注册一个底层函数：

| 函数                          | 签名                                 | 说明                                   |
| ----------------------------- | ------------------------------------ | -------------------------------------- |
| `$_send(channel, jsonString)` | `(string, string) => string \| null` | JS→Java 唯一通信入口，返回 JSON 字符串 |

支持的消息通道：

| 通道        | 用途                                   | 处理位置              |
| ----------- | -------------------------------------- | --------------------- |
| `task`      | 游戏任务（请求/获取方块/传送等）       | 主线程调度器          |
| `timer`     | 定时器（setTimeout/setInterval）       | 插件线程 Timer 线程池 |
| `fs`        | 文件系统读写                           | 插件线程直接处理      |
| `http`      | HTTP 服务器/客户端                     | 插件线程直接处理      |
| `assets`    | 插件内置资源读取                       | 插件线程直接处理      |
| `service`   | 服务注册/请求/订阅/发布                | ServiceManager        |
| `debug`     | 错误上报 / 心跳 ping-pong              | 插件线程直接处理      |
| `log`       | 控制台日志（自动添加 `[插件名]` 前缀） | 插件线程直接处理      |
| `env`       | 运行时环境信息 + 微秒时间戳            | 插件线程直接处理      |
| `dir`       | 插件数据目录路径                       | 插件线程直接处理      |
| `lifecycle` | 生命周期确认（unloadDone）             | 插件线程直接处理      |

### `$send` 封装层

init.js 在 `$_send` 基础上封装了 `$send(channel, payload)`，自动做 JSON 转换：

```js
// $_send 直接使用需手动 JSON
$_send('task', JSON.stringify({type: 'player.get', params: {identifier: 'uuid'}}));

// $send 自动处理 JSON
$send('task', {type: 'player.get', params: {identifier: 'uuid'}});
```

### 通道说明

**task 通道** — 游戏任务，走主线程调度器：

```json
{
    "type": "player.get",
    "params": {"identifier": "uuid"},
    "cb": "cb_1",         // 异步回调 ID（可选）
    "priority": "high"     // 优先级（可选）
}
```

同步任务（无 `cb`）阻塞等待结果，异步任务（有 `cb`）通过回调 Promise resolve。

**timer 通道** — 替代 `$timeout`/`$interval`：

```json
{"type": "timeout", "cb": "cb_1", "delay": 1000}
{"type": "interval", "cb": "cb_2", "delay": 5000}
```

**log 通道** — 控制台日志自动添加 `[插件名]` 前缀：

```js
console.log('hello');     // → [MyPlugin] hello
console.warn('warning');  // → [MyPlugin] warning
```

**reportError 通道** — 手动上报错误到 dev-server source-map 解析：

```js
import { logError } from 'yeow-api';
try { riskyOp(); } catch (e) { logError(e, 'custom context'); }
```

**lifecycle 通道** — 生命周期确认（插件内部使用）：

```
$send('lifecycle', {type: 'unloadDone'})    // 禁用或热重载完成确认
```

JS 端在 `onUnload` 回调执行完毕后通过 `$send('lifecycle')` 向 Java 端确认。收到确认后 Java 端设置 `running = false`，消息循环自然退出。

### 封装层

init.js 将 `$_send` 封装为标准 JS API：

```
$_send(channel, jsonString)   ← 唯一Java原生函数
    ↓
init.js 封装层
    ├── $send(channel, object)   ← 自动 JSON 转换
    ├── console.log/warn/error   ← 自动添加 [pluginName] 前缀
    ├── setTimeout / clearTimeout
    ├── setInterval / clearInterval
    ├── fetch                    ← HTTP fetch (Promise)
    └── $hm                      ← 消息分发器
```

## 运行时配置

`plugins/Yeow/runtime/config.yml`（首次启动自动生成）的完整参考（含 Folia 节与预警配置）见 [运行时运维 - 运行时配置](/operations#运行时配置)；预警阈值与动态扩容见 [运行时警告指南](/runtime-warning)。
