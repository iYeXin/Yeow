<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, withBase } from 'vitepress'

const open = ref(false)
const rootEl = ref(null)
const route = useRoute()

const target = computed(() => {
  if (typeof window === 'undefined') return { label: 'English', href: '/' }
  const isCn = window.location.hostname === 'cn.yexin.wiki'
  const path = route.path + window.location.search + window.location.hash
  return {
    label: isCn ? 'English' : '中文',
    href: `https://${isCn ? 'yexin.wiki' : 'cn.yexin.wiki'}${path}`,
  }
})

function onDocClick(e) {
  if (rootEl.value && !rootEl.value.contains(e.target)) open.value = false
}
watch(open, (o) => {
  document[o ? 'addEventListener' : 'removeEventListener']('click', onDocClick)
})
onUnmounted(() => document.removeEventListener('click', onDocClick))
</script>

<template>
  <div class="vt-locales" ref="rootEl" @mouseenter="open = true" @mouseleave="open = false">
    <button
      class="vt-locales-btn"
      type="button"
      aria-haspopup="true"
      :aria-expanded="open"
      aria-label="Change language / 切换语言"
      @click="open = !open"
    >
      <span class="vt-locales-btn-icon" aria-hidden="true">
        <svg xmlns="http://www.w3.org/2000/svg" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" viewBox="0 0 24 24"><path d="m5 8 6 6M4 14l6-6 2-3M2 5h12M7 2h1M22 22l-5-10-5 10M14 18h6"/></svg>
      </span>
      <svg class="vt-locales-btn-chevron" xmlns="http://www.w3.org/2000/svg" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" viewBox="0 0 24 24"><path d="m6 9 6 6 6-6"/></svg>
    </button>

    <div class="vt-locales-menu" :class="{ open }">
      <div class="vt-locales-panel">
        <ul class="vt-locales-dropdown">
          <li>
            <a class="vt-locales-link" :href="target.href">{{ target.label }}</a>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<style scoped>
.vt-locales {
  position: relative;
  display: flex;
  align-items: center;
}

.vt-locales-btn {
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 0 8px;
  height: var(--vp-nav-height);
  color: var(--vp-c-text-1);
  background: transparent;
  border: 0;
  font-family: inherit;
  cursor: pointer;
  transition: color 0.5s;
}
.vt-locales-btn:hover {
  color: var(--vp-c-brand-1);
}

.vt-locales-btn-icon {
  display: block;
  flex: none;
  line-height: 0;
  width: 16px;
  height: 16px;
  color: var(--vp-c-text-2);
  transition: color 0.25s;
}
.vt-locales-btn-icon svg {
  display: block;
  width: 100%;
  height: 100%;
  vertical-align: middle;
}
.vt-locales-btn:hover .vt-locales-btn-icon {
  color: var(--vp-c-text-1);
}

.vt-locales-btn-chevron {
  display: block;
  flex: none;
  line-height: 0;
  width: 14px;
  height: 14px;
  color: var(--vp-c-text-2);
}
.vt-locales-btn-chevron svg {
  display: block;
  width: 100%;
  height: 100%;
}

.vt-locales-menu {
  position: absolute;
  top: calc(var(--vp-nav-height) / 2 + 20px);
  right: 0;
  opacity: 0;
  visibility: hidden;
  transition: opacity 0.25s, visibility 0.25s, transform 0.25s;
}
.vt-locales-menu.open,
.vt-locales:hover .vt-locales-menu {
  opacity: 1;
  visibility: visible;
  transform: translateY(0);
}

.vt-locales-panel {
  min-width: 128px;
  border: 1px solid var(--vp-c-divider);
  border-radius: 12px;
  padding: 6px 0;
  background-color: var(--vp-c-bg-soft);
  box-shadow: var(--vp-shadow-3);
}

.vt-locales-dropdown {
  list-style: none;
  margin: 0;
  padding: 0;
}

.vt-locales-link {
  display: block;
  border-radius: 6px;
  padding: 0 12px;
  line-height: 32px;
  font-size: 14px;
  font-weight: 500;
  color: var(--vp-c-text-1);
  white-space: nowrap;
  text-decoration: none;
  transition: background-color 0.25s, color 0.25s;
}
.vt-locales-link:hover {
  color: var(--vp-c-brand-1);
  background-color: var(--vp-c-default-soft);
}
/* 高亮 = 当前语言 */
.vt-locales-link.active {
  color: var(--vp-c-brand-1);
}
</style>

<style>
.VPNavBar .content-body > .vt-locales {
  order: 1;
}
.VPNavBar .content-body > .appearance {
  order: 2;
}
.VPNavBar .content-body > .social-links {
  order: 3;
}
.VPNavBar .content-body > .vt-locales::before,
.VPNavBar .content-body > .appearance::before {
  align-self: center;
  margin-right: 8px;
  margin-left: 8px;
  width: 1px;
  height: 24px;
  background-color: var(--vp-c-divider);
  content: "";
}
</style>
