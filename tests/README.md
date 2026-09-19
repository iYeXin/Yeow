# tests — 测试套件

Yeow 的自动化测试分为两层，统一入口为 `node tests/run.mjs <tier>`：

| 层 | 是否启动服务器 | 内容 | 典型耗时 |
|---|---|---|---|
| `simple` | 否 | QuickJS 桥测试组件 + runtime 单元测试（Maven） | 秒级 |
| `full` | 是（真实 Paper） | 加载测试插件并校验断言 | 十秒级（含启动） |

## 前置

- **JDK 21**：`JAVA_HOME` 或 PATH 中的 `javac`/`java`。
- **Maven 3.9+**：`simple` 的 runtime 单测。
- **Node.js 18+**。
- **Zig 0.16**：仅 `simple --build` 或 `full` 需要重建 wrapper 时使用。
- **tests 依赖**：`cd tests && npm install` —— `systeminformation`（平台信息）、`minecraft-protocol`（假玩家）、`esbuild`/`adm-zip`（用 build.js 构建测试插件）。缺依赖时平台信息回退 `node:os`，假玩家与插件构建不可用。

测试开始时统一打印测试平台（CPU 型号/物理核、内存总量与规格、OS、Node 版本）。

`simple` 的 QuickJS 组件使用 `quickjs-wrapper/zig-out/yeow-quickjs.jar`；该文件不存在时用 `--build` 生成。

## simple

```bash
node tests/run.mjs simple
node tests/run.mjs simple --build              # 先 zig build jar
node tests/run.mjs simple --filter=binary      # 只跑名称匹配的用例
node tests/run.mjs simple --json=report.json   # 输出机器可读报告
node tests/run.mjs simple --api                # 追加 yeow-api 类型检查（需要 tsc）
```

依次执行：

1. **QuickJS 桥测试组件**（`tests/quickjs/`）：零依赖 Runner，编译并驱动构建出的 wrapper。
   - Java 用例（`Cases.java`）：上下文生命周期、值映射、上行/下行、`bindGlobal`/`callHandle`、`drainJobs`/Promise、异常、线程亲和、不可中断、二进制编解码原语。
   - JS 语料（`cases/*.js`）：通过注入的 `test()`/`testAsync()`/`assert` 编写。
   - 每个用例使用独立 `QuickJSContext`。退出码反映结果。
2. **runtime 单元测试**：`mvn -q test`（`yeow-runtime/jvm`，core/paper/folia）。需要本地 Maven 仓库已安装 `wiki.yexin:yeow-quickjs`（见 `CONTRIBUTING.md`）。
3. `--api`：`tsc -p yeow-api`（默认不执行，避免依赖网络）。

> 桥层用例**不得**依赖由运行时 `init.js` 注入的全局（`console`、`$dev`、`$binary`、`__plugin` 等），这些属于 full 层。

## full

```bash
node tests/run.mjs full
node tests/run.mjs full --build-only           # 只打包测试插件，不启动服务器
node tests/run.mjs full --keep --outfile=full.log
node tests/run.mjs full --paper=/path/paper.jar --timeout=300
```

流程：

1. 定位 Paper jar。顺序：`--paper=<jar>` → `$YEOW_PAPER_JAR` → 自动探测 `test/test/.yeow/dev/cache/paper-*.jar` → 从 PaperMC API 下载到 `tests/e2e/.paper/`。
2. 准备服务器目录（默认 `tests/e2e/.work/server`）：`eula.txt`、`server.properties`。
3. 用 `create-yeow` 的 `build.js` 构建每个测试插件项目（`tests/e2e/plugins/<name>/`，TypeScript + `yeow-api` 相对路径依赖）为 `.yeow.zip`；部署运行时 jar（`--runtime=<jar>`，默认取 `create-yeow/templates/default/.yeow/assets/yeow-runtime-*.jar`）到 `plugins/`，插件 zip 放入 `plugins/Yeow/`。
4. 启动 Paper，读取 stdout；测试插件在 `onLoad` 或事件到达时通过 `log` 通道输出单行哨兵 `[YEOW-E2E] {json}`。
5. 若存在 `e2e-tests`（或名称含 `players`）的插件，则在服务器加载后以**离线模式**连接 `--clients` 个 1.21.4 假玩家（`minecraft-protocol`），并等待其加入。
6. 收集全部插件的报告，汇总输出；有失败则以非零码退出。

测试插件是标准 Yeow 项目（`package.json` + `yeow.config.json` + `src/index.ts`，`yeow-api` 以相对路径声明在 `dependencies`），经 `yeow-api` 的 `onLoad`/`eventOn`/`Player`/`getEnv` 等编写；`tests/e2e/build-plugin.mjs` 把模板 `.yeow` 工具链（`build.js` / `yeow-assets.mjs` / 模板 jar）复制进项目、链接 `yeow-api`，再运行 `build.js` 产出 `.yeow.zip`。断言结果以 JSON 形式回传。

当前场景（`tests/e2e/plugins/e2e-tests/`）覆盖：

- 通道：`env`、`task`（同步 + 批量）、`util`（UTF-8、gzip）、`fs`、`debug.payload` 往返。
- 通信层基准：`debug.payload` 同步往返（每载荷 200 预热 + 5000 采样），随报告回传 `bench` 指标（mean/min/p50/p99/max，ms/op）。
- 假玩家：`eventOn('playerJoin')` + `Player.getSync`，回传 `joined` 供 harness 交叉校验。

选项：`--server=<dir>`、`--paper=<jar>`、`--runtime=<jar>`、`--clients=<n>`、`--client-prefix=<name>`、`--mc-version=<ver>`、`--build-only`、`--keep`、`--outfile=<path>`、`--timeout=<sec>`。

产物与缓存（`tests/quickjs/out/`、`tests/e2e/.work/`、`tests/e2e/.paper/`、测试插件的 `.yeow/` 与 `dist/`）不纳入版本控制。

## 目录

```
tests/
  run.mjs                      # 统一入口（先打印测试平台信息）
  package.json                 # 测试依赖（minecraft-protocol / systeminformation / esbuild / adm-zip）
  lib/{proc.mjs,paper.mjs,systeminfo.mjs,mc.mjs}
  quickjs/
    build.mjs
    java/yeow/tests/quickjs/{Runner,Assert,Cases}.java
    cases/*.js
  e2e/
    harness.mjs
    build-plugin.mjs           # 复制模板 .yeow 工具链 + 链接 yeow-api + 运行 build.js
    plugins/e2e-tests/{package.json,yeow.config.json,tsconfig.json,src/index.ts}
```

## 约定

- 新增 full 场景：在 `tests/e2e/plugins/` 下新增插件目录（`main.js` + `yeow.json`），harness 会自动打包并等待其报告。
- 测试插件不得修改服务器持久状态；如需写入，只使用插件自身数据目录（`fs` 的 `plugin.*` 节点）。
- 断言失败必须包含足以定位问题的信息（期望值/实际值/名称）。

## 维护要求

实现与测试同步更新：任何改动必须在**同一次改动中**更新对应的测试组件，不得留待后续。组件与测试层的对应关系见仓库根 `AGENTS.md` 的「迭代同步要求」表格；只改实现不改测试视为未完成。

提交前至少运行 `node tests/run.mjs simple`；涉及协议、运行时行为或 API 语义时再运行 `full`。
