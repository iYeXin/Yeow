# AGENTS.md

## 发布模式（运行时 / yeow-api 变更后）

修改 `yeow-runtime/jvm/paper`（Java，Paper 平台实现）后，将编译产物复制到模板：

```
create-yeow\templates\default\.yeow\assets\yeow-runtime-0.1.0.jar   ← yeow-runtime\jvm\paper\target\yeow-runtime-0.1.0.jar
```

若变更涉及 `yeow-api`（TypeScript），依次执行：

1. 修改 `yeow-api\package.json` 中的版本号
2. 修改 `create-yeow\templates\default\package.json` 中 `yeow-api` 的版本号（依赖范围）
3. 更新 `create-yeow\package.json` 中的版本号

然后由用户发布至 npm。

## 文档站点

- 文档源：`Yeow-Docs\zh\`；站点工程 `yeow-doc-website`（`docs/` 为目录联接）
- 构建：`npm run build`（产出 `.vitepress\dist\`）
- 发布：`npm run publish`（构建 + 压缩 dist 上传部署服务器，配置在 `yeow-doc-website\.env`，不入库；模板见 `.env.example`）
- `sitemap.md` 会在构建时自动同步到 `create-yeow\templates\default\sitemap.md`
