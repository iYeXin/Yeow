# Assets API

```js
import { getAssetsPath, assets } from 'yeow-api';
```

`assets/` 目录下的文件在构建时自动打包进 JAR，运行时通过此 API 读取。

## getAssetsPath(path)

获取 `assets/` 目录下资源文件的 JAR 内路径。**构建时自动哈希、去重**，返回的路径可传给所有需要字符串路径的 API。

```ts
getAssetsPath('icon.png'): string
// → "assets/icon.a1b2c3d4.png"

getAssetsPath('native/win/svc.exe'): string
// → "assets/native.a1b2c3d4/win/svc.exe"
```

如果文件不存在或未被打包，**原样返回**传入路径。

### 目录 vs 文件

- **文件路径**（如 `native/win/svc.exe`）— 返回文件在 JAR 中的路径
- **目录路径**（以 `/` 结尾，如 `native/win/`）— 返回目录路径（`assets/native.a1b2c3d4/win/`）

**哈希规则**：

- **根级文件**（`assets/` 直接子文件）— 独立哈希 `name.hash.ext`
- **顶层目录**（`assets/` 直接子目录）— **整体哈希** `name.<dirHash>/`（内容变则哈希变）
- **顶层目录内部一切保持原名**（含嵌套子目录）— 目录内所有相对引用（`./`、`../`）完整有效

```ts
// 布局: assets/native/win/{start.bat, app.js, modules/moduleA.js}
getAssetsPath('native/')           // → "assets/native.a1b2c3d4/"
getAssetsPath('native/win/')       // → "assets/native.a1b2c3d4/win/"
getAssetsPath('native/win/app.js') // → "assets/native.a1b2c3d4/win/app.js"   ← 原名
getAssetsPath('native/win/modules/moduleA.js') // → "assets/native.a1b2c3d4/win/modules/moduleA.js"
```

### 边界与注意事项

**① 目录应自包含（最重要）**

相对引用只能指向**同一顶层目录内部**。跨顶层目录的引用在哈希后会断裂：

```ts
// ❌ 断裂: pkg1 引用 pkg2 的内容（pkg2 被哈希改名）
//    pkg1/<hash>/tool.js 中引用 ../../pkg2/helper.js → 找不到

// ✅ 自包含: 需要互相引用的文件放在同一个顶层目录
assets/native/<hash>/...
```

**② `{ file }` 模式只提取单文件**

`{ file: getAssetsPath(...) }` 只解压**一个文件**，该文件对目录内其他文件的相对引用会失效。需要保持内部引用的请用 `{ dir, entry }` 模式。

**③ `{ dir }` 指向顶层自包含单元**

`dir` 应指向**包含全部依赖的最顶层目录**，`entry` 用相对子路径：

```ts
// ❌ 只提取 win/，start.bat 引用 ../shared/ 会断
{ dir: getAssetsPath('native/win/'), entry: 'start.bat' }

// ✅ 提取整个 native/（自包含），内部引用完整
{ dir: getAssetsPath('native/'), entry: 'win/start.bat' }
```

**④ 根级文件引用（碰巧可用，不推荐依赖）**

目录内引用根级文件（`../../config.json`）恰好可用（根级文件仍独立哈希在 `assets/` 根），但这是隐式依赖，建议把共享文件也放进同一目录。

> 构建时 esbuild 会拦截 `__yeow-assets` 虚拟模块，扫描整个 `assets/` 目录树（含依赖包的 `assets/`），根级文件与顶层目录按内容 MD5 哈希，复制到 `dist/.assets/`（或 `dist/.dev/.assets/`），最后打包进 JAR。

> **⚠ 路径必须通过 `getAssetsPath()` 获取**：构建时资源会被哈希改名（如 `template.txt` → `template.a1b2c3d4.txt`），硬编码原始路径在运行时**找不到文件**。所有 assets API 的 `path` 参数都应传入 `getAssetsPath()` 的返回值。

## assets.read(path) / assets.readSync(path)

读取资产文件为 UTF-8 字符串。

```ts
assets.read(getAssetsPath('template.txt')): Promise<string>
assets.readSync(getAssetsPath('template.txt')): string
```

## assets.readBase64(path) / assets.readBase64Sync(path)

读取资产文件为 Base64 编码字符串。

```ts
assets.readBase64(getAssetsPath('image.png')): Promise<string>
assets.readBase64Sync(getAssetsPath('image.png')): string
```

## assets.extract(path, dest?) / assets.extractSync(path, dest?)

解压资产文件到文件系统。默认解压到 `plugins/<插件名>/assets/<path>`。

```ts
assets.extract(getAssetsPath('config.json')): Promise<string>
assets.extractSync(getAssetsPath('config.json')): string
```

返回解压后的绝对路径。

> **权限**：`assets:extract` 默认拒绝，须在 `yeow.config.json` 的 `permissions` 中声明 `"assets:extract"`。`assets.read` / `assets.readBase64` 默认允许。

## 示例

```js
import { getAssetsPath, assets } from 'yeow-api';

// 读取配置（路径经 getAssetsPath 哈希解析）
const config = assetsReadSync(getAssetsPath('config.yml'));

// 解压资源到文件系统
await assets.extract(getAssetsPath('icon.png'));

// Native Service（自动哈希路径）
const { serviceId } = await registerNativeService('renderer', {
    windows: getAssetsPath('native/renderer.exe'),
    linux: getAssetsPath('native/renderer'),
});
```
