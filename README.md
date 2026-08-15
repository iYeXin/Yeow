# Yeow v0

**用 TypeScript / JavaScript 编写 Minecraft Paper 插件。QuickJS 引擎。**

Yeow 是一个面向 Minecraft Paper 服务器的插件开发框架：用现代前端工程化（TypeScript、npm、esbuild）编写插件，运行时为每个插件启动**独立的 QuickJS 线程**，通过消息桥与游戏主线程交互。插件代码不阻塞服务器主线程，一个插件崩溃不影响其他插件。

```bash
npm create yeow@latest -- -y     # 创建插件项目
cd my-plugin
npm install
npm run dev                      # 一键启动 Paper + 秒级热重载
npm run build                    # 产出标准 Paper JAR + 平台无关 .yeow.zip
```

- 文档（中文）：[Yeow-Docs](Yeow-Docs/) · [文档站点](https://github.com/iyexin/yeow/tree/main/Yeow-Docs)
- 运行时下载：[Modrinth](https://modrinth.com/plugin/yeow)
- 开发与贡献：[CONTRIBUTING.md](CONTRIBUTING.md)

---

## 组件总览（10 个目录）

| 目录                                    | 语言       | 作用                                                                                                              |
| --------------------------------------- | ---------- | ----------------------------------------------------------------------------------------------------------------- |
| [`yeow-runtime`](yeow-runtime/)         | Java 21    | **核心运行时**（Paper 系插件）：QuickJS 上下文管理、三级优先级调度器、事件/命令桥、Service、Profile 预警与全量分析 |
| [`yeow-api`](yeow-api/)                 | TypeScript | 插件开发期 npm 依赖：OOP 封装全部底层协议（Player/World/Event/Command/Service…），构建时随插件 bundle             |
| [`packages/yeow-command`](packages/yeow-command/) | TypeScript | 重载式命令构建器（类型化 schema、补全、权限），独立 npm 包（`yeow-command`），随插件 bundle                       |
| [`packages/yeow-server`](packages/yeow-server/)   | TypeScript | HTTP 服务器高层封装（路由、洋葱中间件、静态挂载），独立 npm 包（`yeow-server`），随插件 bundle                     |
| [`create-yeow`](create-yeow/)           | Node.js    | `npm create yeow` 脚手架：交互式项目模板、dev-server（Paper + 热重载 + source-map 错误定位）、构建脚本            |
| [`yeow-template`](yeow-template/)       | Java 21    | 空 JAR 骨架（`Bootstrap` 类），构建时注入 JS 代码生成标准 Paper 插件 JAR                                          |
| [`quickjs-wrapper`](quickjs-wrapper/) | Java + C++ | QuickJS 2026-06-04 的 JVM 封装（fork），含四平台预编译原生库。**本仓库中的副本仅为镜像**——主维护仓库：[github.com/iYeXin/quickjs-wrapper](https://github.com/iYeXin/quickjs-wrapper)（版本标签、多平台 CI 构建、Release 发布均在那里进行）。`native/quickjs`（QuickJS 本体 C 源码）为 **git submodule**（[iyexin/quickjs](https://github.com/iyexin/quickjs)，上游 bellard/quickjs 的 fork，锁定 2026-06-04 快照） |
| [`yeow-dev`](yeow-dev/)                 | Node.js    | 构建期虚拟模块（空 npm 包）：`getAssetsPath` 的引入来源，构建时被 esbuild 拦截并按依赖项注入命名空间            |
| [`yeow-tools`](yeow-tools/)             | Java 21    | 开发辅助工具：调度器/队列基准（Bench）、Base64 编解码诊断                                                         |
| [`Yeow-Docs`](Yeow-Docs/)               | Markdown   | 全部中文文档：入门、API 参考（24 模块）、进阶、平台规范（协议层）、运行时警告                                     |
| [`yeow-doc-website`](yeow-doc-website/) | VitePress  | 文档站点工程：`docs/` 为 `Yeow-Docs/zh` 的目录联接，自定义首页与主题；`npm run build` 产出 `/v1/` 站点            |

### 运行链路

```
插件 JAR/.yeow.zip ──► yeow-template(Bootstrap) ──► yeow-runtime ──► Paper
                              ▲                        │
        create-yeow 构建 ──────┘      quickjs-wrapper(QuickJS 引擎)
        yeow-api / yeow-command / yeow-server（打包进插件）
```

---

## 克隆与构建

```bash
# 克隆（quickjs-wrapper 含 QuickJS C 源码 submodule）
git clone --recursive https://github.com/iYeXin/Yeow.git
```

- 运行时/模板构建：见 [CONTRIBUTING.md](CONTRIBUTING.md)（Maven 本地安装流程）
- 插件开发：`npm create yeow@latest`（脚手架本身不依赖本仓库）

## 快速开始

完整文档见 [Yeow-Docs/zh/getting-started.md](Yeow-Docs/zh/getting-started.md)。

1. **安装运行时**：将 `yeow-runtime-0.2.0.jar` 放入服务器 `plugins/`
2. **创建插件**：`npm create yeow@latest -- -y && cd my-plugin && npm install`
3. **开发**：`npm run dev`（自动下载 Paper、启动热重载）
4. **构建**：`npm run build` → `dist/<name>-<version>.jar` + `.yeow.zip`
5. **部署**：JAR 放 `plugins/`；或 `.yeow.zip` 放 `plugins/Yeow/` 自动扫描，或 `/yeow install <url>`

插件需要声明敏感权限（`fs:server.*`、`http:*`、`service:registerNative`、`assets:extract`），见 [权限声明](Yeow-Docs/zh/getting-started.md#权限声明)。

---

## 跨平台

- 插件包是标准 ZIP（`.yeow.zip`，含打包 JS、资源、元信息与权限声明），**不依赖 Java 环境**
- **Paper 与 [Folia](https://papermc.io/software/folia/) 双平台通用**：同一份 `.yeow.zip` / `.jar` 插件包可直接互换，API 使用方法完全一致；Folia 服务器只需安装 Folia 版 [Yeow 运行时](https://modrinth.com/plugin/yeow)（Modrinth 提供）
- **Folia 深度适配**：`yeow-runtime-folia` 是独立实现——区域驻留调度器、热点迁移、预算控制、非阻塞投递，插件无需任何改动即可自动享受 Folia 的多线程并行优势（详见 [`Yeow-Docs/zh/advanced/folia.md`](Yeow-Docs/zh/advanced/folia.md)）
- 任何实现[平台规范](Yeow-Docs/zh/specifications/README.md)的运行时都能运行同一份插件；Folia 只是 Yeow 跨平台性的第一个例子，未来还将支持 Fabric / NeoForge 等平台
- Paper 系（Paper/Purpur/Leaf 等）的 `yeow-runtime` 是官方实现示例

## 文档索引

| 文档                         | 位置                                                                  |
| ---------------------------- | --------------------------------------------------------------------- |
| 快速开始 / API 参考 / 进阶   | [Yeow-Docs/zh](Yeow-Docs/zh/README.md)                                |
| 构建与分发（Modrinth 等）    | [Yeow-Docs/zh/distribution.md](Yeow-Docs/zh/distribution.md)          |
| 平台规范（协议层）           | [Yeow-Docs/zh/specifications/](Yeow-Docs/zh/specifications/README.md) |
| 运行时警告（告警 code/阈值） | [Yeow-Docs/zh/runtime-warning.md](Yeow-Docs/zh/runtime-warning.md)    |

## 许可证

各组件独立：`yeow-runtime` / `yeow-api` / `packages/yeow-command` / `packages/yeow-server` / `create-yeow` / `yeow-template` / `yeow-dev` / `yeow-tools` 为 MIT；`quickjs-wrapper` 为 Apache-2.0（QuickJS 引擎 MIT）；`Yeow-Docs` 与文档站点为 MIT。
