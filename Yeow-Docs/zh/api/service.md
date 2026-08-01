# Service API

插件间通信和原生能力扩展。

```js
import { registerService, registerNativeService, serviceRequest, serviceSubscribe, servicePublish } from 'yeow-api';
import type { ServiceResult, NativeServiceResult } from 'yeow-api';
```

## 公共服务的自包含设计

> 这是 Yeow Service 的核心设计原则，写插件/npm 包前请先阅读本节。

**public 服务是一个自包含的"服务方"，服务与调用者的界限必须分明：**

| 角色 | 拥有的能力 |
| ---- | ---------- |
| **服务方**（注册者） | 持有 `token`、处理 `onRequest` 请求、在**服务内部**按业务逻辑调用 `publish` 发布事件 |
| **调用方**（外部） | 只能通过 `request`（`serviceRequest`）请求服务，或 `subscribe` 订阅服务事件 |

具体规则：

1. **外部不应直接调用 `publish`**——事件发布是服务方的内部职责。外部需要触发业务逻辑时，应发送 `request`，由服务方收到请求后决定是否/何时发布事件。这样服务的业务状态（谁有权限发布、发布什么）始终封装在服务内部。
2. **`token` 是服务方的私有凭证**，只在注册时返回一次，不应对外暴露。
3. **重复注册会拒绝 Promise**：`isPublic: true` 且同名服务已存在时，`registerService` / `registerNativeService` 抛出的 `Error` 携带 `serviceId` 字段（即已存在服务的 ID）。调用方应捕获错误，并用 `err.serviceId` 以**调用方身份**接入既有服务实例。
4. **npm 包封装模式**：公共服务通常来自一个 npm 依赖包（多个插件都会引入）。包内应封装注册逻辑，对外**只暴露 request 的封装函数**，隐藏 serviceId/token 细节。按包的角色分为三类（SDK / JS 服务 / 原生服务，可组合）——见下文 [Service 包类型](#service-包类型npm)。下面是一个"JS 服务"（类型 2）的示例，包同时是服务方与调用方：

```ts
// yeow-economy 包内部 —— 注册 + 封装
import { registerService, serviceRequest, servicePublish } from 'yeow-api';

let _serviceId: string;
let _svc: { serviceId: string; token: string } | null = null;

export async function initEconomy() {
  try {
    _svc = await registerService('iyexin.economy.v1', async (path, body) => {
      // 服务方：收到请求后按业务逻辑在内部发布事件
      if (path === '/deposit') {
        await db.deposit(body.player, body.amount);
        if (_svc) servicePublish(_svc.token, 'deposit', { player: body.player, amount: body.amount });
        return { ok: true };
      }
      return { err: 'unknown path' };
    });
    _serviceId = _svc.serviceId;   // token 留在包内，不外传
  } catch (e) {
    // 已存在：以调用方身份接入既有服务实例
    _serviceId = e.serviceId;
  }
}

// 对外只暴露 request 封装
export function deposit(player: string, amount: number) {
  return serviceRequest(_serviceId, '/deposit', { player, amount });
}
```

> 类型 1（纯 SDK）只需把上面的 `registerService` 部分去掉、`_serviceId` 换成常量；类型 3（原生服务）把 `registerService` 换成 `registerNativeService` + `await ready()`。组合模式（JS 门面 + 原生引擎）见 [package-author.md](../package-author.md)。

> **为什么不能"重复注册即共享"？** 每个插件运行在独立的 JS 上下文中，无法共享服务状态。如果两个插件各自注册同名服务，`onRequest` 只会投递给其中一个，另一个插件的处理器被静默忽略，行为不可预期。因此设计为：**服务唯一，其余都是调用者**。

> **serviceId 命名规范：** 公共服务的 serviceId 就是 `refName`（`isPublic: true` 时）。为避免不同作者/不同版本的服务冲突，请使用 `作者.服务名.版本` 格式，如 `iyexin.economy.v1`。

## Service 包类型（npm）

封装 Service 的 npm 包按**角色**分为三类，可组合使用：

| 类型 | 包的角色 | 注册服务 | 服务来源 | 典型场景 |
| ---- | -------- | :------: | -------- | -------- |
| **1. SDK（调用方语法糖）** | 纯调用方 | ❌ | 其他插件已注册的服务 | 经济、领地、商店插件对外暴露的服务 |
| **2. JS 服务（全局唯一）** | 服务方 + 调用方 | ✅ 失败降级 | 本包 JS 逻辑 | 需要全局唯一状态的通用功能 |
| **3. 原生服务** | 服务方（子进程） | ✅ 失败降级 | 本包 `assets/` 二进制 | 图像处理、机器学习等重计算 |

- **类型 1**：不注册服务、不持有 token，只封装 `serviceRequest` / `serviceSubscribe`。服务必须由其他插件提供
- **类型 2**：入口尝试注册，成功即唯一服务实例（token/onRequest/事件发布在包内）；同名服务已存在时注册被拒绝，用 `err.serviceId` **降级为调用方**接入既有服务。对外只暴露 request 封装
- **类型 3**：`registerNativeService` 按平台提取 `assets/` 二进制并 spawn 子进程；降级逻辑同类型 2
- **组合（2 + 3）**：类型 2 的服务方逻辑可内嵌类型 3 的原生引擎——包注册 JS 服务作为**门面**（路径约定、事件发布），内部管理原生子进程作为**引擎**（重计算）

> 完整代码示例（含类型 1/2/3 与组合模式）见 [package-author.md](../package-author.md)「封装 Service 的包（三种类型）」。

## Plugin Service

### 注册

```js
const { serviceId, token } = await registerService('myService', (path, body) => {
    switch (path) {
        case '/api/add': return { sum: body.a + body.b };
        case '/api/echo': return { you_sent: body };
        default: return { err: 'unknown path' };
    }
}, true);
```

| 参数        | 默认   | 说明                                            |
| ----------- | ------ | ----------------------------------------------- |
| `refName`   | —      | 服务引用名。`public=true` 时同时作为 serviceId  |
| `onRequest` | —      | 请求处理回调。`(path, body) => result`          |
| `isPublic`  | `true` | 是否公有。`false` 时 Runtime 分配唯一 serviceId |

`registerService` 返回 `{ serviceId, token }`。`token` 用于 `publish` 鉴权（**服务方私有凭证，勿对外暴露**）。

**重复注册：** 若 `isPublic: true` 且同名服务已存在，Promise reject，`Error` 带 `serviceId` 字段（已有服务的 ID）。此时你的 `onRequest` 不会生效——应捕获错误，改用 `serviceRequest(err.serviceId, ...)` 接入既有服务。见上文[自包含设计](#公共服务的自包含设计)。

### 发布事件

```js
servicePublish(token, 'playerJoin', { name: player.name, time: Date.now() });
```

## Native Service

### 注册

`registerNativeService` 返回 `{ serviceId, ready, onTerminate }`。`ready()` 返回 Promise：

- **resolve** — 原生进程 TCP 连接已建立，就绪消息已收到
- **reject** — 进程异常退出，Error 对象附带 `exitCode`（退出码）和 `output`（stdout/stderr）字段；或服务被卸载/未找到

`onTerminate(handler)` — 注册服务终止钩子，见下文[终止钩子](#终止钩子-onterminate)。

> **权限**：`service:registerNative` 默认拒绝——须在 `yeow.config.json` 的 `permissions` 中声明 `"service:registerNative"`，否则注册返回 `Permission denied`。Plugin Service（`registerService`）默认允许。

`platforms` 支持三种格式：

```js
import { registerNativeService, getAssetsPath } from 'yeow-api';

// 1. 字符串（向后兼容）
const { serviceId, ready } = await registerNativeService('myNative', {
    windows: getAssetsPath('native/win/my-svc.exe'),
    linux: getAssetsPath('native/linux/my-svc'),
    macos: getAssetsPath('native/macos/my-svc'),
});
await ready(); // 等待进程就绪

// 2. 单文件对象
const { serviceId, ready } = await registerNativeService('myNative', {
    windows: { file: getAssetsPath('native/win/my-svc.exe') },
    linux:   { file: getAssetsPath('native/linux/my-svc') },
});

// 3. 目录 + 入口（提取整个目录到临时目录，运行入口文件）
const { serviceId, ready } = await registerNativeService('myNative', {
    windows: { dir: getAssetsPath('native/'), entry: 'win/start.ps1' },
    linux:   { dir: getAssetsPath('native/'), entry: 'linux/start.sh' },
    macos:   { dir: getAssetsPath('native/'), entry: 'macos/start.sh' },
}, true);
```

**支持平台**：key 支持 `操作系统` 或 `操作系统-架构` 两种粒度。**精确匹配（含架构）优先，找不到则回退到操作系统**：

| key | 说明 |
|-----|------|
| `windows` / `windows-x64` | Windows（x64 或任意架构） |
| `linux` / `linux-x64` / `linux-arm64` | Linux x86_64 / ARM64（树莓派、ARM 服务器） |
| `macos` / `macos-x64` / `macos-arm64` | macOS Intel / Apple Silicon |

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

`dir` 使用 `getAssetsPath()` 时，**顶层目录整体哈希**（如 `assets/native.a1b2c3d4/`），目录内一切保持原名——`entry` 及其引用的所有文件（含嵌套子目录、`../` 兄弟目录）都不会被改名，引用关系完整。

> **`dir` 应指向包含全部依赖的最顶层目录**，`entry` 用相对子路径：
>
> ```ts
> // ❌ 只提取 win/，start.bat 引用 ../shared/ 会断
> { dir: getAssetsPath('native/win/'), entry: 'start.bat' }
>
> // ✅ 提取整个 native/（自包含），内部引用完整
> { dir: getAssetsPath('native/'), entry: 'win/start.bat' }
> ```
>
> 更多边界详见 [Assets API](assets.md#边界与注意事项)。

`platforms` 对应各平台相对于 `assets/` 目录的路径。构建时 `assets/` 下的文件随插件打包进 JAR。

> **重复注册：** `isPublic: true` 且同名服务已存在时，Promise reject，`Error` 带 `serviceId` 字段——服务进程不会被重复启动。调用方捕获错误后用 `err.serviceId` 以调用者身份接入既有服务实例（`serviceRequest` / `serviceSubscribe`），而不是再次注册。详见上文[自包含设计](#公共服务的自包含设计)。

> **serviceId 命名规范：** 公共服务的 serviceId 即 `refName`，为避免不同作者的包冲突，应写明 `作者.服务名.版本`，例如 `iyexin.image-svc.v1`：
>
> ```js
> import { registerNativeService, getAssetsPath } from 'yeow-api';
>
> const svc = await registerNativeService('iyexin.image-svc.v1', {
>     windows: getAssetsPath('native/windows/image-svc.exe'),
>     linux:   getAssetsPath('native/linux/image-svc'),
>     macos:   getAssetsPath('native/macos/image-svc'),
> });
> ```

> **推荐用法：** 使用 `getAssetsPath()` 获取资源路径，而非手写字符串。它在构建时对文件做哈希处理，确保发布 npm 包后路径仍然正确：
>
> ```js
> import { getAssetsPath, registerNativeService } from 'yeow-api';
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

`registerNativeService` 返回的对象提供 `onTerminate(handler)` 方法，注册服务终止回调。触发时机：

| reason 值 | 触发场景 |
| --------- | -------- |
| `disconnected` | 子进程 TCP 连接断开（进程崩溃、网络中断） |
| `exited` | 子进程退出（`exitCode` 非 0 时通常伴随原因 1） |
| `unregistered` | 服务被卸载（插件禁用 / hot-reload） |
| `shutdown` | 运行时关闭 |

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

> **调用方如何感知服务终止？** 挂起的 `serviceRequest` Promise 会在服务终止时 reject（错误消息含原因）。订阅的事件不会主动通知终止——调用方可在收到请求失败后自行处理。

### 可执行文件协议

可执行文件接收 `<yeowPort> <serviceId>` 两个启动参数，通过 TCP 以 JSON line 协议与 Runtime 通信。详见 [Native Service 规范](../specifications/native-service/index.md)。

> **二进制数据传输：** 请求/响应的 `body` 为 JSON，不支持直接传递二进制。需要传输二进制数据时，用 Base64 编码。运行时的 `uint8ArrayToBase64()` 和 `base64ToUint8Array()` 全局函数可用于编解码。

## 请求服务

```js
const result = await serviceRequest(serviceId, '/api/add', { a: 1, b: 2 });
// → { sum: 3 }
```

返回 Promise。若服务未找到或请求失败，Promise reject。若服务在请求挂起期间终止（断开/退出/卸载），挂起的请求也会被 reject，错误消息格式：`Native service <serviceId> terminated (<reason>)`。

Plugin Service 和 Native Service 使用方式完全一致——调用方不感知服务类型。

## 订阅事件

```js
const unsubscribe = serviceSubscribe(serviceId, 'status', (body, eventPath) => {
    console.log(body, eventPath);
});

unsubscribe();
```

返回 `() => void` 取消函数。插件 unload / hot-reload 时 Runtime 自动清理订阅。

## 示例

```js
// 注册 Plugin Service
const svc = await registerService('chatSvc', (path, body) => {
    if (path === '/translate') return { translated: `[EN] ${body.text}` };
    return { err: 'unknown' };
});

const unsub = serviceSubscribe(svc.serviceId, 'message', (path, body) => {
    console.log(`[${body.author}]: ${body.text}`);
});

const translated = await serviceRequest(svc.serviceId, '/translate', { text: 'Bonjour' });

servicePublish(svc.token, 'message', { author: 'Bot', text: 'Hello!' });

unsub();
```

```js
import { registerNativeService, getAssetsPath } from 'yeow-api';

// 注册 Native Service + 等待就绪（路径必须经 getAssetsPath 哈希解析）
const { serviceId, ready } = await registerNativeService('image-svc', {
    windows: getAssetsPath('image-svc.exe'),
});

try {
    await ready();
    console.log('Native service is ready');
} catch (e) {
    console.error('Native service failed to start:', e.message);
    return;
}

const result = await serviceRequest(serviceId, '/render', { width: 1024, height: 1024 });
```
