import { defineConfig } from 'vitepress'

// ── English site (yexin.wiki/yeow/v1) ────────────────────────────
// Build root: vp-roots/en (vitepress build vp-roots/en), srcDir points to ../docs/en.
// Same base /yeow/v1/ as CN site, different deployment domain; language switching
// via vp-roots/en/.vitepress/theme's LanguageSwitcher (vt-locales-btn native style, cross-domain jump with path).
// Output: vp-roots/en/.vitepress/dist (does not overwrite CN site vp-roots/cn/.vitepress/dist).
const base = '/yeow/v1/'

export default defineConfig({
    lang: 'en-US',
    title: 'Yeow',
    description: 'Write Minecraft cross-platform plugins (Paper / Folia) in TypeScript · QuickJS engine',
    site: 'https://yexin.wiki/yeow/v1/',
    base,
    srcDir: '../../docs/en',
    appearance: 'dark',
    cleanUrls: true,
    head: [
        ['meta', { name: 'og:title', content: 'Yeow — Minecraft cross-platform plugins in TypeScript' }],
        ['meta', { name: 'og:description', content: 'QuickJS engine · Per-plugin isolated threads · Hot reload · Paper/Folia universal plugin packages' }],
        ['meta', { name: 'og:url', content: 'https://github.com/iyexin/yeow' }],
        ['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}favicon.svg` }],
    ],
    themeConfig: {
        logo: '/favicon.svg',
        lastUpdated: { text: 'Last updated' },
        socialLinks: [{ icon: 'github', link: 'https://github.com/iyexin/yeow' }],
        nav: [
            { text: 'Guide', link: '/overview', activeMatch: '/overview|/getting-started|/ai-agent|/cli|/runtime-warning|/permissions|/operations' },
            { text: 'API', link: '/api/', activeMatch: '/api/' },
            { text: 'Advanced', link: '/advanced', activeMatch: '/advanced' },
            { text: 'Distribution', link: '/distribution', activeMatch: '/distribution' },
            { text: 'Package Dev', link: '/package-author', activeMatch: '/package-author|/package-service' },
            { text: 'Specifications', link: '/specifications/', activeMatch: '/specifications/' },
            { text: 'Roadmap', link: '/todo', activeMatch: '/todo' },
            { text: 'Download Runtime', link: 'https://hangar.papermc.io/iYeXin/Yeow/versions' },
        ],
        sidebar: [
            {
                text: 'Getting Started',
                items: [
                    { text: 'Overview', link: '/overview' },
                    { text: 'Quick Start', link: '/getting-started' },
                    { text: 'Environment', link: '/environment' },
                    { text: 'AI Setup Guide', link: '/ai-agent' },
                    { text: 'CLI Reference', link: '/cli' },
                    { text: 'Build & Distribution', link: '/distribution' },
                    { text: 'Permissions & Native Service Trust', link: '/permissions' },
                    { text: 'Runtime Operations', link: '/operations' },
                    { text: 'Runtime Warnings', link: '/runtime-warning' },
                    { text: 'Changelog', link: '/changelog' },
                    { text: 'Roadmap', link: '/todo' },
                    { text: 'Sitemap', link: '/sitemap' },
                ],
            },
            {
                text: 'API Reference',
                items: [
                    { text: 'API Index', link: '/api/' },
                    {
                        text: 'Player & Server',
                        collapsed: true,
                        items: [
                            { text: 'Player', link: '/api/player' },
                            { text: 'Server', link: '/api/server' },
                            { text: 'Env', link: '/api/env' },
                            { text: 'Permission', link: '/api/permission' },
                        ],
                    },
                    {
                        text: 'World & Blocks',
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
                        text: 'Entities',
                        collapsed: true,
                        items: [
                            { text: 'Entity', link: '/api/entity' },
                            { text: 'Potion', link: '/api/potion' },
                            { text: 'Particle', link: '/api/particle' },
                        ],
                    },
                    {
                        text: 'UI',
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
                        text: 'Events & Commands',
                        collapsed: true,
                        items: [
                            { text: 'Event', link: '/api/event' },
                            { text: 'Command', link: '/api/command' },
                        ],
                    },
                    {
                        text: 'Items',
                        collapsed: true,
                        items: [
                            { text: 'ItemStack', link: '/api/item' },
                        ],
                    },
                    {
                        text: 'Services & Networking',
                        collapsed: true,
                        items: [
                            { text: 'Service', link: '/api/service' },
                            { text: 'HTTP', link: '/api/http' },
                            { text: 'HTTP Server', link: '/api/http-server' },
                        ],
                    },
                    {
                        text: 'Multithreading',
                        collapsed: true,
                        items: [
                            { text: 'Worker', link: '/api/worker' },
                        ],
                    },
                    {
                        text: 'Files & Data',
                        collapsed: true,
                        items: [
                            { text: 'FS', link: '/api/fs' },
                            { text: 'Assets', link: '/api/assets' },
                            { text: 'PDC', link: '/api/pdc' },
                            { text: 'Util', link: '/api/util' },
                        ],
                    },
                    {
                        text: 'Text',
                        collapsed: true,
                        items: [
                            { text: 'Text', link: '/api/text' },
                        ],
                    },
                    { text: 'Log', link: '/api/log' },
                ],
            },
            {
                text: 'Advanced',
                collapsed: true,
                items: [
                    { text: 'Advanced Index', link: '/advanced' },
                    { text: 'Architecture & Threading Model', link: '/advanced/architecture' },
                    { text: 'Scheduler & Tasks', link: '/advanced/scheduler' },
                    { text: 'Folia Support', link: '/advanced/folia' },
                    { text: 'Events & Callbacks', link: '/advanced/events' },
                    { text: 'Lifecycle & Hot Reload', link: '/advanced/lifecycle' },
                    { text: 'Environment & Channels', link: '/advanced/channels' },
                    { text: 'Service Mechanism', link: '/advanced/service' },
                    { text: 'About Yeow', link: '/advanced/about' },
                ],
            },
            {
                text: 'Package Development',
                items: [
                    { text: 'Writing Packages', link: '/package-author' },
                    { text: 'Packages with Services', link: '/package-service' },
                ],
            },
            {
                text: 'Platform Specifications',
                collapsed: true,
                items: [
                    { text: 'Specifications Overview', link: '/specifications/' },
                    { text: 'Value Domain Appendix', link: '/specifications/values' },
                    { text: 'Java Plugin Integration', link: '/specifications/java-api' },
                    {
                        text: 'Message Channels',
                        collapsed: true,
                        items: [
                            { text: 'Channel Overview', link: '/specifications/message/' },
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
                        text: 'Task Types',
                        collapsed: true,
                        items: [
                            { text: 'Task Overview', link: '/specifications/task/' },
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
                        text: 'Events',
                        collapsed: true,
                        items: [
                            { text: 'Event Overview', link: '/specifications/event/' },
                            { text: 'Player Events', link: '/specifications/event/player-events' },
                            { text: 'Entity Events', link: '/specifications/event/entity-events' },
                            { text: 'Block Events', link: '/specifications/event/block-events' },
                            { text: 'Inventory Events', link: '/specifications/event/inventory-events' },
                            { text: 'Server Events', link: '/specifications/event/server-events' },
                        ],
                    },
                    {
                        text: 'Environment',
                        items: [
                            { text: 'Runtime Environment Standard', link: '/specifications/runtime/' },
                            { text: 'Native Service Protocol', link: '/specifications/native-service/' },
                            { text: 'Adapter Specification', link: '/specifications/adapter/' },
                        ],
                    },
                ],
            },
        ],
        outline: {
            level: [2, 3],
            label: 'On this page',
        },
        docFooter: {
            prev: 'Previous',
            next: 'Next',
        },
        sidebarMenuLabel: 'Menu',
        returnToTopLabel: 'Back to top',
        darkModeSwitchLabel: 'Theme',
        lightModeSwitchTitle: 'Switch to light mode',
        darkModeSwitchTitle: 'Switch to dark mode',
        editLink: {
            pattern: 'https://github.com/iyexin/yeow/edit/main/yeow-doc-website/docs/en/:path',
            text: 'Edit this page on GitHub',
        },
        search: {
            provider: 'local',
            options: {
                translations: {
                    button: { buttonText: 'Search docs', buttonAriaLabel: 'Search docs' },
                    modal: {
                        noResultsText: 'No results found',
                        resetButtonTitle: 'Clear query',
                        footer: { selectText: 'Select', navigateText: 'Navigate', closeText: 'Close' },
                    },
                },
            },
        },
    },
})
