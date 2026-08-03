# CLI 参考

## create-yeow

```bash
npm create yeow@latest [-- options]
```

创建 Yeow 插件项目。

### 参数

| 参数               | 说明                          |
| ------------------ | ----------------------------- |
| `-y`               | 非交互模式，使用默认值        |
| `--name=<value>`   | 项目名（默认 my-yeow-plugin） |
| `--author=<value>` | 作者名                        |
| `--js`             | 生成 JavaScript 项目（默认）  |
| `--ts`             | 生成 TypeScript 项目          |
| `--no-typecheck`   | 跳过类型检查（仅 TS）         |

### 语言选择

交互模式下使用 **Tab 切换**、**Enter 确认** 选择 JavaScript 或 TypeScript：

```
  ▸ JavaScript ◂    TypeScript         ← Tab 切换，Enter 确认
```

默认 **JavaScript**。TypeScript 项目在构建时自动运行 `tsc --noEmit` 类型检查，可在 `yeow.config.json` 中设置 `"typecheck": false` 关闭。

![创建 Yeow 项目](assets/create-yeow.png)

### 示例

```bash
npm create yeow@latest -- -y                    # JS 项目（默认）
npm create yeow@latest -- -y --ts               # TS 项目
npm create yeow@latest -- -y --ts --no-typecheck # TS 项目跳过类型检查
npm create yeow@latest -- -y --name=survival --author=Notch
```

## 开发服务器

```bash
npm run dev [-- options]
```

启动 Paper 测试服务器 + 热重载。`npm run dev` 自动添加 `-Dyeow.dev=true` 以启用开发模式，`npm run build` 构建生产包，不含此标记。

### 参数

| 参数                  | 说明                           |
| --------------------- | ------------------------------ |
| `-y`                  | 跳过提示，自动接受 EULA        |
| `--stop=<N><s\|m\|h>` | 自动停止，如 `60s`、`5m`、`2h` |
| `--proxy=<url>`       | Paper 下载代理                 |

### 工作流程

1. 检查本地 Paper 1.21.4 缓存，没有则下载
2. 启动 WebSocket 服务端（端口 17368）
3. 以开发模式（`-Dyeow.dev=true`）启动 Paper 服务器
4. 构建插件：生成 `.yeow/dev.json`（含源码路径）而非打包代码；部署 `.yeow.zip`（含 dev.json）到 `plugins/Yeow/`（dev-server 自动创建该目录，运行时自动扫描加载）
5. Java 运行时通过 WebSocket 连接 dev-server
6. 监听 `src/` + `assets/` 文件变化 → 自动重新打包 → 通过 WebSocket 发送热重载消息
7. 运行时收到消息 → 销毁旧 QuickJS 上下文 → 加载新代码 → 热重载完成
8. 开发模式下 `assets` API 直接从文件系统目录读取，无需打包

> 热重载期间插件保持运行，不需要 `/reload` 或重启服务器。

> 开发模式下若 `plugins/` 中存在旧版本的同名模板 JAR（早期版本部署遗留），dev-server 会自动移除，避免与 `plugins/Yeow/` 中的 `.yeow.zip` 产生同名冲突。

### 调试体验

开发模式（`npm run dev`）下，dev-server 提供完整的错误定位体验。以下能力**仅在开发环境启用**（`-Dyeow.dev=true`）——生产构建不携带 source-map、不捕获异步栈，错误只输出到服务器日志。

#### 源码映射（Source Map）

插件源码经 esbuild 打包为 `dist/.dev/main.js`（附带 `main.js.map`）。运行时错误通过 WebSocket 上报 dev-server 后，`source-map` 库把编译后位置反解回**原始源码**：

```
  ⛔ JS Error [my-yeow-plugin]
  'test1' is not defined
  at src/index.js:29:0
        27| });
        28|
    →   29| test1();
        30|
```

![dev-server 错误定位展示](assets/error-show.png)

- 自动定位**用户源码**中的出错帧，显示错误行 ±3 行上下文与 `→` 定位符
- 行号/列号直接对应 `src/` 里的真实代码，而不是打包产物

#### 异步调用栈追踪

异步错误很难定位：错误发生在回调或微任务里，而问题出在**注册异步调用**的代码行。Yeow 在开发模式下重建完整异步调用链，出错时附加到错误信息尾部：

```
Stack:
    at <anonymous> (src/index.js:56:10)            ← 错误抛出点
    at <anonymous> (init.js:262:41) (internal)     ← 运行时内部帧（灰显）
    --- promise chain ---
    at <anonymous> (src/index.js:55:29)            ← .then 调用点
    at doSomething (src/index.js:53:15)            ← 回调注册点（用户帧）
    at <anonymous> (src/index.js:61:14)            ← onLoad 回调
    at _runLifecycleCallbacks (init.js:154:32) (internal)
    --- outer callback ---
    at doSomething (src/index.js:53:15)            ← 外层回调注册点
    at <anonymous> (src/index.js:61:14)
```

机制（仅开发环境启用）：

- **注册栈**：每个回调（定时器 / 事件 / 命令 / 异步请求）注册时捕获完整用户调用栈
- **`--- promise chain ---`**：拦截 `Promise.prototype.then`（仅 `$dev`）——覆盖 `.then` 处理器**自身抛错**、以及拒绝沿链传播时每一环的调用点；`.then` 在回调/微任务上下文执行时自动继承该回调的用户链
- **`--- outer callback ---`**：多层回调嵌套（如 `setTimeout` 套 `setTimeout`）时逐层还原外层回调的注册点
- **最近回调上下文**：`await` 恢复后的微任务里发起的 `.then` 也能拼上发起它的回调的用户调用链；非回调消息（LOAD 等）会清空上下文，避免误归因

