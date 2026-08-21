import type { Theme } from 'vitepress'
import DefaultTheme from 'vitepress/theme'
import CustomHome from './CustomHome.vue'
import Layout from './Layout.vue'
import './style.css'

export default {
    extends: DefaultTheme,
    Layout, // 覆写默认布局：在导航栏注入多语言切换（Layout.vue → #nav-bar-content-after）
    enhanceApp({ app }) {
        // 自定义布局：VPContent 通过 :is="frontmatter.layout" 解析全局组件名
        app.component('custom-home', CustomHome)
    },
} satisfies Theme
