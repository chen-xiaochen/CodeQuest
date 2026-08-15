<script setup lang="ts">
/**
 * 题面页(WP20 真实数据):URL query `?platform=&remoteId=`(Kotlin 侧
 * ProblemViewNavigator 构造)→ /api/problem 拉取 → 渲染管线(清洗+hljs/KaTeX)。
 */
import { computed, onMounted, ref } from 'vue'
import AppShell from '../../components/AppShell.vue'
import { ApiError, fetchProblemDetail, type ProblemDetail } from '../../lib/api'
import { difficultyClass } from '../../lib/difficulty'
import { renderStatement } from '../../lib/statement'

const detail = ref<ProblemDetail | null>(null)
const loading = ref(false)
const error = ref('')

const params = new URLSearchParams(window.location.search)
const platform = params.get('platform') ?? ''
const remoteId = params.get('remoteId') ?? ''

const renderedHtml = computed(() => {
  if (!detail.value) return ''
  return renderStatement(detail.value.content, detail.value.contentFormat)
})

function describeError(cause: unknown): string {
  if (cause instanceof ApiError) {
    return `[${cause.code}] ${cause.message}`
  }
  return cause instanceof Error ? cause.message : String(cause)
}

async function load(): Promise<void> {
  if (!platform || !remoteId) return
  loading.value = true
  error.value = ''
  try {
    detail.value = await fetchProblemDetail(platform, remoteId)
  } catch (cause) {
    error.value = describeError(cause)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <AppShell title="Problem View" subtitle="Problem statement with sanitized HTML / markdown rendering.">
    <p v-if="!platform || !remoteId" class="state">
      No problem selected. Open one from the Problems list.
    </p>

    <p v-else-if="error" class="state state--error" role="alert">
      {{ error }}
      <button type="button" class="state__retry" @click="load">Retry</button>
    </p>

    <p v-else-if="loading" class="state">Loading problem statement…</p>

    <template v-else-if="detail">
      <header class="statement-header">
        <h2 class="statement-header__title">
          <span class="statement-header__remote">{{ detail.remoteId }}</span>
          {{ detail.title }}
        </h2>
        <p class="statement-header__meta">
          <span class="diff-badge" :class="difficultyClass(detail.difficulty)">{{ detail.difficulty }}</span>
          <span class="statement-header__source" :data-source="detail.source">{{ detail.source }}</span>
          <a
            v-if="detail.url"
            :href="detail.url"
            target="_blank"
            rel="noopener noreferrer"
          >
            open on site
          </a>
        </p>
      </header>

      <!-- eslint-disable-next-line vue/no-v-html — 内容经 renderStatement 白名单清洗 -->
      <article class="statement" v-html="renderedHtml"></article>
    </template>
  </AppShell>
</template>

<style scoped>
.state {
  margin: 16px 0;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--cq-muted);
  border: 1px dashed var(--cq-border);
}

.state--error {
  color: var(--cq-danger);
  border: 1px solid var(--cq-danger);
  background: var(--cq-panel);
}

.state__retry {
  margin-left: 10px;
  padding: 2px 10px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  cursor: pointer;
}

.statement-header {
  margin-bottom: 12px;
}

.statement-header__title {
  margin: 0 0 6px;
  font-size: 17px;
  line-height: 1.4;
}

.statement-header__remote {
  display: block;
  font-size: 11px;
  color: var(--cq-muted);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
}

.statement-header__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  font-size: 12px;
  color: var(--cq-muted);
}

.statement-header__source {
  padding: 1px 8px;
  border: 1px solid var(--cq-border);
  border-radius: 999px;
}

.statement-header__source[data-source='cache'] {
  color: var(--cq-success);
  border-color: var(--cq-success);
}

.diff-badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  color: #ffffff;
}

.diff-badge--easy {
  background: var(--cq-diff-easy);
}

.diff-badge--medium {
  background: var(--cq-diff-medium);
}

.diff-badge--hard {
  background: var(--cq-diff-hard);
}

.diff-badge--other {
  background: var(--cq-diff-other);
}
</style>
