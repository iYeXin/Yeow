# 编写 Yeow 依赖包指南

Yeow 插件可以把共享逻辑和资源封装为 npm 包，供其他插件复用。典型场景：

- 封装 Service（三类包：SDK / JS 服务 / 原生服务，见下文「[封装 Service 的包（三种类型）](#封装-service-的包三种类型)」）
- 封装 Native Service（如 `yeow-image` 打包 `image-svc.exe` + 注册/调用逻辑）
- 共享工具函数（如 `yeow-command`、`yeow-server`）
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
        "yeow-api": "^0.4.0"
    },
    "devDependencies": {
        "yeow-api": "^0.4.0"
    },
    "license": "MIT"
}
```

### 关键字段

| 字段               | 说明                                                                                                                                                                                       |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `main` / `types`   | 指向 `src/index.ts` 源码（构建时由主项目 esbuild 直接打包源码，无需预编译）                                                                                                                |
| `files`            | **必须包含 `assets/`**，否则发布到 npm 时资源不会随包分发                                                                                                                                  |
| `peerDependencies` | 契约 + **构建期识别标记**：`yeow-api` 版本范围。声明"使用者插件必须安装 yeow-api"；构建器也以此为识别条件（配合 `assets/`）决定是否把本包资源打包进 JAR 并合并权限（见下文「依赖项识别」） |
| `devDependencies`  | 开发期依赖：独立开发时 `import 'yeow-api'` 需要类型定义和 IDE 提示                                                                                                                         |


```bash
npm install --save-dev yeow-api
```

### 两个声明的分工

| 声明               | 作用域                              | 用途                                                                                                                                                                                                                                                                     |
| ------------------ | ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `peerDependencies` | 使用者侧（npm 安装期 + **构建期**） | 契约：使用者的插件必须有 yeow-api。范围不重叠时 npm 会为包安装独立 yeow-api 副本——**多副本可安全共存**（见下文「构建时的自动处理」）。**同时是构建器识别依赖项的标记**——存在 `assets/` 且 peer 含 `yeow-api` 的包才会参与资产打包与权限合并（漏声明 → 资源静默不进 JAR） |
| `devDependencies`  | 开发期（作者侧）                    | 独立开发时的类型/提示；**不随包发布**，不影响运行时                                                                                                                                                                                                                      |

**版本策略（两个范围、各司其职，不必相同）**：

- `devDependencies` = **开发目标版本**——你开发/类型检查时用的版本，可窄
- `peerDependencies` = **兼容的最宽范围**——npm 7+ 在范围不重叠时会为消费端**另装一份 yeow-api**（bundle 体积增加）；宽范围避免重复安装。只用了各版本共有 API 的老包可写 `^0.3.0 || ^0.4.0`；依赖包源码会被使用者的 `tsc --noEmit`（构建时 typecheck）检查，只要包只用范围内版本都有的 API，宽范围天然安全。**即使版本不兼容也不影响运行**：多个 yeow-api 副本共享生命周期/事件/GC 全局注册表，可安全共存（见下文）

---

## 权限声明

敏感消息节点默认拒绝，必须由**依赖此包的主项目**在 `yeow.config.json` 的 `permissions` 字段声明（构建时自动合并计算进 `computedPermissions`）：

| 需要声明的节点                                                 | 包内对应能力                                                                        |
| -------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| `fs:server.*` / `fs:outer.*`（或 `fs:server.readFile` 等节点） | `fs.server.*` / `fs.outer.*` API（服务器根/任意路径）；`fs.*`（插件数据目录）免声明 |
| `http:*`（或 `http:requestAsync` 等节点）                      | `fetch`、`request`、HTTP 服务器（`createServer`/`listen`）                          |
| `service:registerNative`                                       | `registerNativeService`（spawn 原生子进程）                                         |

> `assets` 通道（`assetsExtract` / `assetsExtractDir` 解压）**不设权限拦截**：解压目标强制限定在插件数据目录 `plugins/<插件名>/` 内（越界返回错误），无需声明。

规则：

- 节点级（`fs:server.readFile`）、整组通配（`fs:server.*`）与通道通配（`fs:*`）均可；未声明调用返回 `Permission denied: <node>`（异步 API 为 Promise reject）
- 其余节点（`service:request`、`assets:read`、`fs.*` 等）默认允许
- 权限在插件**加载时**读取并固定；使用者修改 `permissions` 后需重新构建并**完整重载插件**（`/yeow reload` 或重启服务器）生效——若处于开发模式，热重载同时更新权限

---

## 使用 getAssetsPath 访问资源

包内部通过 `getAssetsPath()` 获取 `assets/` 中文件的 JAR 内路径：

```ts
// src/index.ts
import { registerNativeService } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';   // 构建期虚拟模块

