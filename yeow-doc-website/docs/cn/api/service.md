# Service API

插件间通信和原生能力扩展。

```ts
import {
  Service, PluginService, NativeService, ServiceReply,
  registerService, registerNativeService, getService, hasService,
} from 'yeow-api';
```

## 三种场景（按复杂度）

按"服务由谁提供、你的包扮演什么角色"分为三种，复杂度递增。多数情况只需前两种——**不需要**一上来就考虑"公共服务的自包含 / 唯一实例降级"（那是场景 3 的问题）。

| 场景                                | 服务来源 | 你的包扮演        | 复杂度 | 典型                   |
| ----------------------------------- | -------- | ----------------- | :----: | ---------------------- |
| **1. 插件包自身提供公共服务**       | 本插件   | 服务方（+调用方） |   低   | 经济插件核心           |
| **2. 依赖包只封装调用与事件监听**   | 其他插件 | 纯调用方          |   中   | 调用方 SDK             |
| **3. 依赖包内嵌服务并对外提供接口** | 本依赖包 | 服务方 + 调用方   |   高   | 全局唯一的公共服务 SDK |

> 场景 1、2 的服务提供者是唯一且明确的，不涉及"唯一实例"问题；只有场景 3 需要下面的自包含规则。

### 场景 1：插件包自身提供公共服务

最直接：插件在 `onLoad` 里注册服务，之后用返回的 `PluginService` 句柄请求 / 发布 / 订阅（参考下文「示例」中的经济插件核心）。

```ts
import { PluginService, registerService, onLoad } from 'yeow-api';

let economy: PluginService;

onLoad(async () => {
  economy = await registerService('myplugin.economy.v1', (path, body) => {
    switch (path) {
      case '/balance': return { balance: db.balance(body.player) };
      case '/deposit':
        db.deposit(body.player, body.amount);
        economy.publish('deposit', body);   // 服务端内部发布事件
        return { ok: true };
      default: return { err: 'unknown path' };
    }
  });
});

// 本插件内部直接用句柄；其他插件用 getService('myplugin.economy.v1') 调用
// await (await economy.request('/balance', { body: { player } })).json()
```

- 服务随插件生命周期：卸载 / 热重载时自动清理。
- 无需 token 隔离、无需降级——本插件是唯一提供者。

### 场景 2：依赖包只封装调用与事件监听

依赖包（会被多个插件引入）只做**调用方**：用 `getService` 取消费者句柄，封装 `request` / `subscribe`；不注册、不持有 token、不能 `publish`。

```ts
import { Service, getService } from 'yeow-api';

const SERVICE_ID = 'iyexin.economy.v1';
let svc: Service;

export async function initEconomyClient() {
  svc = await getService(SERVICE_ID);   // 服务不存在 → 抛错（error.serviceId）
}

export async function balance(player: string): Promise<number> {
  return (await svc.request('/balance', { body: { player } })).json();
}

export function onDeposit(handler: (body: any) => void) {
  return svc.subscribe('deposit', handler);
}
```

- 服务必须由其他插件提供；`getService` 在服务不存在时抛错。
- 消费者句柄无 token：不能 `publish`，也不能 `unregister` 插件服务。

### 场景 3：依赖包内嵌服务并对外提供接口（最复杂）

依赖包**同时**注册服务（服务方）并向使用者暴露调用接口（调用方）。由于 public 服务**全局唯一**，这里才需要"自包含设计"。

**服务与调用者的界限必须分明：**

| 角色                 | 拥有的能力                                                         |
| -------------------- | ------------------------------------------------------------------ |
| **服务方**（注册者） | 持有 `token`、处理 `onRequest`、在**服务内部**按业务逻辑 `publish` |
| **调用方**（外部）   | 只能 `request` 请求、`subscribe` 订阅                              |

**每个插件运行在独立 JS 上下文中，无法共享服务状态**——若两个插件各自注册同名服务，`onRequest` 只会投递给其中一个，另一个被静默忽略。因此：**服务唯一，其余都是调用者**。依赖包的典型结构：

