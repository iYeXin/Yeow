<script setup lang="ts">
import { withBase } from 'vitepress'

const entries = [
    {
        link: '/overview',
        title: '概览',
        desc: '按角色导引：初学者 / 开发者 / 管理员 / 平台实现者',
        icon: 'compass',
    },
    {
        link: '/getting-started',
        title: '快速开始',
        desc: '创建项目、第一个插件、部署与权限声明',
        icon: 'zap',
    },
    {
        link: '/api/',
        title: 'API 参考',
        desc: '按模块分组的完整 API 索引',
        icon: 'code',
    },
    {
        link: '/advanced',
        title: '进阶知识',
        desc: '架构、线程模型、调度器原理',
        icon: 'layers',
    },
    {
        link: '/specifications/',
        title: '平台规范',
        desc: '协议层定义，供运行时实现者参考',
        icon: 'terminal',
    },
]

const icons = {
    compass: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88"/></svg>',
    zap: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>',
    code: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>',
    layers: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>',
    terminal: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3" width="20" height="18" rx="2"/><line x1="7" y1="15" x2="11" y2="15"/><line x1="14" y1="15" x2="17" y2="15"/></svg>',
}

const lines = [
    { cmd: 'npm create yeow@latest -- -y' },
    { cmd: 'cd my-plugin' },
    { cmd: 'npm install' },
    { cmd: 'npm run dev', comment: '本地服务器 + 热重载' },
    { cmd: 'npm run build', comment: '产出 .jar 与 .yeow.zip' },
]
</script>

<template>
    <div class="yeow-home">
        <!-- ── Hero ── -->
        <section class="hero">
            <span class="overline">Yeow v1</span>
            <h1>
                用 <span class="grad">TypeScript</span> 写<br />
                Minecraft <span class="grad">Paper</span> 插件
            </h1>
            <p class="subtitle">
                Yeow 为 Paper 服务器提供 TypeScript / JavaScript 插件开发框架，
                每个插件运行在独立的 QuickJS 线程中，与游戏主线程通过消息桥交互。
            </p>
            <div class="cta">
                <a class="btn primary" :href="withBase('/getting-started')">快速开始</a>
                <a class="btn ghost" href="https://modrinth.com/plugin/yeow">下载运行时</a>
            </div>
        </section>

        <!-- ── 开始使用 ── -->
        <section class="quickstart">
            <h2 class="section-label">开始使用</h2>
            <div class="term">
                <div class="term-bar">
                    <span class="dot dot-r"></span>
                    <span class="dot dot-y"></span>
                    <span class="dot dot-g"></span>
                    <span class="term-title">terminal</span>
                </div>
                <div class="term-body">
                    <div v-for="(l, i) in lines" :key="i" class="line">
                        <span class="prompt">$</span>
                        <span class="cmd">{{ l.cmd }}</span>
                        <span v-if="l.comment" class="cmt"># {{ l.comment }}</span>
                    </div>
                </div>
            </div>
        </section>

        <!-- ── 文档 ── -->
        <section class="docs">
            <h2 class="section-label">文档</h2>
            <div class="docs-grid">
                <a v-for="e in entries" :key="e.title" class="doc-card" :href="withBase(e.link)">
                    <span class="icon" v-html="icons[e.icon]"></span>
                    <div class="card-body">
                        <h3>{{ e.title }}</h3>
                        <p>{{ e.desc }}</p>
                    </div>
                    <span class="arrow">→</span>
                </a>
            </div>
        </section>

        <!-- ── 管理员 ── -->
        <section class="admin">
            <span class="admin-icon" v-html="icons.terminal"></span>
            <p>
                服务器管理员：安装 <a href="https://modrinth.com/plugin/yeow">Yeow 运行时</a> 后，
                插件包放入 <code>plugins/Yeow/</code> 自动加载，或
                <code>/yeow install &lt;your-plugin-url&gt;</code> 一键安装。
            </p>
        </section>

        <footer class="home-footer">
            <a :href="withBase('/overview')">概览</a>
            <a :href="withBase('/distribution')">构建与分发</a>
            <a href="https://github.com/iyexin/yeow">GitHub</a>
            <a href="https://modrinth.com/plugin/yeow">Modrinth</a>
        </footer>
    </div>
</template>

<style scoped>
.yeow-home {
    position: relative;
    min-height: calc(100vh - var(--vp-nav-height));
    background: var(--vp-c-bg);
    max-width: 900px;
    margin: 0 auto;
    padding: 80px 24px 48px;
    overflow: hidden;
}

/* ── Hero ── */
.hero {
    position: relative;
    text-align: center;
    padding: 40px 0 64px;
    z-index: 1;
}
.hero::before {
    content: '';
    position: absolute;
    top: -120px;
    left: 50%;
    transform: translateX(-50%);
    width: 640px;
    height: 360px;
    background: radial-gradient(ellipse 50% 50% at 50% 50%, var(--vp-c-brand-soft), transparent 70%);
    z-index: -1;
    pointer-events: none;
}
.overline {
    display: inline-block;
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.18em;
    text-transform: uppercase;
    color: var(--vp-c-brand-1);
    border: 1px solid var(--vp-c-border);
    background: var(--vp-c-bg-soft);
    border-radius: 999px;
    padding: 5px 14px;
    margin-bottom: 24px;
}
.hero h1 {
    font-size: clamp(32px, 5vw, 48px);
    font-weight: 800;
    letter-spacing: -0.03em;
    line-height: 1.18;
    margin: 0 0 18px;
    color: var(--vp-c-text-1);
}
.grad {
    background: var(--yeow-grad);
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
}
.subtitle {
    font-size: 15.5px;
    line-height: 1.8;
    color: var(--vp-c-text-2);
    max-width: 560px;
    margin: 0 auto 32px;
}
.cta {
    display: flex;
    gap: 12px;
    justify-content: center;
    flex-wrap: wrap;
}
.btn {
    display: inline-flex;
    align-items: center;
    padding: 10px 24px;
    border-radius: 10px;
    font-size: 15px;
    font-weight: 600;
    text-decoration: none;
    transition: transform 0.15s ease, box-shadow 0.15s ease, border-color 0.15s ease, background 0.15s ease;
}
.btn:hover {
    transform: translateY(-1px);
}
.btn.primary {
    color: #04140c;
    background: var(--yeow-grad);
    box-shadow: 0 4px 18px var(--vp-c-brand-soft);
}
.btn.primary:hover {
    box-shadow: 0 8px 26px var(--vp-c-brand-soft);
}
.btn.ghost {
    color: var(--vp-c-text-1);
    border: 1px solid var(--vp-c-border);
    background: var(--vp-c-bg-soft);
}
.btn.ghost:hover {
    border-color: var(--vp-c-brand-1);
    color: var(--vp-c-brand-1);
}