export const IMAGE_SERVICE = 'iyexin.image-svc.v1';

export async function registerImageService(): Promise<string> {
    const { serviceId, ready } = await registerNativeService(IMAGE_SERVICE, {
        windows: getAssetsPath('image-svc.exe'),
    });
    await ready();
    return serviceId;
}
```

> **为什么从 `yeow-dev` 引入？** `getAssetsPath` 必须知道调用代码属于哪个依赖项（以注入对应的命名空间 id），而 `yeow-dev` 是构建期虚拟模块——构建器按 importer 归属解析。`yeow-dev` 已发布为空包（可不安装，类型声明由 `yeow-api` 提供）。

**构建时自动处理：**

- 构建器扫描主项目与所有依赖包的 `assets/`，为每个依赖项分配唯一命名空间 id（8 位十六进制），内容**原样**复制到 JAR `assets/<id>/`
- `getAssetsPath('image-svc.exe')` 在构建后返回 `"assets/<id>/image-svc.exe"`（真实路径）
- **无需任何额外配置** — 构建插件自动扫描所有依赖包（见下文「依赖项识别」）

### 目录资源

如果入口脚本需要引用同目录的兄弟文件（脚本内相对引用），用目录级路径：

```ts
const svc = await registerNativeService('my-svc', {
    windows: { dir: getAssetsPath('native/'), entry: 'win/start.ps1' },
});
```

**无哈希**：所有文件**保持原名**（含嵌套子目录）——`assets/` 内部（含跨目录）的任何相对引用（`./`、`../`）都**永远有效**，不再有「目录应自包含」「跨顶层目录断裂」的限制。

### 目录边界

**`dir` 指向包含全部依赖的最顶层目录**，`entry` 用相对子路径：

```ts
// ✅ 提取整个 native/，内部引用完整
{ dir: getAssetsPath('native/'), entry: 'win/start.bat' }
```

**`{ file }` 只提取单文件** — 该文件对目录内其他文件的引用会失效（不被提取）。需要自包含请用 `{ dir, entry }`。

---

## 构建时的自动处理

主项目 `build.js` 使用 esbuild 资产插件（`yeow-assets.mjs`），对依赖包**透明**：

### 1. yeow-api 多副本共存（无去重）

构建器**不强制**把 `import 'yeow-api'` 统一到主项目实例——安装由包管理器按语义化版本规则自行决定：peer 范围不重叠（如主项目 `^0.4.0` 与依赖包 `^0.3.0`）时，npm 为依赖包安装**独立 yeow-api 副本**，bundle 中两个副本共存。

> **为什么安全**：yeow-api 对底层协议是纯封装（协议 1.0.0 之后无破坏性变更），多副本只是重复封装同一协议；生命周期钩子（`onInit` / `onLoad` / `onUnload`）注册在**共享全局注册表**（`__yeowInitCbs` 等——读已有、绝不覆盖），事件处理器与句柄 GC 队列同样共享——多个副本的回调进入同一注册表，运行时一次 INIT/LOAD 分发**全部执行**，不会互相覆盖丢失（曾因覆盖 `__yeowInitCbs` 表现为 `onLoad` 不执行）。

### 2. 资产插件

扫描主项目 + 所有依赖包的 `assets/`，按命名空间部署进 JAR。

### 依赖项识别（node_modules 扫描）

构建器扫描 `node_modules` 顶层目录（含 `@scope/name` 两级），以 `<name>-<version>` 为键识别依赖项：

- **识别条件**：包存在 `assets/` 目录，且 `peerDependencies` 含 `yeow-api` 键
- **主项目**：有 `assets/` 即参与（始终分配 id）
- **同名冲突**：各依赖项有独立命名空间，同名文件互不覆盖
- **兼容性**：npm / pnpm 的扁平布局支持良好；yarn 的 hoisting 差异可能导致依赖不在预期位置，如遇问题请使用 npm 或 pnpm

### 依赖包权限声明（yeow.config.json）

依赖包可以自带 `yeow.config.json`，目前只需声明 `permissions`：

```json
{
    "permissions": ["fs:server.readFile", "http:*", "service:registerNative"]
}
```

**每个包只需声明自己的权限**——npm/pnpm 的包是扁平分布的，node_modules 顶层的包（直接依赖与被提升的传递依赖）都会参与计算，但包无需考虑其依赖所需的权限：权限由使用者的插件构建时统一汇总，依赖包只要把自己的权限声明清楚即可。缺失 `yeow.config.json` 或 `permissions` 字段的依赖包不贡献任何权限。

### 最终权限（computedPermissions）

开发者声明的 `permissions` 保持原样，构建时自动计算最终生效权限：

- **合并**：主项目在前、依赖包按序追加、自动去重
- **通配归一化**：存在 `X:*`（如 `fs:*`）时，该通道其余节点（`fs:server.*`、`fs:server.readFile` 等）自动移除；存在 `X:段.*`（如 `fs:server.*`）时，该前缀节点（`fs:server.readFile`）自动移除——通配已覆盖，无需冗余声明
- **`fs:*` 展开**：声明 `fs:*` 后，computedPermissions 中自动展开为 `fs:outer.*, fs:server.*`（权限语义等价——`plugin` 段节点默认允许，server/outer 由各自通配覆盖）——让开发者与服主对实际影响范围（任意路径 + 服务器根）有明确感知
- **写回**：结果写入 `yeow.config.json` 的 `computedPermissions` 字段（保留开发者声明的 `permissions`），打包时写入 `yeow.json` 供运行时读取；构建终端同步打印

查看计算过程与权限来源分布：

```bash
npm run permissions
```

```
── Permissions by source ─────────────────────────
  fs:server.*                 ← my-plugin-1.0.0
  fs:server.readFile          ← yeow-test-pkg-1.0.0
  http:*                      ← yeow-test-pkg-1.0.0
  service:registerNative      ← yeow-test-pkg-1.0.0

