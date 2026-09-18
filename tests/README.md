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
3. 部署 `yeow-runtime-0.6.1.jar`（取 `create-yeow/templates/default/.yeow/assets/`）到 `plugins/`，并把 `tests/e2e/plugins/*` 逐个打包为 `.yeow.zip` 放入 `plugins/Yeow/`。
4. 启动 Paper，读取 stdout；测试插件在 LOAD 阶段执行断言并通过 `log` 通道输出单行哨兵 `[YEOW-E2E] {json}`。
5. 收集全部插件的报告，汇总输出；有失败则以非零码退出。

测试插件为纯 JS（无构建步骤），通过 `globalThis.__yeowLoadCbs` 注册加载回调，使用 `$send` 与运行时全局。断言结果以 JSON 形式回传。

`tests/e2e/plugins/bench/` 为**通信层性能基准**：对 `debug.payload` 做固定小规模的同步往返（每载荷 200 预热 + 5000 采样，全部载荷合计约 0.6–1s），随报告回传 `bench` 指标（mean/min/p50/p99/max，ms/op），由 harness 打印；另附一条宽松上限断言（mean ≤ 10ms），仅用于捕捉数量级退化，避免环境抖动误报。基准不纳入耗时门槛，不作为失败条件（除非越过该宽松上限）。

选项：`--server=<dir>`、`--paper=<jar>`、`--runtime=<jar>`、`--build-only`、`--keep`、`--outfile=<path>`、`--timeout=<sec>`。

产物与缓存（`tests/quickjs/out/`、`tests/e2e/.work/`、`tests/e2e/.paper/`）不纳入版本控制。

## 目录

```
tests/
  run.mjs                      # 统一入口
  lib/{proc.mjs,zip.mjs,paper.mjs}
  quickjs/
    build.mjs
    java/yeow/tests/quickjs/{Runner,Assert,Cases}.java
    cases/*.js
  e2e/
    harness.mjs
    plugins/<name>/{main.js,yeow.json}
```

## 约定

- 新增 full 场景：在 `tests/e2e/plugins/` 下新增插件目录（`main.js` + `yeow.json`），harness 会自动打包并等待其报告。
- 测试插件不得修改服务器持久状态；如需写入，只使用插件自身数据目录（`fs` 的 `plugin.*` 节点）。
- 断言失败必须包含足以定位问题的信息（期望值/实际值/名称）。

## 维护要求

实现与测试同步更新：任何改动必须在**同一次改动中**更新对应的测试组件，不得留待后续。组件与测试层的对应关系见仓库根 `AGENTS.md` 的「迭代同步要求」表格；只改实现不改测试视为未完成。

提交前至少运行 `node tests/run.mjs simple`；涉及协议、运行时行为或 API 语义时再运行 `full`。