1. **尝试注册**：`registerService` 成功即成为唯一服务实例（属主 `PluginService` 句柄，含 `token` 与 `onRequest`）；失败（已存在）则用 `error.serviceId` **降级**，经 `getService` 接入既有服务（消费者句柄）。
2. **对外只暴露调用接口**：无论是否持有服务端，使用者调用的都是同一套 `request` / `subscribe` 封装，隐藏 serviceId/token 细节。

```ts
import { PluginService, registerService, getService } from 'yeow-api';

let _svc: PluginService;   // 成功 = 属主句柄（含 token）；降级后 = 消费者句柄（无 token）

export async function initEconomy() {
  try {
    _svc = await registerService('iyexin.economy.v1', async (path, body) => {
      if (path === '/deposit') {
        await db.deposit(body.player, body.amount);
        _svc.publish('deposit', { player: body.player, amount: body.amount });   // 发布始终在服务端内部
        return { ok: true };
      }
      return { err: 'unknown path' };
    });
  } catch (e) {
    _svc = (await getService(e.serviceId)) as PluginService;   // 降级为调用者
  }
}

export async function deposit(player: string, amount: number) {
  return (await _svc.request('/deposit', { body: { player, amount } })).json();
}
```

> **禁止对外暴露服务端能力（尤其是 `publish`）**：注册失败时根本拿不到 `token`；即便泄露，各插件上下文独立，外部 `publish` 会绕过服务端造成**致命的状态不一致**。哪怕需求只是"广播一个事件"（如 `deposit` 成功后广播余额变化），也应让调用方 `svc.request('/publishEvent', { body: event })`，由服务端内部 `publish`。
>
> **`token` 是服务方私有凭证**，只在注册时返回一次，不应外传。
>
> **不要用「先 `hasService` 判断再注册」**：检查与注册**不是原子操作**，并发下有竞态；必须 `try-catch` 包裹注册，并在捕获到「已存在」时降级（见下文「获取与检查服务」）。

> **serviceId 命名规范：** 公共服务的 serviceId 即 `refName`。为避免不同作者 / 版本冲突，用 `作者.服务名.版本`，如 `iyexin.economy.v1`。

> 更多依赖包封装示例（纯调用 / 内嵌 JS 服务 / 内嵌原生服务 / JS 门面 + 原生引擎）见 [封装 Service 的依赖包](../package-service.md)。

> 依赖包封装服务的具体类型（纯调用 SDK / 内嵌 JS 服务 / 内嵌原生服务 / JS 门面 + 原生引擎）与完整代码示例见 [封装 Service 的依赖包](../package-service.md)。

## Service 句柄（OOP）

`registerService` / `registerNativeService` 返回**句柄对象**，后续请求、订阅、发布、卸载都通过句柄方法完成。请求 / 订阅能力定义在抽象基类 `Service`：