/* ── 区块标题 ── */
.section-label {
    text-align: center;
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.18em;
    text-transform: uppercase;
    color: var(--vp-c-text-3);
    margin: 0 0 20px;
}

/* ── 终端 ── */
.quickstart {
    margin-bottom: 56px;
}
.term {
    border-radius: 12px;
    border: 1px solid var(--vp-c-border);
    background: var(--vp-code-block-bg);
    overflow: hidden;
    box-shadow: 0 8px 30px rgba(0, 0, 0, 0.18);
}
.term-bar {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 10px 14px;
    border-bottom: 1px solid var(--vp-c-border);
    background: var(--vp-c-bg-soft);
}
.dot {
    width: 11px;
    height: 11px;
    border-radius: 50%;
}
.dot-r { background: #f87171; }
.dot-y { background: #fbbf24; }
.dot-g { background: #34d399; }
.term-title {
    margin-left: 10px;
    font-size: 12px;
    color: var(--vp-c-text-3);
    font-family: ui-monospace, Consolas, monospace;
}
.term-body {
    padding: 18px 22px;
}
.line {
    font-family: ui-monospace, 'Cascadia Code', Consolas, monospace;
    font-size: 13.5px;
    line-height: 2;
    white-space: nowrap;
    overflow-x: auto;
}
.prompt {
    color: var(--vp-c-brand-1);
    font-weight: 700;
    margin-right: 8px;
    user-select: none;
}
.cmd {
    color: var(--vp-c-text-1);
}
.cmt {
    color: var(--vp-c-text-3);
    margin-left: 12px;
}

/* ── 文档入口 ── */
.docs {
    margin-bottom: 56px;
}
.docs-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
    gap: 14px;
}
.doc-card {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 18px 20px;
    border-radius: 12px;
    border: 1px solid var(--vp-c-border);
    background: var(--vp-c-bg-soft);
    text-decoration: none;
    transition: border-color 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease;
}
.doc-card:hover {
    border-color: var(--vp-c-brand-1);
    transform: translateY(-2px);
    box-shadow: 0 8px 22px var(--vp-c-brand-soft);
}
.icon {
    flex: 0 0 auto;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border-radius: 10px;
    background: var(--vp-c-brand-soft);
    color: var(--vp-c-brand-1);
}
.icon :deep(svg) {
    width: 20px;
    height: 20px;
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;
}
.card-body {
    flex: 1;
    min-width: 0;
}
.card-body h3 {
    font-size: 15px;
    font-weight: 600;
    margin: 0 0 3px;
    color: var(--vp-c-text-1);
}
.card-body p {
    font-size: 13px;
    line-height: 1.5;
    margin: 0;
    color: var(--vp-c-text-2);
}
.arrow {
    color: var(--vp-c-text-3);
    font-size: 15px;
    transition: transform 0.18s ease, color 0.18s ease;
}
.doc-card:hover .arrow {
    color: var(--vp-c-brand-1);
    transform: translateX(3px);
}

/* ── 管理员 ── */
.admin {
    display: flex;
    align-items: center;
    gap: 14px;
    max-width: 720px;
    margin: 0 auto 48px;
    padding: 16px 20px;
    border-radius: 12px;
    border: 1px solid var(--vp-c-border);
    background: var(--vp-c-bg-soft);
}
.admin-icon {
    flex: 0 0 auto;
    color: var(--vp-c-brand-1);
    opacity: 0.85;
}
.admin-icon :deep(svg) {
    width: 20px;
    height: 20px;
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;
}
.admin p {
    font-size: 13.5px;
    line-height: 1.8;
    color: var(--vp-c-text-2);
    margin: 0;
}
.admin a {
    color: var(--vp-c-brand-1);
    text-decoration: none;
    font-weight: 500;
}
.admin a:hover {
    text-decoration: underline;
}
.admin code {
    font-family: ui-monospace, Consolas, monospace;
    font-size: 12.5px;
    color: var(--vp-c-brand-1);
    background: var(--vp-code-bg, var(--vp-c-bg));
    border: 1px solid var(--vp-c-border);
    padding: 1px 6px;
    border-radius: 5px;
}

/* ── Footer ── */
.home-footer {
    display: flex;
    gap: 22px;
    justify-content: center;
    flex-wrap: wrap;
    padding-top: 8px;
}
.home-footer a {
    font-size: 13px;
    color: var(--vp-c-text-3);
    text-decoration: none;
    transition: color 0.15s ease;
}
.home-footer a:hover {
    color: var(--vp-c-brand-1);
}

@media (max-width: 640px) {
    .yeow-home {
        padding: 48px 20px 40px;
    }
    .hero {
        padding: 24px 0 48px;
    }
}
</style>
