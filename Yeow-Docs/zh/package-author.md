# 编写 Yeow 依赖包指南

Yeow 插件可以把共享逻辑和资源封装为 npm 包，供其他插件复用。典型场景：

- 封装 Service（三类包：SDK / JS 服务 / 原生服务，见下文「[封装 Service 的包（三种类型）](#封装-service-的包三种类型)」）
- 封装 Native Service（如 `yeow-image` 打包 `image-svc.exe` + 注册/调用逻辑）
- 共享工具函数（如 `yeow-utils`）
- 共享配置模板、资源文件

---

## 包结构

```
yeow-image/
├── package.json          ← 包元信息（关键字段见下）
├── tsconfig.json         ← TS 配置（可省略）
├── assets/               ← 随包发布的资源（exe、配置、图片等）
│   └── image-svc.exe
└── src/
    └── index.ts          ← 包入口（main/types 指向这里）
```

## package.json

```json
{
    "name": "yeow-image",
    "version": "0.1.0",
    "type": "module",
    "main": "./src/index.ts",
    "types": "./src/index.ts",
    "files": ["src/", "assets/"],
    "peerDependencies": {
        "yeow-api": "^0.2.57"
    },
    "devDependencies": {
        "yeow-api": "^0.2.57"
    },
    "license": "MIT"
}
```

### 关键字段

| 字段               | 说明                                                                        |
| ------------------ | --------------------------------------------------------------------------- |
| `main` / `types`   | 指向 `src/index.ts` 源码（构建时由主项目 esbuild 直接打包源码，无需预编译） |
| `files`            | **必须包含 `assets/`**，否则发布到 npm 时资源不会随包分发                   |
| `peerDependencies` | 运行时契约：`yeow-api` 版本范围。声明"使用者插件必须安装 yeow-api"          |
| `devDependencies`  | 开发期依赖：独立开发时 `import 'yeow-api'` 需要类型定义和 IDE 提示          |


```bash
npm install --save-dev yeow-api
```

### 两个声明的分工

| 声明               | 作用域             | 用途                                                                 |
| ------------------ | ------------------ | -------------------------------------------------------------------- |
| `peerDependencies` | 运行时（使用者侧） | 契约：使用者的插件必须有 yeow-api；dedupe 插件保证运行时用主项目实例 |
| `devDependencies`  | 开发期（作者侧）   | 独立开发时的类型/提示；**不随包发布**，不影响运行时                  |

**版本保持同步**：两者建议写同一个版本范围（`^0.2.57`），避免开发期与运行时 API 不一致。

---

## 权限声明

敏感消息节点默认拒绝，必须由**依赖此包的主项目**在 `yeow.config.json` 的 `permissions` 字段声明（构建时写入 `yeow.json`）：

| 需要声明的节点                            | 包内对应能力                                               |
| ----------------------------------------- | ---------------------------------------------------------- |
| `fs:*`（或 `fs:readFile` 等节点）         | `fs.*` API（读取/写入/删除等）                             |
| `http:*`（或 `http:requestAsync` 等节点） | `fetch`、`request`、HTTP 服务器（`createServer`/`listen`） |
| `service:registerNative`                  | `registerNativeService`（spawn 原生子进程）                |
| `assets:extract`                          | `assetsExtract` / `assetsExtractSync`（解压资源到磁盘）    |

规则：

- 节点级（`fs:readFile`）与通配级（`fs:*`）均可；未声明调用返回 `Permission denied: <node>`（异步 API 为 Promise reject）
- 其余节点（`service:request`、`assets:read` 等）默认允许
- 权限在插件**加载时**读取并固定；使用者修改 `permissions` 后需重新构建并**完整重载插件**（`/yeow reload` 或重启服务器）才生效——开发模式热重载不更新权限

### 包作者必须在 README 中说明所需权限

**凡是包内用到上述四类能力，README 必须注明使用者需要声明哪些权限节点**——否则使用者的插件加载后调用会直接返回 `Permission denied`，且加载日志中的权限清单不含对应节点，难以排查。约定格式：

```md
## 权限

本包需要在使用者插件的 `yeow.config.json` 中声明（构建后写入 yeow.json）：

```json
{
    "permissions": ["service:registerNative", "fs:readFile", "http:requestAsync"]
}
```

| 节点                     | 用途                    |
| ------------------------ | ----------------------- |
| `service:registerNative` | 启动图片处理原生子进程  |
| `fs:readFile`            | 读取缓存文件            |
| `http:requestAsync`      | 下载远程模型（`fetch`） |

缺少声明时对应功能报 `Permission denied` 错误。

> 未注明权限的包：使用者无法预知所需权限，功能会静默/报错失败——这属于包文档缺陷。发布前请对照上文表格逐一核对包内使用的 API，确保权限清单完整。

---

## 使用 getAssetsPath 访问资源

包内部通过 `getAssetsPath()` 获取 `assets/` 中文件的 JAR 内路径：

```ts
// src/index.ts
import { registerNativeService, getAssetsPath } from 'yeow-api';

export const IMAGE_SERVICE = 'iyexin.image-svc.v1';

export async function registerImageService(): Promise<string> {
    const { serviceId, ready } = await registerNativeService(IMAGE_SERVICE, {
        windows: getAssetsPath('image-svc.exe'),
    });
    await ready();
    return serviceId;
}
```

**构建时自动处理：**

- 主项目 esbuild 扫描本包 `assets/` → 文件哈希 → 复制到 JAR `assets/`
- `getAssetsPath('image-svc.exe')` 在构建后返回 `"assets/image-svc.a1b2c3d4.exe"`（真实路径）
- **无需任何额外配置** — 构建插件自动扫描所有依赖包的 `assets/`

### 目录资源

如果入口脚本需要引用同目录的兄弟文件（脚本内相对引用），用目录级路径：

```ts
const svc = await registerNativeService('my-svc', {
    windows: { dir: getAssetsPath('native/'), entry: 'win/start.ps1' },
});
```

**哈希规则**：顶层目录（`assets/` 直接子目录）整体哈希（`native.a1b2c3d4/`），目录内一切保持原名（含嵌套子目录）——内部相对引用（`./`、`../`）完整有效。

### 目录边界（重要）

**① 目录应自包含** — 相对引用只能指向同一顶层目录内部。跨顶层目录引用在哈希后会断裂：

```ts
// ❌ pkg1 引用 pkg2（pkg2 被哈希改名）→ 断裂
// ✅ 需要互相引用的文件放在同一顶层目录
```

**② `dir` 指向最顶层自包含单元**，`entry` 用相对子路径：

```ts
// ❌ 只提取 win/，若 start.bat 引用 ../shared/ 会断
{ dir: getAssetsPath('native/win/'), entry: 'start.bat' }

// ✅ 提取整个 native/，内部引用完整
{ dir: getAssetsPath('native/'), entry: 'win/start.bat' }
```

**③ `{ file }` 只提取单文件** — 该文件对目录内其他文件的引用会失效（不被提取）。需要自包含请用 `{ dir, entry }`。

---

## 构建时的自动处理

主项目 `build.js` 使用两个 esbuild 插件（`yeow-assets.mjs`），对依赖包**透明**：

### 1. dedupe 插件

所有 `import 'yeow-api'`（无论来自主项目还是依赖包）统一解析到**主项目的 yeow-api 实例**。

> **为什么必须**：若依赖包自带 yeow-api 副本，bundle 会出现两份 lifecycle 模块，`globalThis.__yeowInitCbs` 被后者覆盖，插件回调丢失（表现为 `onLoad` 不执行）。

### 2. 资产插件

扫描主项目 + 所有依赖包的 `assets/`，统一哈希后进 JAR。依赖包条目**不覆盖**主项目同名条目。

---

## 封装 Service 的包（三种类型）

Service 是 Yeow 包最常见的封装对象。根据**包的角色**（调用方 / 服务方）与**服务来源**，分为三类：

| 类型                       | 包的角色         | 是否注册服务  | 服务来源                     | 典型场景                                                   |
| -------------------------- | ---------------- | :-----------: | ---------------------------- | ---------------------------------------------------------- |
| **1. SDK（调用方语法糖）** | 纯调用方         |       ❌       | 其他插件已注册的服务         | 经济、领地、商店等插件对外暴露的服务                       |
| **2. JS 服务（全局唯一）** | 服务方 + 调用方  | ✅（失败降级） | 本包注册（JS 逻辑）          | 需要全局唯一状态的通用功能（统计、聊天格式化、跨插件数据） |
| **3. 原生服务**            | 服务方（子进程） | ✅（失败降级） | 本包注册（`assets/` 二进制） | 图像处理、机器学习等重计算                                 |

**组合规则**：类型可以组合使用。最典型的是 **类型 2 + 3**——包注册一个 JS 服务作为**门面**（对外路径约定、事件发布），内部再启动原生子进程作为**引擎**（重计算）。类型 2 完整包含类型 1 的调用方封装（降级后即退回类型 1 的形态）。

### 类型 1 —— SDK（调用方语法糖）

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

### 类型 2 —— JS 服务（全局唯一）

包内实现服务方逻辑（纯 JS），入口处尝试注册：**成功 → 本插件成为唯一服务实例；失败（同名 public 服务已存在）→ 降级为调用方**，用 `err.serviceId` 接入既有服务：

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
```

要点：
- **服务唯一**：多个插件引入同一包时，第一个注册的插件成为服务方，其余自动降级为调用方。所有插件的 `record()` 最终都请求到**同一个服务实例**，行为一致
- **全局唯一状态**：服务方的状态（如 `store`）只存在于服务方插件的 JS 上下文中——上下文不可跨插件共享，这正是"全局唯一"的价值
- 降级后本包的调用封装依然可用（经 `err.serviceId` 路由到既有服务），调用方无需感知

### 类型 3 —— 原生服务

包内携带二进制（`assets/`），用 `registerNativeService` 按平台提取并启动子进程，再封装调用。核心模式：

```ts
// yeow-image —— 原生服务
import { registerNativeService, getAssetsPath, serviceRequest } from 'yeow-api';

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
- 完整示例见下文「完整示例」

### 组合 —— JS 门面 + 原生引擎（类型 2 + 3）

JS 服务作为门面（对外路径约定、事件发布），原生子进程作为引擎（重计算）。包同时承担服务方（注册 JS 服务）与子进程管理者两个角色：

```ts
// yeow-image-svc —— 类型 2 + 3 组合
import { registerService, registerNativeService, serviceRequest, servicePublish, getAssetsPath } from 'yeow-api';

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

### 如何选择

| 你的场景                              | 类型      |
| ------------------------------------- | --------- |
| 服务由其他插件提供，只需写调用封装    | **1**     |
| 需要全局唯一的逻辑/状态，多个插件共享 | **2**     |
| 需要二进制/重计算能力                 | **3**     |
| JS 门面 + 原生引擎                    | **2 + 3** |

---

## 完整示例

以 `yeow-image` 包为例（对应 `Yeow-Test/test/yeow-image`）：

> **serviceId 命名规范**：公共服务可能被多个依赖它的包加载（同名 public 服务全局唯一——重复注册会被拒绝，调用方用 `err.serviceId` 降级接入，见上文类型 2）。为避免不同作者的包冲突，serviceId 应写明 `作者.服务名.版本`，例如 `iyexin.image-svc.v1`：

```ts
// src/index.ts
import { registerNativeService, getAssetsPath, serviceRequest } from 'yeow-api';

export const IMAGE_SERVICE = 'iyexin.image-svc.v1';

/**
 * 支持平台：linux / windows / macos，按架构细分（见下方"支持平台"表）
 */
export interface RenderResult {
    base64?: string;
    err?: string;
}

export interface ImageRenderer {
    serviceId: string;
    render(width: number, height: number, pixels: Uint8Array): Promise<RenderResult>;
}

export async function initRenderer(): Promise<ImageRenderer> {
    const { serviceId, ready } = await registerNativeService(IMAGE_SERVICE, {
        'linux-x64':   getAssetsPath('native/linux-x64/image-svc'),
        'linux-arm64': getAssetsPath('native/linux-arm64/image-svc'),
        'windows-x64': getAssetsPath('native/windows-x64/image-svc.exe'),
        'macos-x64':   getAssetsPath('native/macos-x64/image-svc'),
        'macos-arm64': getAssetsPath('native/macos-arm64/image-svc'),
    });
    await ready();

    return {
        serviceId,
        async render(width, height, pixels) {
            const base64 = pixels.toBase64(); // ES2026 原生；兼容旧版可用全局 uint8ArrayToBase64(pixels.buffer)
            return serviceRequest(serviceId, '/imageRender', {
                width,
                height,
                base64,
            }) as Promise<RenderResult>;
        },
    };
}
```

**支持平台**：key 支持 `操作系统` 或 `操作系统-架构` 两种粒度，精确匹配（含架构）优先，找不到回退到操作系统：

```
assets/
├── native/
│   ├── linux-x64/image-svc
│   ├── linux-arm64/image-svc
│   ├── windows-x64/image-svc.exe
│   ├── macos-x64/image-svc
│   └── macos-arm64/image-svc
```

| key                                   | 说明                        |
| ------------------------------------- | --------------------------- |
| `windows` / `windows-x64`             | Windows（x64 或任意架构）   |
| `linux` / `linux-x64` / `linux-arm64` | Linux x86_64 / ARM64        |
| `macos` / `macos-x64` / `macos-arm64` | macOS Intel / Apple Silicon |

> **建议至少提供 `windows-x64` + `linux-x64` + `linux-arm64`**：绝大多数 Paper 服务器部署在 Linux x64 VPS、Linux ARM（树莓派/NAS/ARM 云主机）或 Windows x64。缺少当前平台的配置时，注册返回错误 `No binary for platform: <os> (<os>-<arch>)`。

使用者在主插件中：

```json
// 主插件 package.json
{
    "dependencies": {
        "yeow-api": "^0.2.57",
        "yeow-image": "^0.0.1"   // 示例
    }
}
```

```ts
// 主插件 src/index.ts
import { initRenderer } from 'yeow-image';

onLoad(async () => {
    const renderer = await initRenderer();
    const result = await renderer.render(2, 2, pixels);  // Uint8Array，base64 编码封装在包内
});
```

> **使用者必须声明权限**：本示例的 `initRenderer` 内部调用 `registerNativeService`，使用者插件需在 `yeow.config.json` 中声明 `"service:registerNative"`，否则 `initRenderer()` 会抛 `Permission denied`。包 README 必须注明（见上文[权限声明](#权限声明)）。

> **封装建议**：把 `serviceId`、就绪等待、base64 编码等细节全部封装在包内，对外只暴露高层操作函数（如 `renderer.render()`）。使用者无需了解 Native Service 的存在。

---

## 检查清单

- [ ] `files` 包含 `assets/`
- [ ] `main` / `types` 指向 `src/index.ts`
- [ ] `peerDependencies` 声明 yeow-api 版本范围
- [ ] `devDependencies` 声明同版本 yeow-api（独立开发类型检查）
- [ ] 用到 fs/http/registerNative/assetsExtract 时，README 注明使用者需声明的权限节点
- [ ] 资源通过 `getAssetsPath()` 获取路径，不手写
- [ ] 目录资源用尾部 `/` 路径 + `{ dir, entry }` 模式
