import { defineConfig } from 'vitepress'

// ── 中文站点（cn.yexin.wiki/yeow/v1）────────────────────────────────
// 构建 root：vp-roots/cn（vitepress build vp-roots/cn），srcDir 指向上级 docs/cn（仓库内直接提交）。
// 「上次修改」由内置 VPLastUpdated 读取入库文件 git 提交日期（不写回 md）。
// 多语言：英文站见 vp-roots/en/.vitepress/config.mts（部署于 yexin.wiki/yeow/v1）；
// 导航语言切换由 vp-roots/cn/.vitepress/theme 的 LanguageSwitcher 实现（vt-locales-btn 原生样式，跨域跳转携带路径）。
// base /yeow/v1/（版本化，后续 v2 可并存）。本构建（中文）输出 vp-roots/cn/.vitepress/dist（publish 依赖该路径）。
const base = '/yeow/v1/'

export default defineConfig({
    lang: 'zh-CN',
    title: 'Yeow',
    description: '用 TypeScript 写 Minecraft 跨平台插件（Paper / Folia）· QuickJS 引擎',
    site: 'https://cn.yexin.wiki/yeow/v1/',
    base,
    srcDir: '../../docs/cn',
    appearance: 'dark',
    cleanUrls: true,
    head: [
        ['meta', { name: 'og:title', content: 'Yeow — 用 TypeScript 写 Minecraft 跨平台插件（Paper / Folia）' }],
        ['meta', { name: 'og:description', content: 'QuickJS 引擎 · 每插件独立线程 · 热重载 · Paper/Folia 双平台通用插件包' }],
        ['meta', { name: 'og:url', content: 'https://github.com/iyexin/yeow' }],
        ['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}favicon.svg` }],
    ],
    themeConfig: {
        logo: '/favicon.svg',
        lastUpdated: { text: '最后更新于' },
        socialLinks: [{ icon: 'github', link: 'https://github.com/iyexin/yeow' }],
        nav: [
            { text: '指南', link: '/overview', activeMatch: '/overview|/getting-started|/ai-agent|/cli|/runtime-warning|/permissions|/operations' },
            { text: 'API', link: '/api/', activeMatch: '/api/' },
            { text: '进阶', link: '/advanced', activeMatch: '/advanced' },
            { text: '分发', link: '/distribution', activeMatch: '/distribution' },
            { text: '包开发', link: '/package-author', activeMatch: '/package-author|/package-service' },
            { text: '规范', link: '/specifications/', activeMatch: '/specifications/' },
            { text: '路线图', link: '/todo', activeMatch: '/todo' },
            { text: '下载运行时', link: 'https://hangar.papermc.io/iYeXin/Yeow/versions' },
        ],
        sidebar: [
            {
                text: '开始',
                items: [
                    { text: '概览', link: '/overview' },
                    { text: '快速开始', link: '/getting-started' },
                    { text: '环境能力', link: '/environment' },
                    { text: 'AI 启动指南', link: '/ai-agent' },
                    { text: 'CLI 参考', link: '/cli' },
                    { text: '构建与分发', link: '/distribution' },
                    { text: '权限与原生服务可信性', link: '/permissions' },
                    { text: '运行时运维', link: '/operations' },
                    { text: '运行时警告', link: '/runtime-warning' },
                    { text: '更新日志', link: '/changelog' },
                    { text: '路线图', link: '/todo' },
                    { text: '站点地图', link: '/sitemap' },
                ],
            },
            {
                text: 'API 参考',
                items: [
                    { text: 'API 索引', link: '/api/' },
                    {
                        text: '玩家与服务器',
                        collapsed: true,
                        items: [
                            { text: 'Player', link: '/api/player' },
                            { text: 'Server', link: '/api/server' },
                            { text: 'Env', link: '/api/env' },
                            { text: 'Permission', link: '/api/permission' },
                        ],
                    },
                    {
                        text: '世界与方块',
                        collapsed: true,
                        items: [
                            { text: 'World', link: '/api/world' },
                            { text: 'Chunk', link: '/api/chunk' },
                            { text: 'Location', link: '/api/location' },
                            { text: 'Block', link: '/api/block' },
                            { text: 'Material', link: '/api/material' },
                        ],
                    },
                    {
                        text: '实体',
                        collapsed: true,
                        items: [
                            { text: 'Entity', link: '/api/entity' },
                            { text: 'Potion', link: '/api/potion' },
                            { text: 'Particle', link: '/api/particle' },
                        ],
                    },
                    {
                        text: '交互界面',
                        collapsed: true,
                        items: [
                            { text: 'Inventory', link: '/api/inventory' },
                            { text: 'BossBar', link: '/api/bossbar' },
                            { text: 'Scoreboard', link: '/api/scoreboard' },
                            { text: 'Advancement', link: '/api/advancement' },
                            { text: 'Recipe', link: '/api/recipe' },
                        ],
                    },
                    {
                        text: '事件与命令',
                        collapsed: true,
                        items: [
                            { text: 'Event', link: '/api/event' },
                            { text: 'Command', link: '/api/command' },
                        ],
                    },
                    {
                        text: '物品',
                        collapsed: true,
                        items: [
                            { text: 'ItemStack', link: '/api/item' },
                        ],
                    },
                    {
                        text: '服务与网络',
                        collapsed: true,
                        items: [
                            { text: 'Service', link: '/api/service' },
                            { text: 'HTTP', link: '/api/http' },
                            { text: 'HTTP Server', link: '/api/http-server' },
                        ],
                    },
                    {
                        text: '多线程',
                        collapsed: true,
                        items: [
                            { text: 'Worker', link: '/api/worker' },
                        ],
                    },
                    {
                        text: '文件与数据',
                        collapsed: true,
                        items: [
                            { text: 'FS', link: '/api/fs' },
                            { text: 'Assets', link: '/api/assets' },
                            { text: 'PDC', link: '/api/pdc' },
                            { text: 'Util', link: '/api/util' },
                        ],
                    },
                    {
                        text: '文本',
                        collapsed: true,
                        items: [
                            { text: 'Text', link: '/api/text' },
                        ],
                    },
                    { text: 'Log', link: '/api/log' },
                ],
            },
            {
                text: '进阶',
                collapsed: true,
                items: [
                    { text: '进阶索引', link: '/advanced' },
                    { text: '架构与线程模型', link: '/advanced/architecture' },
                    { text: '调度器与任务', link: '/advanced/scheduler' },
                    { text: 'Folia 支持', link: '/advanced/folia' },
                    { text: '事件与回调', link: '/advanced/events' },
                    { text: '生命周期与热重载', link: '/advanced/lifecycle' },
                    { text: '环境能力与通道', link: '/advanced/channels' },
                    { text: '服务机制', link: '/advanced/service' },
                    { text: '关于 Yeow', link: '/advanced/about' },
                ],
            },
            {
                text: '依赖包开发',
                items: [
                    { text: '编写依赖包', link: '/package-author' },
                    { text: '封装 Service 的依赖包', link: '/package-service' },
                ],
            },
            {
                text: '平台规范',
                collapsed: true,
                items: [
                    { text: '规范总览', link: '/specifications/' },
                    { text: '值域附录', link: '/specifications/values' },
                    { text: 'Java 插件集成', link: '/specifications/java-api' },
                    {
                        text: '消息通道',
                        collapsed: true,
                        items: [
                            { text: '通道总览', link: '/specifications/message/' },
                            { text: 'Timer', link: '/specifications/message/timer' },
                            { text: 'Task', link: '/specifications/message/task' },
                            { text: 'FS', link: '/specifications/message/fs' },
                            { text: 'HTTP', link: '/specifications/message/http' },
                            { text: 'Assets', link: '/specifications/message/assets' },
                            { text: 'Service', link: '/specifications/message/service' },
                            { text: 'Log', link: '/specifications/message/log' },
                            { text: 'Lifecycle', link: '/specifications/message/lifecycle' },
                            { text: 'Debug', link: '/specifications/message/debug' },
                            { text: 'Util', link: '/specifications/message/util' },
                            { text: 'Worker', link: '/specifications/message/worker' },
                        ],
                    },
                    {
                        text: '任务类型',
                        collapsed: true,
                        items: [
                            { text: '任务总览', link: '/specifications/task/' },
                            { text: 'Player', link: '/specifications/task/player' },
                            { text: 'Server', link: '/specifications/task/server' },
                            { text: 'World', link: '/specifications/task/world' },
                            { text: 'Scoreboard', link: '/specifications/task/scoreboard' },
                            { text: 'Recipe', link: '/specifications/task/recipe' },
                            { text: 'PDC', link: '/specifications/task/pdc' },
                            { text: 'Inventory & GUI', link: '/specifications/task/inventory-gui' },
                            { text: 'Event System', link: '/specifications/task/event-system' },
                            { text: 'Entity', link: '/specifications/task/entity' },
                            { text: 'Command', link: '/specifications/task/command' },
                            { text: 'BossBar', link: '/specifications/task/bossbar' },
                            { text: 'Advancement', link: '/specifications/task/advancement' },
                        ],
                    },
                    {
                        text: '事件',
                        collapsed: true,
                        items: [
                            { text: '事件总览', link: '/specifications/event/' },
                            { text: '玩家事件', link: '/specifications/event/player-events' },
                            { text: '实体事件', link: '/specifications/event/entity-events' },
                            { text: '方块事件', link: '/specifications/event/block-events' },
                            { text: '库存事件', link: '/specifications/event/inventory-events' },
                            { text: '服务器事件', link: '/specifications/event/server-events' },
                        ],
                    },
                    {
                        text: '环境',
                        items: [
                            { text: '运行时环境标准', link: '/specifications/runtime/' },
                            { text: 'Native Service 协议', link: '/specifications/native-service/' },
                            { text: '适配器规范', link: '/specifications/adapter/' },
                        ],
                    },
                ],
            },
        ],
        outline: {
            level: [2, 3],
            label: '本页目录',
        },
        docFooter: {
            prev: '上一页',
            next: '下一页',
        },
        sidebarMenuLabel: '菜单',
        returnToTopLabel: '返回顶部',
        darkModeSwitchLabel: '主题',
        lightModeSwitchTitle: '切换到浅色模式',
        darkModeSwitchTitle: '切换到深色模式',
        editLink: {
            pattern: 'https://github.com/iyexin/yeow/edit/main/Yeow-Docs/zh/:path',
            text: '在 GitHub 上编辑此页',
        },
        search: {
            provider: 'local',
            options: {
                translations: {
                    button: { buttonText: '搜索文档', buttonAriaLabel: '搜索文档' },
                    modal: {
                        noResultsText: '未找到相关结果',
                        resetButtonTitle: '清除查询条件',
                        footer: { selectText: '选择', navigateText: '切换', closeText: '关闭' },
                    },
                },
            },
        },
    },
})
