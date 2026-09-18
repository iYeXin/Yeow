# AGENTS.md

## 测试

统一入口 `node tests/run.mjs <tier>`（详见 `tests/README.md`）：

- `simple`：QuickJS 桥测试组件 + `mvn -q test`（core/paper/folia），不启动服务器。改动 `quickjs-wrapper` 或 `yeow-runtime` 后先跑此层。
- `full`：实机 Paper，加载 `tests/e2e/plugins/*` 测试插件校验断言（`node tests/run.mjs full`）。Paper jar 自动定位/下载，或 `--paper=<jar>`。

新增 full 场景：在 `tests/e2e/plugins/` 增加插件目录（`main.js` + `yeow.json`），harness 自动打包并等待其 `[YEOW-E2E]` 哨兵报告。

### 迭代同步要求（强制）

任何改动必须**在同一次改动中**同步更新对应的测试组件，不得留待后续：

| 改动位置                                               | 必须同步更新                                                |
| ------------------------------------------------------ | ----------------------------------------------------------- |
| `quickjs-wrapper/native/**`、`quickjs-wrapper/java/**` | `tests/quickjs/`（Java 用例与/或 JS 语料）                  |
| `yeow-runtime/jvm/core/**`                             | `yeow-runtime/jvm/core/src/test/**`（JUnit，simple 层）     |
| `yeow-runtime/jvm/{paper,folia}/**`                    | 对应模块 JUnit；涉及协议/通道行为时补 `tests/e2e/plugins/*` |
| `yeow-api/src/**`（行为或协议）                        | `tests/e2e/plugins/*` 场景；必要时类型检查                  |
| `create-yeow/templates/default/**`（协议/构建/模板）   | `tests/e2e/plugins/*`；受影响时验证模板构建                 |
| 消息通道 / 任务 / 事件 / 命令 / 服务语义               | `tests/e2e/plugins/<scenario>/`                             |

提交/发布前至少跑通 `simple`；涉及协议、运行时行为或 API 语义时再跑 `full`。测试组件与实现同等对待：只改实现不改测试视为未完成。

## 发布模式（运行时 / yeow-api 变更后）

修改 `yeow-runtime/jvm/paper`（Paper 平台实现）或 `yeow-runtime/jvm/folia`（Folia 平台实现）后，将编译产物复制到模板：

```
create-yeow\templates\default\.yeow\assets\yeow-runtime-0.6.1.jar         ← yeow-runtime\jvm\paper\target\yeow-runtime-0.6.1.jar
create-yeow\templates\default\.yeow\assets\yeow-runtime-folia-0.6.1.jar   ← yeow-runtime\jvm\folia\target\yeow-runtime-folia-0.6.1.jar
```

修改 `yeow-template` 后同样需要复制：

```
create-yeow\templates\default\.yeow\assets\yeow-template-0.6.1.jar   ← yeow-template\target\yeow-template-0.6.1.jar
```

若变更涉及 `yeow-api`（TypeScript），依次执行：

1. 修改 `yeow-api\package.json` 中的版本号
2. 修改 `create-yeow\templates\default\package.json` 中 `yeow-api` 的版本号（依赖范围）
3. 更新 `create-yeow\package.json` 中的版本号

然后由用户发布至 npm。

## 文档站点

- 文档源：`yeow-doc-website\docs\cn\`（仓库内文档目录，直接提交；`docs\` 为多语言根，`cn\` 为中文、`en\` 为英文占位）；站点工程 `yeow-doc-website`
- 构建：`npm run build`——双语言两个构建（`vp-roots\cn` 中文 → `vp-roots\cn\.vitepress\dist`，`vp-roots\en` 英文 → `vp-roots\en\.vitepress\dist`；配置在各自的 `.vitepress\config.mts`，多语言切换组件在各自 `.vitepress\theme`）
- 发布：`npm run publish`（构建 + 按 `.env` 中 `CN_PATH`/`EN_PATH` 逐个上传各语言 dist，配置不入库；模板见 `.env.example`）
- `sitemap.md` 会在构建时自动同步到 `create-yeow\templates\default\sitemap.md`
