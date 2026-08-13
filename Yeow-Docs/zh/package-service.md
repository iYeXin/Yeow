# 封装 Service 的依赖包

> 从 [编写依赖包](package-author.md) 拆出的独立主题：如何在 npm 包中封装 Service（插件间通信与原生扩展）。包的基础结构（package.json / 权限 / 资源）见[编写依赖包](package-author.md)。

Service 是 Yeow 包最常见的封装对象。根据**包的角色**（调用方 / 服务方）与**服务来源**，分为三类：

| 类型                       | 包的角色         | 是否注册服务  | 服务来源                     | 典型场景                                                   |
| -------------------------- | ---------------- | :-----------: | ---------------------------- | ---------------------------------------------------------- |
| **1. SDK（调用方语法糖）** | 纯调用方         |       ❌       | 其他插件已注册的服务         | 经济、领地、商店等插件对外暴露的服务                       |
| **2. JS 服务（全局唯一）** | 服务方 + 调用方  | ✅（失败降级） | 本包注册（JS 逻辑）          | 需要全局唯一状态的通用功能（统计、聊天格式化、跨插件数据） |
| **3. 原生服务**            | 服务方（子进程） | ✅（失败降级） | 本包注册（`assets/` 二进制） | 图像处理、机器学习等重计算                                 |

**组合规则**：类型可以组合使用。最典型的是 **类型 2 + 3**——包注册一个 JS 服务作为**门面**（对外路径约定、事件发布），内部再启动原生子进程作为**引擎**（重计算）。类型 2 完整包含类型 1 的调用方封装（降级后即退回类型 1 的形态）。

## 类型 1 —— SDK（调用方语法糖）

服务由**其他插件**提供（通常该插件引入了类型 2/3 的包，或直接注册了服务）。本包只做调用封装：

- 不调用 `registerService` / `registerNativeService`
- 不接触 `token`、不发布事件
- 对外暴露 `serviceRequest` / `serviceSubscribe` 的封装函数

```ts
// yeow-economy-sdk —— 调用方语法糖
import { serviceRequest, serviceSubscribe } from 'yeow-api';

export const ECONOMY_SERVICE = 'iyexin.economy.v1';   // 与提供方约定的 serviceId

export function deposit(player: string, amount: number) {
    return serviceRequest(ECONOMY_SERVICE, '/deposit', { player, amount });
}

export function onBalanceChange(handler: (p: { player: string; balance: number }) => void) {
    return serviceSubscribe(ECONOMY_SERVICE, 'balanceChange', handler);
}
```

要点：
- `serviceId` 以常量声明，与提供方约定一致
- 使用者的插件需与提供服务的插件**同时安装**（否则请求失败：服务未找到）

## 类型 2 —— JS 服务（全局唯一）

包内实现服务方逻辑（纯 JS），入口处尝试注册：**成功 → 本插件成为唯一服务实例；失败（同名 public 服务已存在）→ 降级为调用方**，用 `err.serviceId` 接入既有服务：