> **已知边界**：`await` 之后的语句由引擎内部调度，**async 函数之间的中间调用帧无法重建**（JS 引擎不跨 await 边界保留调用栈）——但"错误抛出点 + 注册链"两端始终完整，足以定位问题。

> [!TIP]
> 堆栈中的路径（如 `src/index.js:56:10`）是 source-map 反解后的**真实源码位置**——在多数现代编辑器的内置终端中可以直接 **Ctrl + 点击**（VS Code、WebStorm、IntelliJ IDEA 等）跳转到对应代码行。

> [!WARNING]
> 异步堆栈追踪在**开发模式**下会降低性能：每叠加一层回调 / `.then` 链，性能下降约 **10%**（栈捕获成本）。**生产模式没有任何损耗**（无拦截、无栈捕获、无 source-map），无需担心。

#### 自动高亮与代码过滤

- **用户代码**（`src/` 下的帧）——**加粗高亮**，并提供源码上下文与定位符
- **依赖包**（`node_modules/` 中的帧，如 `yeow-api`）——灰显但保留，用于理解调用链
- **内部实现**（`init.js` / `unknown.js` 等运行时内部帧）——灰显并标记 `(internal)`
- 错误主体自动取**第一个用户代码帧**作为上下文（找不到时退化为普通栈输出）

#### 手动上报错误

`catch` 块中可用 `logError(err, context?)` 主动上报（context 为可选的附加说明，出现在报告头部）：

```js
import { logError } from 'yeow-api';

try {
    await someOperation();
} catch (e) {
    logError(e, 'someOperation failed for player ' + player.name);
}
```

错误经 `debug` 通道发送到运行时：开发模式下转发 dev-server 做 source-map 解析（享受上述全部体验），生产模式下输出到服务器日志（`[插件名] JS Error: ...`，含文件名/行号与栈前 3 帧）。

> **提示**：运行时报错时，`--- runtime executer error(for reference) ---` 段为 Java 侧执行器的完整堆栈，仅作参考——排查业务逻辑请以上方的 `--- promise chain ---` / `--- outer callback ---` 段为准。

`runtime executer error` 段不应该被你看到，如果你真的看到了它，请向 [Yeow](https://github.com/iYeXin/Yeow) 项目报告问题（Yeow 运行时错误）。

## 性能分析

### 预警引擎（默认开启）

运行时预警独立于全量分析，`profile.warnings-enabled: true` 时每秒聚合窗口级指标并输出告警（心跳/事件/补全/积压/饱和）。阈值见 [运行时警告](runtime-warning.md#配置)。

### 全量分析（`/yeow profile` / `/yeow track`）

逐任务级采集（每任务计时 + 插件/任务分解）默认关闭，避免采集开销。在 `plugins/Yeow/runtime/config.yml` 中开启：

```yaml
profile:
  enabled: true
```

然后：

- `/yeow profile` — 性能快照：健康评分 + 实时（HIGH/NORMAL）与批量（LOW）队列指标 + 各插件/任务分解，同时保存详细报告到 `plugins/Yeow/yeow-profile-*.txt`
- `/yeow track <plugin> <seconds>` — 单插件深度追踪（任务/事件/补全/心跳明细），报告保存到 `plugins/Yeow/yeow-track-*.txt`

> 语义提示：HIGH/NORMAL 是实时队列（不应积压）；LOW 是批量队列（允许积压与延迟）。报告与告警均按此口径分析。

## 权限计算

```bash
npm run permissions
```

只读计算（会回写 `yeow.config.json` 的 `computedPermissions` 字段）：合并主项目与全部依赖包声明的 `permissions`（去重 + 通配归一化），打印**最终权限**与**来源分布**（每个权限来自哪个包），用于排查权限缺失与冗余。详见 [权限声明](getting-started.md#权限声明)。

## 构建

```bash
npm run build
```

1. TypeScript 项目自动运行 `tsc --noEmit` 类型检查（可通过 `yeow.config.json` 的 `typecheck` 字段关闭）
2. esbuild 打包 `src/index.ts`（或 `index.js`） → `.yeow/main.js`（生产）/ `.dev/main.js`（开发）
3. 资源文件（`import './assets/icon.png'`）按依赖项命名空间复制到 `dist/.assets/<id>/`（生产）或 `dist/.dev/.assets/<id>/`（开发）
4. 从 `.yeow/assets/` 读取模板 JAR
5. 注入 `main.js` + `yeow.json` + `plugin.yml` + 全部资源文件
6. 输出到 `dist/<name>-<version>.jar` + `.yeow.zip`（生产）/ `dist/plugins/<name>-<version>.jar` + `.yeow.zip`（开发，zip 内含 `.yeow/dev.json`）
7. 生产模式额外生成**平台无关插件包** `dist/<name>-<version>.yeow.zip`（`.yeow/main.js` + `assets/` + `yeow.json`，无模板类）——放入 `plugins/Yeow/` 自动扫描加载，或 `/yeow load` 动态加载

> 开发模式下构建产物放在 `dist/.dev/`，与生产 `dist/` 隔离。`yeow.config.json` 的 `permissions` 字段会写入 `yeow.json`，作为插件加载时的权限声明。

> **分发**：两种产物的对比与 Modrinth 等平台的上传建议、`/yeow install <url>` 一键安装，见 [构建与分发](distribution.md)。