| 成员                            | 类型                       | 说明                                 |
| ------------------------------- | -------------------------- | ------------------------------------ |
| `id`                            | `string`                   | serviceId                            |
| `kind`                          | `'plugin' \| 'native'`     | 服务类型                             |
| `request(path, options?)`       | `Promise<ServiceResponse>` | 请求服务（见 [请求服务](#请求服务)） |
| `subscribe(eventPath, handler)` | `() => void`               | 订阅事件（见 [订阅事件](#订阅事件)） |
| `unregister()`                  | `Promise<void>`            | 卸载服务（见 [卸载服务](#卸载服务)） |

`ServiceResponse.ok` 兼容 fetch 风格；`ServiceReply` 供服务方 `onRequest` 携带响应头。

### PluginService extends Service

| 成员                        | 说明                                                                                             |
| --------------------------- | ------------------------------------------------------------------------------------------------ |
| `token?`                    | 属主 token；仅 `registerService` 返回的属主句柄持有，`getService` 返回的消费者句柄为 `undefined` |
| `publish(eventPath, body?)` | 发布事件。**需 token**；消费者句柄调用抛错                                                       |
| `unregister()`              | 需 token；消费者句柄调用抛出 `Promise` reject                                                    |

### NativeService extends Service

| 成员                   | 说明                                                       |
| ---------------------- | ---------------------------------------------------------- |
| `ready()`              | `Promise<void>`：等待原生进程 TCP 连接建立、就绪消息已收到 |
| `onTerminate(handler)` | 注册服务终止钩子，见下文[终止钩子](#终止钩子-onterminate)  |

## Plugin Service

### 注册

```ts
import { registerService, ServiceReply } from 'yeow-api';

const svc = await registerService('myService', (path, body) => {
    switch (path) {
        case '/api/add': return { sum: body.a + body.b };
        case '/api/echo': return new ServiceReply({ you_sent: body }, { 'x-echo': 'true' });
        default: return { err: 'unknown path' };
    }
}, true);
```

| 参数        | 默认   | 说明                                            |
| ----------- | ------ | ----------------------------------------------- |
| `refName`   | —      | 服务引用名。`public=true` 时同时作为 serviceId  |
| `onRequest` | —      | 请求处理回调。`(path, body) => result`          |
| `isPublic`  | `true` | 是否公有。`false` 时 Runtime 分配唯一 serviceId |

`registerService` 返回 `PluginService`（属主句柄）：`svc.id` 为 serviceId，`svc.token` 为发布/卸载鉴权凭证（**服务方私有凭证，勿对外暴露**）。`onRequest` 返回 `ServiceReply` 可携带响应头（`new ServiceReply(body, headers?)`）；返回普通值等价于 JSON body。

**重复注册：** 若 `isPublic: true` 且同名服务已存在，Promise reject，`Error` 带 `serviceId` 字段（已有服务的 ID）。此时你的 `onRequest` 不会生效——应捕获错误，改用 `await getService(err.serviceId)` 接入既有服务。见上文「场景 3（自包含设计）」。

### 发布事件

```ts
svc.publish('playerJoin', { name: player.name, time: Date.now() });
```

`publish` 需要属主 `token`；`getService` 返回的消费者句柄无 token，调用会抛错。

## Native Service

### 注册

`registerNativeService` 返回 `Promise<NativeService>`。`await svc.ready()` 返回 Promise：

- **resolve** — 原生进程 TCP 连接已建立，就绪消息已收到
- **reject** — 进程异常退出，Error 对象附带 `exitCode`（退出码）和 `output`（stdout/stderr）字段；或服务被卸载/未找到

`svc.onTerminate(handler)` — 注册服务终止钩子，见下文[终止钩子](#终止钩子-onterminate)。

> **权限**：`service:registerNative` 默认拒绝——须在 `yeow.config.json` 的 `permissions` 中声明 `"service:registerNative"`，否则注册返回 `Permission denied`。Plugin Service（`registerService`）默认允许。

`platforms` 支持两种格式（**仅单文件**；目录模式已移除）：

```ts
import { registerNativeService } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

// 1. 字符串
const svc = await registerNativeService('myNative', {
    windows: getAssetsPath('native/win/my-svc.exe'),
    linux: getAssetsPath('native/linux/my-svc'),
    macos: getAssetsPath('native/macos/my-svc'),
});
await svc.ready(); // 等待进程就绪

// 2. 单文件对象（与字符串等价）
const svc2 = await registerNativeService('myNative', {
    windows: { file: getAssetsPath('native/win/my-svc.exe') },
    linux:   { file: getAssetsPath('native/linux/my-svc') },
});
await svc2.ready();
```

> **强制声明与哈希校验**：插件（或依赖包）**必须**在 `yeow.config.json` 声明 `native` 字段（serviceId + 二进制文件），构建时计算 SHA-256 写入 `yeow.json`，加载插件时作为元数据保存。
>
> - **强制声明（构建 / 加载层）**：申请了 `service:registerNative` 权限但 `native` 清单为空 → **构建失败**（构建器）/ 加载时**拒绝加载**
> - **注册校验（运行时）**：`registerNativeService` 时 serviceId 未声明、二进制路径未声明、目录模式、或 SHA-256 不匹配 → **拒绝注册**，Promise reject
> - **不可信警告**：**声明 ≠ 可信**——所有原生服务仍按不可信处理，申请该权限的插件加载时打印醒目警告；`native-service-allow-untrusted: false` 时拒绝加载
>
> 错误原因可从 `ready()` 的 reject 消息区分：
>
> - `Native service '<id>' is not declared ...` / `Native binary '<path>' is not declared ...` — 未声明（`yeow.config.json` 缺 `native` 或漏了该服务/文件）
> - `hash mismatch ... refused to load` — 可执行文件被篡改
>
> 完整 try-catch 降级示例见 [编写依赖包](../package-author.md#原生服务的错误处理与降级)。

**支持平台**：key 支持 `操作系统` 或 `操作系统-架构` 两种粒度。**精确匹配（含架构）优先，找不到则回退到操作系统**：

| key                                   | 说明                                       |
| ------------------------------------- | ------------------------------------------ |
| `windows` / `windows-x64`             | Windows（x64 或任意架构）                  |
| `linux` / `linux-x64` / `linux-arm64` | Linux x86_64 / ARM64（树莓派、ARM 服务器） |
| `macos` / `macos-x64` / `macos-arm64` | macOS Intel / Apple Silicon                |

```js
const svc = await registerNativeService('iyexin.image-svc.v1', {
    'linux-x64':   getAssetsPath('native/linux-x64/image-svc'),
    'linux-arm64': getAssetsPath('native/linux-arm64/image-svc'),
    'windows-x64': getAssetsPath('native/windows-x64/image-svc.exe'),
    'macos-x64':   getAssetsPath('native/macos-x64/image-svc'),
    'macos-arm64': getAssetsPath('native/macos-arm64/image-svc'),
});
```

> **建议至少提供 `windows-x64` + `linux-x64` + `linux-arm64`**：绝大多数 Paper 服务器部署在 Linux x64 VPS、Linux ARM（树莓派/NAS/ARM 云主机）或 Windows x64。缺少当前平台的配置时，注册返回错误 `No binary for platform: <os> (<os>-<arch>)`。

`platforms` 对应各平台相对于 `assets/` 目录的路径。仅支持**单文件**：运行时只把该文件提取到临时目录，因此原生二进制需**自包含**（静态链接，或把依赖打进单一可执行文件）。目录模式已移除。构建时 `assets/` 下的文件随插件打包进 JAR。

> **重复注册：** `isPublic: true` 且同名服务已存在时，Promise reject，`Error` 带 `serviceId` 字段——服务进程不会被重复启动。调用方捕获错误后用 `err.serviceId`、经 `await getService(err.serviceId)` 以调用者身份接入既有服务实例（`svc.request` / `svc.subscribe`），而不是再次注册。详见上文「场景 3（自包含设计）」。

> **serviceId 命名规范：** 公共服务的 serviceId 即 `refName`，为避免不同作者的包冲突，应写明 `作者.服务名.版本`，例如 `iyexin.image-svc.v1`：
>
> ```js
> import { registerNativeService } from 'yeow-api';
> import { getAssetsPath } from 'yeow-dev';
>
> const svc = await registerNativeService('iyexin.image-svc.v1', {
>     windows: getAssetsPath('native/windows/image-svc.exe'),
>     linux:   getAssetsPath('native/linux/image-svc'),
>     macos:   getAssetsPath('native/macos/image-svc'),
> });
> ```

> **推荐用法：** 使用 `getAssetsPath()` 获取资源路径，而非手写字符串。它按调用方所属依赖项注入命名空间，确保发布 npm 包后路径仍然正确：
>
> ```js
> import { registerNativeService } from 'yeow-api';
> import { getAssetsPath } from 'yeow-dev';
> 
> const svc = await registerNativeService('mySvc', {
>     windows: getAssetsPath('native/win/my-svc.exe'),
>     linux:   getAssetsPath('native/linux/my-svc'),
> });
> ```
> 
> 当封装为 npm 公共库时尤为重要——你的 `assets/` 目录中的二进制文件会被正确的哈希复制到最终 JAR，不会因为包名或路径冲突覆盖其他包的资源。

注册时二进制文件被提取到 `%TEMP%/yeow-native-services/<serviceId>/`（每次启动时自动清理上次残留）。

Native Service **不返回 token** — 原生进程通过 TCP 连接自动鉴权，事件由 Runtime 基于连接自动关联服务。

### 终止钩子 `onTerminate`

`registerNativeService` 返回的 `NativeService` 提供 `onTerminate(handler)` 方法，注册服务终止回调。触发时机：

| reason 值      | 触发场景                                             |
| -------------- | ---------------------------------------------------- |
| `disconnected` | 子进程 TCP 连接断开（进程崩溃、网络中断）            |
| `exited`       | 子进程退出（`exitCode` 非 0 时通常伴随原因 1）       |
| `unregistered` | 服务被卸载（插件禁用 / hot-reload / `unregister()`） |
| `shutdown`     | 运行时关闭                                           |

```ts
const svc = await registerNativeService('iyexin.image-svc.v1', {
    windows: getAssetsPath('image-svc.exe'),
});

svc.onTerminate((info) => {
    log.warn(`Image service terminated: ${info.reason}, exitCode=${info.exitCode}`);
    // 重新注册或切换到降级方案
});
```

回调参数 `NativeTerminateInfo`：`{ serviceId, reason, exitCode?, output? }`。`output` 为子进程 stdout+stderr 合并输出（若已有内容）。仅服务持有者（属主插件）可注册，重复调用会替换之前的处理器。

> **调用方如何感知服务终止？** 挂起的 `svc.request(...)` Promise 会在服务终止时 reject（错误消息含原因）。订阅的事件不会主动通知终止——调用方可在收到请求失败后自行处理。

### 可执行文件协议

可执行文件接收 `<yeowPort> <serviceId>` 两个启动参数，通过 TCP 以**帧协议**（header JSON + raw body，支持流式与原始二进制）与 Runtime 通信。详见 [Native Service 规范](../specifications/native-service/index.md)。

> **二进制数据传输：** `svc.request(path, { body })` 的 `body` 传 `Uint8Array` 即按原始二进制发送（运行时解码后原样转发给子进程）；响应统一由 fetch 风格 `ServiceResponse` 承载——JSON 用 `await resp.json()`，二进制用 `await resp.bytes()` / `await resp.arrayBuffer()`。

## 获取与检查服务

```ts
import { getService, hasService } from 'yeow-api';

const svc = await getService('iyexin.economy.v1'); // 不存在时抛错（error.serviceId）
const exist = await hasService('iyexin.economy.v1'); // 仅用于展示 / 诊断
```

- `getService(id)` —— 按 id 获取句柄；不存在时抛错（`error.serviceId`）。返回的是**消费者句柄**（无 token）：`kind === 'plugin'` 时为 `PluginService`，但 `publish` / `unregister` 会抛错；`kind === 'native'` 时为 `NativeService`。
- `hasService(id)` —— 检查是否存在。

> **警告：不能用「先 `hasService` 判断再注册」！** 检查与注册**不是原子操作**，并发下会竞态——两个插件可能同时检查到不存在，随后一个注册成功、另一个被拒绝。注册必须用 `try-catch` 包裹 `registerService`，捕获到「已存在」时用 `error.serviceId` 降级接入既有服务。`hasService` 仅用于展示/诊断。

## 请求服务

`svc.request(path, options)` 返回 `Promise<ServiceResponse>`，语义类似 fetch。`options`：

| 字段          | 说明                                                                            |
| ------------- | ------------------------------------------------------------------------------- |
| `headers`     | app 级请求头（`Record<string, string>`）；`content-type` 为典型项               |
| `contentType` | `headers['content-type']` 的便捷别名                                            |
| `body`        | JSON 值，或 `Uint8Array`（原始二进制，运行时解码后原样转发）                    |
| `timeout`     | 超时（毫秒）；缺省用运行时配置（默认 30000，`service-request-timeout-ms` 可调） |

```js
const resp = await svc.request('/api/add', { body: { a: 1, b: 2 } });
const result = await resp.json();     // → { sum: 3 }

// 二进制
const bin = await svc.request('/render', { body: new Uint8Array([...]) });
const bytes = await bin.bytes();      // Uint8Array
const ab = await bin.arrayBuffer();
```

`ServiceResponse`：`ok` / `status` / `contentType` / `headers` + `json()` / `text()` / `bytes()` / `arrayBuffer()` / `base64()`（`body` 为未来的可读流预留）。请求体传 `Uint8Array` 时按原始二进制发送，否则按 JSON 值发送。

**`request` 可能抛错：**

- 服务未找到
- 超时——默认 30s，运行时配置 `service-request-timeout-ms` 可调，`options.timeout` 可逐次覆盖；错误消息 `Service request timed out after <ms>ms: <id>`
- 服务内部错误
- 服务卸载 / 终止——错误消息 `Native service <id> terminated (<reason>)`

Plugin Service 和 Native Service 使用方式完全一致——调用方不感知服务类型。

## 订阅事件

```js
const unsubscribe = svc.subscribe('status', (body, eventPath) => {
    console.log(body, eventPath);
});

unsubscribe();
```

返回 `() => void` 取消函数。插件 unload / hot-reload 时 Runtime 自动清理订阅。事件订阅语义不变。

## 卸载服务

```js
await svc.unregister();
```

- **Plugin Service** 需属主 `token`（`registerService` 返回的属主句柄）；`getService` 返回的消费者句柄调用会抛错（`unregister requires the owner token ...`）
- **Native Service** 无需 `token`，但调用方必须是属主插件

## 示例

```js
// 注册 Plugin Service
const svc = await registerService('chatSvc', (path, body) => {
    if (path === '/translate') return { translated: `[EN] ${body.text}` };
    return { err: 'unknown' };
});

const unsub = svc.subscribe('message', (body, eventPath) => {
    console.log(`[${body.author}]: ${body.text}`);
});

const translated = await (await svc.request('/translate', { body: { text: 'Bonjour' } })).json();

svc.publish('message', { author: 'Bot', text: 'Hello!' });

unsub();
```

```js
import { registerNativeService } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

// 注册 Native Service + 等待就绪（路径必须经 getAssetsPath 解析）
const svc = await registerNativeService('image-svc', {
    windows: getAssetsPath('image-svc.exe'),
});

try {
    await svc.ready();
    console.log('Native service is ready');
} catch (e) {
    console.error('Native service failed to start:', e.message);
    return;
}

const result = await (await svc.request('/render', { body: { width: 1024, height: 1024 } })).json();
```

### 错误处理与降级（registerNativeService）

`ready()` 可能因多种原因 reject（服务已存在 / 可执行文件被篡改）。注意：申请 `service:registerNative` 权限只决定加载时是否警告/拒绝（见上），注册阶段的错误只有以下两类：

```js
import { registerNativeService, getService, log } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

try {
    const svc = await registerNativeService('iyexin.image-svc.v1', {
        windows: getAssetsPath('native/win/image-svc.exe'),
    });
    await svc.ready();
} catch (e) {
    const msg = e.message;
    if (msg.includes('Service already registered')) {
        // 服务已存在：用 err.serviceId 以调用方身份接入既有服务（正常降级）
        const sid = e.serviceId;
        const svc = await getService(sid);
        await svc.request('/ping', { body: {} });
    } else if (msg.includes('hash mismatch')) {
        // 可执行文件被篡改（声明与实际 SHA-256 不一致）：拒绝使用，检查二进制来源
        log.error('Native binary tampered — refusing to load');
    } else {
        log.error('Native service failed:', msg);
    }
}
```
