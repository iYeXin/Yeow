# Assets API

```js
import { getAssetsPath } from 'yeow-dev';   // 构建期虚拟模块
import { assets } from 'yeow-api';
```

`assets/` 目录下的文件在构建时自动打包进 JAR，运行时通过此 API 读取。

> `getAssetsPath` 从 **`yeow-dev`**（构建期虚拟模块）引入，而非 `yeow-api`：它按调用方所属依赖项注入命名空间，只有构建器知道当前代码属于哪个包。`yeow-dev` 已发布为空包（可不安装，类型声明由 `yeow-api` 提供）。

## getAssetsPath(path)

获取 `assets/` 目录下资源文件的 JAR 内路径。返回的路径可传给所有需要字符串路径的 API。

```ts
getAssetsPath('icon.png'): string
// → "assets/9f2c8a41/icon.png"

getAssetsPath('native/win/svc.exe'): string
// → "assets/9f2c8a41/native/win/svc.exe"
```

**路径规则**：构建时每个依赖项（主项目与满足条件的 npm 包）分配一个**唯一命名空间 id**（8 位十六进制），其 `assets/` 内容**原样**复制到 `assets/<id>/` 下——文件**不会改名**，`getAssetsPath` 只是在传入路径前加上 `assets/<id>/` 前缀。

- **文件路径**（如 `native/win/svc.exe`）→ `assets/<id>/native/win/svc.exe`
- **目录路径**（以 `/` 结尾，如 `native/win/`）→ `assets/<id>/native/win/`（保留尾斜杠）
- 路径会被规范化（`./`、`..` 解析，且不会逃出 `assets/<id>/`）

### 相对引用（无限制）

因为文件**不哈希改名**，`assets/` 内部（含跨目录）的任何相对引用——无论来自被引用文件的内容（配置、脚本、`../` 兄弟引用）还是 `{ dir, entry }` 的原生服务——都**永远有效**。不再有「目录应自包含」「跨顶层目录断裂」的限制：

```ts
// 布局: assets/native/win/{start.bat, app.js, modules/moduleA.js}
getAssetsPath('native/')           // → "assets/<id>/native/"
getAssetsPath('native/win/')       // → "assets/<id>/native/win/"
getAssetsPath('native/win/app.js') // → "assets/<id>/native/win/app.js"
getAssetsPath('native/win/modules/moduleA.js') // → "assets/<id>/native/win/modules/moduleA.js"
```

### 命名空间隔离

每个依赖项的 assets 有独立的 `<id>` 命名空间，**同名文件互不冲突**（主项目与依赖包、依赖包与依赖包之间都是如此）——不再需要「依赖包不覆盖主项目」的规则。

### 依赖项识别（node_modules 扫描）

构建器扫描 `node_modules` 顶层目录（含 `@scope/name`），以 `<name>-<version>` 为键识别依赖项：

- **识别条件**：包存在 `assets/` 目录，且 `peerDependencies` 含 `yeow-api` 键
- **主项目**：有 `assets/` 即参与（始终分配 id）
- **兼容性**：npm / pnpm 的扁平布局支持良好；yarn 的 hoisting 差异可能导致依赖不在预期位置，如遇问题请使用 npm 或 pnpm

### 目录边界

**`{ file }` 模式只提取单文件**——该文件对目录内其他文件的相对引用会失效。需要保持内部引用的请用 `{ dir, entry }` 模式：

```ts
// ✅ dir 指向包含全部依赖的最顶层目录，entry 用相对子路径
{ dir: getAssetsPath('native/'), entry: 'win/start.bat' }
```

> 构建时 esbuild 拦截 `yeow-dev` 虚拟模块：扫描各依赖项的 `assets/`，原样复制到 `dist/.assets/<id>/`（或 `dist/.dev/.assets/<id>/`），按 importer 归属注入命名空间 id，最后打包进 JAR。

> **⚠ 路径必须通过 `getAssetsPath()` 获取**：即使文件不哈希改名，路径前缀 `assets/<id>/` 也是构建期生成的（id 每次构建可能变化）——硬编码原始路径或 `assets/...` 字面量在运行时**找不到文件**。所有 assets API 的 `path` 参数都应传入 `getAssetsPath()` 的返回值。

## assets.read(path, options?) / assets.readSync(path, options?)

读取资产文件。与 `fs.readFile` 相同语义：**默认返回二进制 `Uint8Array`**，显式指定编码后返回字符串。

```ts
assets.read(getAssetsPath('image.png')): Promise<Uint8Array>
assets.readSync(getAssetsPath('image.png')): Uint8Array

assets.read(getAssetsPath('template.txt'), 'utf8'): Promise<string>
assets.readSync(getAssetsPath('template.txt'), { encoding: 'utf8' }): string

assets.read(getAssetsPath('blob.bin'), 'base64'): Promise<string>
assets.readSync(getAssetsPath('blob.bin'), 'base64'): string
```

`options` 可为 `'utf8' | 'base64'` 或 `{ encoding: 'utf8' | 'base64' }`。底层协议仍使用 `assets:read`（文本）与 `assets:readBase64`（二进制 Base64 承载）节点。

## assets.extract(path, dest?) / assets.extractSync(path, dest?)

解压资产文件到文件系统。默认解压到 `plugins/<插件名>/assets/<path>`。

```ts
assets.extract(getAssetsPath('config.json')): Promise<string>
assets.extractSync(getAssetsPath('config.json')): string
```

返回解压后的目标路径（**相对服务器根目录**，如 `plugins/<插件名>/assets/config.json`；配合 `fs.outer.getServerPath()` 可拼出绝对路径）。

> **权限**：`assets:extract` 默认拒绝，须在 `yeow.config.json` 的 `permissions` 中声明 `"assets:extract"`。`assets.read` 默认允许。

## assets.extractDir(path, dest?) / assets.extractDirSync(path, dest?)

**目录**整体提取到文件系统（递归，保持内部相对结构）。`path` 指向 `assets/` 下的一个目录（如 `native/`）；`dest` 默认 `plugins/<插件名>/assets/<path>`。

```ts
assets.extractDir(getAssetsPath('native/')): Promise<string>
assets.extractDirSync(getAssetsPath('native/')): string
```

返回解压后的目标目录路径（**相对服务器根目录**）。与 `extract` 的差异：`extractDir` 提取整个目录树（含嵌套子目录），适合需要完整保留内部引用的资源集。

> **权限**：`assets:extractDir` 是**独立节点**，默认拒绝，须在 `yeow.config.json` 的 `permissions` 中声明 `"assets:extractDir"`（或 `"assets:*"`）——**`"assets:extract"` 不覆盖目录提取**。未声明调用返回 `Permission denied: assets:extractDir`。

## 示例

```js
import { getAssetsPath } from 'yeow-dev';
import { assets } from 'yeow-api';

// 读取配置（路径经 getAssetsPath 解析；文本需显式 utf8）
const config = assetsReadSync(getAssetsPath('config.yml'), 'utf8');

// 二进制读取（默认 Uint8Array）
const icon = assetsReadSync(getAssetsPath('icon.png'));

// 解压资源到文件系统
await assets.extract(getAssetsPath('icon.png'));

// Native Service（自动注入命名空间路径）
const { serviceId } = await registerNativeService('renderer', {
    windows: getAssetsPath('native/renderer.exe'),
    linux: getAssetsPath('native/renderer'),
});
```
