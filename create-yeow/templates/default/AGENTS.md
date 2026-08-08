# AGENTS.md — 供 AI 代理（Vibe Coding）查阅

## 项目类型

这是一个 **Yeow 插件项目**：用 TypeScript / JavaScript 编写 Minecraft Paper 插件，经 esbuild 打包为 IIFE，由 Yeow 运行时（QuickJS 引擎，Java/Paper 插件）为每个插件启动独立 JS 线程执行。

项目产物：标准 Paper JAR（`plugins/`）与平台无关 `.yeow.zip`（`plugins/Yeow/`）。

## 站点地图

**同目录 `sitemap.md`** —— 文档站点全部页面的索引（标题 + 摘要 + 绝对 URL），AI 查阅资料请先读它确定目标页面。

## 技术栈

- **语言**：TypeScript / JavaScript（Node.js 18+，开发）
- **构建**：esbuild（bundle → IIFE，`target: esnext`），脚本在 `.yeow/build.js`
- **开发服务器**：`.yeow/dev-server.js`（下载并启动 Paper、WebSocket 热重载、source-map 错误定位）
- **API**：`yeow-api`（运行时 API：Player/World/Event/Command/Worker/fs/http…）与 `yeow-utils`（高层工具：HTTP 服务器、命令构造器）——均打包进产物
- **运行时**：`yeow-runtime`（Java 21，Paper 插件）——仓库 `yeow-runtime/`；本模板 `.yeow/assets/` 内置其 jar
- **Worker（虚拟插件）**：`createWorker` 提供独立线程；配置见 `yeow.config.json` 的 `dev.worker`

> [!IMPORTANT]
> **强烈建议优先选用 TypeScript**——尤其对于 AI 辅助编程：`yeow-api` 提供完整类型推断（命令参数、事件 payload、API 返回值），AI/编辑器获得完善类型支持，杜绝静态错误与"模型幻觉"（编造不存在的 API/字段/类型）。新建项目用 `npm create yeow@latest -- -y --ts`。

### 将 JS 项目改造为 TS 项目

1. 把 `src/index.js` 重命名为 `src/index.ts`
2. 创建 `tsconfig.json`：

```json
{
    "compilerOptions": {
        "target": "ESNext",
        "lib": ["ESNext"],
        "module": "ES2022",
        "moduleResolution": "bundler",
        "strict": true,
        "esModuleInterop": true,
        "skipLibCheck": true,
        "noEmit": true
    },
    "include": ["src/**/*.ts"]
}
```

3. 在 `yeow.config.json` 中把 `typecheck` 改为 `true`（构建时自动 `tsc --noEmit` 检查）

`tsconfig.json` 只做类型检查（`noEmit`），打包仍由 esbuild 完成。

## 关键文档（在线）

| 资料 | 地址 |
|------|------|
| 站点地图（全部页面索引） | https://yeow.yeside.top/v1/sitemap |
| 文档压缩包（全量 Markdown，离线/AI 用） | https://yeow.yeside.top/v1/docs.zip |
| 快速开始 | https://yeow.yeside.top/v1/getting-started |
| API 索引 | https://yeow.yeside.top/v1/api/ |
| 进阶（架构/线程/调度器） | https://yeow.yeside.top/v1/advanced |
| 平台规范（协议层） | https://yeow.yeside.top/v1/specifications/ |

## 项目结构

```
src/index.ts        ← 插件入口（onLoad/onInit/onUnload、命令、事件）
assets/             ← 打包资源（图片/配置/原生程序/Worker 产物），经 getAssetsPath 访问
yeow.config.json    ← 插件配置：name/version/permissions/dev（端口、Paper 版本、worker）
.yeow/              ← 构建脚本、dev-server、打包的运行时/模板 jar
sitemap.md          ← 站点地图（本文档站索引）
```

## 阅读策略

1. 先读 `sitemap.md` 定位目标页面
2. 日常 API 用法：在线 API 索引（`/v1/api/`）按模块查（Player/World/Event/Command/Worker…）
3. 需要理解协议/权限/消息格式：平台规范（`/v1/specifications/`）
4. 架构、线程模型、调度器：进阶知识（`/v1/advanced`）
5. 调试：`npm run dev`（错误经 source-map 定位到源码）；运行时警告见 `/v1/runtime-warning`
