<script setup lang="ts">
/**
 * 页面公共外壳:标题 + 副标题 + 内容槽。
 * 各 toolwindow 页面共用,保持视觉一致。1.5.0 起导航由工具窗顶部 tab 承担,
 * 壳内不再渲染页面级导航。
 */
defineProps<{
  title: string
  subtitle?: string
}>()
</script>

<template>
  <div class="cq-shell">
    <header class="cq-shell__header">
      <h1 class="cq-shell__title">{{ title }}</h1>
      <p v-if="subtitle" class="cq-shell__subtitle">{{ subtitle }}</p>
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
  padding: 12px 16px;
  border-bottom: 1px solid var(--cq-border);
  background: var(--cq-panel);
}

.cq-shell__title {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
}

.cq-shell__subtitle {
  margin: 2px 0 0;
  color: var(--cq-muted);
  font-size: 12px;
}

.cq-shell__main {
  flex: 1;
  padding: 16px;
}
</style>
