import type { Theme } from 'vitepress'
import DefaultTheme from 'vitepress/theme'
import CustomHome from './CustomHome.vue'
import Layout from './Layout.vue'
import './style.css'

/** EN site custom theme: extends default theme, injects language switcher in navbar. */
export default {
    extends: DefaultTheme,
    Layout, // Override default layout: inject language switcher in navbar (Layout.vue → #nav-bar-content-after)
    enhanceApp({ app }) {
        // Custom layout: VPContent resolves global component name via :is="frontmatter.layout"
        app.component('custom-home', CustomHome)
    },
} satisfies Theme