── Computed permissions (3) ─────────────────
  fs:server.*
  http:*
  service:registerNative
```

每个权限都能看到它声明自哪个包，便于排查权限缺失与冗余。

### 原生服务可信性声明（native）

依赖包携带原生服务二进制时，建议在 `yeow.config.json` 声明 `native` 字段固定 SHA-256：

```json
{
    "native": [
        {
            "serviceId": "iyexin.image-svc.v1",
            "files": ["native/win/image-svc.exe"],
            "source": "https://github.com/iyexin/image-svc"
        }
    ]
}
```

- `files` 为**本包** `assets/` 下的二进制原始路径（与 `getAssetsPath` 使用的路径一致）
- 构建时自动映射为打包后路径（`assets/<id>/...`）并计算 SHA-256，写入 `yeow.json` 的 `native` 字段；主项目与依赖包声明相同 `serviceId` 时合并（files 归并）
- 运行时注册该原生服务时校验哈希：不匹配 → 拒绝加载（Promise reject）；无论是否声明都会打印风险日志。详见[权限与原生服务可信性](permissions.md#二原生服务可信性声明)

---

## 封装 Service 的包

封装 Service（插件间通信 / 原生扩展）的**三种类型**（SDK 调用封装 / JS 服务 / 原生服务）与组合模式（JS 门面 + 原生引擎）已独立成篇：[封装 Service 的依赖包](package-service.md)。其中原生服务的**可信性声明与批准机制**见[权限与原生服务可信性](permissions.md)。

---

## 完整示例

以 `yeow-image` 包为例（对应 `Yeow-Test/test/yeow-image`）：

> **serviceId 命名规范**：公共服务可能被多个依赖它的包加载（同名 public 服务全局唯一——重复注册会被拒绝，调用方用 `err.serviceId` 降级接入，见上文类型 2）。为避免不同作者的包冲突，serviceId 应写明 `作者.服务名.版本`，例如 `iyexin.image-svc.v1`：

```ts
// src/index.ts
import { registerNativeService, serviceRequest } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

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
            const base64 = pixels.toBase64(); // ES2026 原生
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
        "yeow-api": "^0.4.0",
        "yeow-image": "^0.0.1"   // 示例
    }
}
```

```ts
// 主插件 src/index.ts
import { initRenderer } from 'yeow-image';

onLoad(async () => {
    const renderer = await initRenderer();
    const result = await renderer.render(2, 2, pixels);
});
```

> **依赖包声明权限**：yeow-image 应当在自己的 `yeow.config.json` 中声明 `permissions: ['service:registerNative']`

> **封装建议**：把 `serviceId`、就绪等待等细节全部封装在包内，对外只暴露高层操作函数（如 `renderer.render()`）。使用者无需了解 Native Service 的存在。

---

## 检查清单

- [ ] `files` 包含 `assets/`
- [ ] `main` / `types` 指向 `src/index.ts`
- [ ] `peerDependencies` 声明 yeow-api 版本范围（兼容的最宽范围；漏声明 → 资产/权限不参与构建）
- [ ] `devDependencies` 声明开发目标版本的 yeow-api（独立开发类型检查）
- [ ] 用到 fs/http/registerNative 时，在自己的 `yeow.config.json` 中声明
- [ ] 资源通过 `getAssetsPath()` 获取路径，不手写
