# AGENTS.md

## 发布模式（运行时 / yeow-api 变更后）

修改 `yeow-runtime/jvm/paper`（Java，Paper 平台实现）后，将编译产物复制到模板：

```
create-yeow\templates\default\.yeow\assets\yeow-runtime-0.5.0.jar   ← yeow-runtime\jvm\paper\target\yeow-runtime-0.5.0.jar
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
