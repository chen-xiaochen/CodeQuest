<script setup lang="ts">
/**
 * 页面公共外壳:标题 + 导航占位 + 内容槽。
 * 六个页面共用,保持各 toolwindow 页面视觉一致。
 */
defineProps<{
  title: string
  subtitle?: string
}>()

const NAV = [
  { href: '/problems/', label: '题库' },
  { href: '/contest/', label: '竞赛' },
  { href: '/stats/', label: '统计' },
  { href: '/search/', label: '本地搜索' },
  { href: '/problem-view/', label: '题面' },
  { href: '/submissions/', label: '提交记录' },
]
</script>

<template>
  <div class="cq-shell">
    <header class="cq-shell__header">
      <h1 class="cq-shell__title">{{ title }}</h1>
      <p v-if="subtitle" class="cq-shell__subtitle">{{ subtitle }}</p>
      <nav class="cq-shell__nav" aria-label="CodeQuest 页面">
        <a
          v-for="item in NAV"
          :key="item.href"
          :href="item.href"
          class="cq-shell__nav-item"
        >
          {{ item.label }}
        </a>
      </nav>
    </header>
    <main class="cq-shell__main">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.cq-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.cq-shell__header {
  padding: 12px 16px 0;
  border-bottom: 1px solid var(--cq-border);
  background: var(--cq-panel);
}

.cq-shell__title {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
}

.cq-shell__subtitle {
  margin: 2px 0 8px;
  color: var(--cq-muted);
  font-size: 12px;
}

.cq-shell__nav {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding-bottom: 8px;
}

.cq-shell__nav-item {
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  text-decoration: none;
  color: var(--cq-muted);
  border: 1px solid transparent;
}

.cq-shell__nav-item:hover {
  color: var(--cq-fg);
  border-color: var(--cq-border);
  background: var(--cq-panel-raised);
}

.cq-shell__main {
  flex: 1;
  padding: 16px;
}
</style>
