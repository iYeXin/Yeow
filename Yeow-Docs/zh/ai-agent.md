# AI 辅助启动指南（Agent）

面向 **AI 代理 / Vibe Coding**：如何启动一个 Yeow 插件项目。

## Yeow 项目简介

用 **TypeScript / JavaScript** 编写 Minecraft Paper 插件——运行时（QuickJS 引擎，Java/Paper 插件）为每个插件启动**独立 JS 线程**，插件代码不阻塞服务器主线程。构建产出：标准 Paper JAR（`plugins/`）与平台无关 `.yeow.zip`（`plugins/Yeow/`）。

## 如何启动项目

```bash
npm create yeow@latest -- -y --ts    # 创建项目（-y 非交互；--ts 使用 TypeScript——强烈建议）
cd my-plugin
npm install                          # 安装依赖
npm run dev                          # 启动 Paper 开发服务器（WebSocket 热重载 + source-map 错误定位）
```

- `--ts`：**TypeScript**（强烈建议——完整类型推断，AI/编辑器获得类型支持，杜绝静态错误与模型幻觉）
- `--name=xxx`：指定项目名；不带 `-y` 时交互式选择语言
- `npm run dev -- --stop=2m`：2 分钟后自动停止

## 调试工作流（AI 代理）

headless 模式适合 AI 代理/CI：自动接受 EULA → 下载服务端 → 启动 → 检测加载完成 → 等待后命令自动结束，日志落盘、服务器进程可控：

```bash
npm run dev -- --eula --keep --timeout=2m --wait=30s --outfile=log.txt
```

- `--eula`：自动接受 EULA；`--timeout=2m`：加载超时（默认 2m，超时可能代表网络问题，检查代理或加大超时时间）；`--wait=30s`：加载成功后等待（默认 30s，时间到命令自动结束）；`--outfile=log.txt`：日志输出文件；`--keep`：命令结束后**保留服务器进程**
- 流程：输出 `Server PID` → 下载/启动 → 检测到 `Done (...)! For help` 视为加载完成 → 等待后命令结束
- 调试方式：1. 引导用户进入测试服务器进行真实测试  2. 启动 http-server，通过外部请求调试   3. 编辑源代码，之后杀死进程并重启开发服务器（headless 无热重载）
- **加载成功后看日志**（`--outfile` 或控制台输出）；不需要服务器时按输出的 `Server PID` 杀死进程（`kill <pid>`）

## 下一步

1. 编辑 `src/index.ts`——注册命令、事件、Worker 等（示例见 [快速开始](getting-started.md)）
2. `npm run build` → `dist/<name>-<version>.jar` + `.yeow.zip`
3. 部署：JAR 放服务器 `plugins/`；`.yeow.zip` 放 `plugins/Yeow/`（自动扫描加载）或 `/yeow install <url>`

## 如何查阅文档

| 资料                                           | 地址                                       |
| ---------------------------------------------- | ------------------------------------------ |
| **站点地图**（全部页面标题 + 摘要 + URL）      | https://yeow.yeside.top/v1/sitemap         |
| **文档压缩包**（全量 Markdown，可直接喂给 AI） | https://yeow.yeside.top/v1/docs.zip        |
| 快速开始                                       | https://yeow.yeside.top/v1/getting-started |
| API 参考（索引）                               | https://yeow.yeside.top/v1/api/            |
| 进阶（架构/线程/调度器）                       | https://yeow.yeside.top/v1/advanced        |
| 平台规范（协议层）                             | https://yeow.yeside.top/v1/specifications/ |

> **策略**：在任何 Harness 产品（Codex、OpenCode、Zcode、Trae 等）中，复制**此页面内容或站点地图**发送给 AI，描述你的需求（如"创建一个带 /back 命令的插件"），AI 将带领你完成项目创建、开发与调试。
