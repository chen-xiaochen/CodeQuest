<script setup lang="ts">
/**
 * 本地搜索独立路由页(/search/ 保留):WP49 起列表逻辑抽为 SearchPanel 组件
 * (与题库页「本地搜索」子视图共用),本页仅保留外壳与标题(中文化)。
 */
import AppShell from '../../components/AppShell.vue'
import SearchPanel from './SearchPanel.vue'
</script>

<template>
  <AppShell title="本地搜索" subtitle="跨题目标题、题面与题解笔记的全文检索,支持标签过滤。">
    <SearchPanel />
  </AppShell>
</template>