> **封装铁律（服务端与客户端隔离）**：一个 service 的封装包括**服务端逻辑**（`onRequest`、`publish`）与**客户端逻辑**（`subscribe`、`request`）。由于 public 服务同一时刻只能存在一个服务端，包内必须先**尝试注册服务端**，再**以客户端身份封装对外接口**；无论注册是否成功，对外暴露的逻辑一致。**绝对不允许暴露任何直接调用服务端能力的接口（最典型是发布事件）**——注册失败时拿不到 `token`，逻辑缺失；即使拿到 `token`，各插件 JS 上下文不同，外部发布也会造成致命的状态不一致。哪怕需求只是单纯发布事件，也应走 `serviceRequest(svcId, '/publishEvent', event)` 由服务端配合。详见 [Service API 自包含设计](api/service.md#公共服务的自包含设计)。

```ts
// yeow-stats —— JS 服务 + 调用方封装
import { registerService, serviceRequest, servicePublish } from 'yeow-api';

let _serviceId: string;
let _svc: { serviceId: string; token: string } | null = null;

export async function initStats() {
    try {
        _svc = await registerService('iyexin.stats.v1', async (path, body) => {
            if (path === '/record') {
                const entry = await store.record(body.kind, body.value);
                if (_svc) servicePublish(_svc.token, 'record', entry);  // 服务方内部发布
                return { ok: true };
            }
            if (path === '/publishEvent') {          // 对外"只发布事件"需求的服务端配合
                await store.emit(body.event, body.payload);
                if (_svc) servicePublish(_svc.token, body.event, body.payload);
                return { ok: true };
            }
            return { err: 'unknown path' };
        });
        _serviceId = _svc.serviceId;   // 成为服务方，token 留在包内
    } catch (e) {
        _serviceId = e.serviceId;      // 已存在：降级为调用方
    }
}

// 对外只暴露调用封装——服务方与调用方走同一代码路径
export function record(kind: string, value: number) {
    return serviceRequest(_serviceId, '/record', { kind, value });
}

// 哪怕只是"发布事件"，也不暴露 publish——走 request，由服务端内部发布
export function publishEvent(event: string, payload: unknown) {
    return serviceRequest(_serviceId, '/publishEvent', { event, payload });
}
```

要点：
- **服务唯一**：多个插件引入同一包时，第一个注册的插件成为服务方，其余自动降级为调用方。所有插件的 `record()` 最终都请求到**同一个服务实例**，行为一致
- **全局唯一状态**：服务方的状态（如 `store`）只存在于服务方插件的 JS 上下文中——上下文不可跨插件共享，这正是"全局唯一"的价值
- 降级后本包的调用封装依然可用（经 `err.serviceId` 路由到既有服务），调用方无需感知

## 类型 3 —— 原生服务

包内携带二进制（`assets/`），用 `registerNativeService` 按平台提取并启动子进程，再封装调用。核心模式：

```ts
// yeow-image —— 原生服务
import { registerNativeService, serviceRequest } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

export const IMAGE_SERVICE = 'iyexin.image-svc.v1';

export async function initRenderer(): Promise<ImageRenderer> {
    const { serviceId, ready, onTerminate } = await registerNativeService(IMAGE_SERVICE, {
        'linux-x64':   getAssetsPath('native/linux-x64/image-svc'),
        'windows-x64': getAssetsPath('native/windows-x64/image-svc.exe'),
        'macos-x64':   getAssetsPath('native/macos-x64/image-svc'),
        'macos-arm64': getAssetsPath('native/macos-arm64/image-svc'),
    });
    await ready();
    onTerminate((info) => { /* 子进程终止：记录日志 / 切换降级方案 */ });

    return {
        serviceId,
        render(width, height, pixels) {
            return serviceRequest(serviceId, '/imageRender', { width, height, base64: pixels.toBase64() });
        },
    };
}
```

要点：
- 注册失败（重复注册 / 平台不支持）同样 reject —— 捕获后按类型 2 的方式降级（`err.serviceId` 接入既有服务），或直接抛错
- `onTerminate` 只在**服务方**侧有意义（子进程是服务方启动的；降级为调用方后不触发）

### 原生服务的错误处理与降级

`registerNativeService` / `ready()` 的 reject 原因需要区分（服务已存在 / 可执行文件被篡改）。**未批准在加载层处理**：声明原生服务的插件默认被拒绝加载（控制台提示一次性码 `/yeow approve <code>`，批准后自动加载），插件不运行；若已批准，则注册阶段的错误只剩以下两类：

```ts
import { registerNativeService, serviceRequest, log } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

export async function initRenderer(): Promise<ImageRenderer | null> {
    try {
        const { serviceId, ready } = await registerNativeService(IMAGE_SERVICE, {
            'windows-x64': getAssetsPath('native/windows-x64/image-svc.exe'),
            'linux-x64':   getAssetsPath('native/linux-x64/image-svc'),
        });
        await ready();
        return { render: (w, h, px) => serviceRequest(serviceId, '/imageRender', { width: w, height: h, base64: px.toBase64() }) };
    } catch (e) {
        const msg = (e as Error).message;
        if (msg.includes('Service already registered')) {
            // 服务已存在：以调用方身份接入既有服务（正常降级）
            const sid = (e as any).serviceId as string;
            return { render: (w, h, px) => serviceRequest(sid, '/imageRender', { width: w, height: h, base64: px.toBase64() }) };
        }
        if (msg.includes('hash mismatch')) {
            // 可执行文件被篡改（声明与实际 SHA-256 不一致）：拒绝使用
            log.error('Native binary tampered — refusing to load');
            return null;
        }
        log.error('Native service failed:', msg);
        return null;
    }
}
```

> 原生服务的**可信性声明（SHA-256）与批准机制**见[权限与原生服务可信性](permissions.md#二原生服务可信性声明)。

## 组合 —— JS 门面 + 原生引擎（类型 2 + 3）

JS 服务作为门面（对外路径约定、事件发布），原生子进程作为引擎（重计算）。包同时承担服务方（注册 JS 服务）与子进程管理者两个角色：

```ts
// yeow-image-svc —— 类型 2 + 3 组合
import { registerService, registerNativeService, serviceRequest, servicePublish } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

let _serviceId: string;
let _svc: { serviceId: string; token: string } | null = null;
let _engine: { render(p: any): Promise<any> } | null = null;

export async function initImageService() {
    try {
        // ① 先注册 JS 门面（成为服务方的前提）
        _svc = await registerService('iyexin.image-svc.v1', async (path, body) => {
            if (path === '/render') {
                const result = await _engine!.render(body);          // 引擎：原生子进程
                if (_svc) servicePublish(_svc.token, 'rendered', result);  // 服务方内部发布
                return result;
            }
            return { err: 'unknown path' };
        });
        _serviceId = _svc.serviceId;

        // ② 成为服务方后，再启动原生引擎
        const native = await registerNativeService('iyexin.image-engine.v1', {
            'linux-x64':   getAssetsPath('native/linux-x64/image-svc'),
            'windows-x64': getAssetsPath('native/windows-x64/image-svc.exe'),
        });
        await native.ready();
        _engine = { render: (p) => serviceRequest(native.serviceId, '/render', p) };
    } catch (e) {
        // JS 门面已存在 → 整体降级为调用方
        _serviceId = e.serviceId;
    }
}

export function render(width: number, height: number, pixels: Uint8Array) {
    return serviceRequest(_serviceId, '/render', { width, height, base64: pixels.toBase64() });
}
```

> **注意**：先注册 JS 门面、后启动引擎。若引擎启动失败（如平台不支持）而门面已注册成功，包应自行决定：抛错中断初始化（此时已注册的门面成为孤儿服务，需插件重启恢复），或继续以"无引擎"模式运行并对外返回错误。

## 如何选择

| 你的场景                              | 类型      |
| ------------------------------------- | --------- |
| 服务由其他插件提供，只需写调用封装    | **1**     |
| 需要全局唯一的逻辑/状态，多个插件共享 | **2**     |
| 需要二进制/重计算能力                 | **3**     |
| JS 门面 + 原生引擎                    | **2 + 3** |

---

相关：Service 的完整 API 见 [Service API](api/service.md)；原生子进程的 TCP 协议见 [Native Service 规范](specifications/native-service/index.md)。
