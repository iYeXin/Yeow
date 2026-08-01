import type { Theme } from 'vitepress'
import DefaultTheme from 'vitepress/theme'
import CustomHome from './CustomHome.vue'
import './style.css'

export default {
    extends: DefaultTheme,
    enhanceApp({ app }) {
        // 自定义布局：VPContent 通过 :is="frontmatter.layout" 解析全局组件名
        app.component('custom-home', CustomHome)
    },
} satisfies Theme
