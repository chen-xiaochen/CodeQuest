<script setup lang="ts">
/**
 * 落地页(/index.html,ResourceHttpServer 的默认入口;WP50 中文化):
 * 列出六页面入口,并提供两个沙箱验收探针——
 * - /api/health 探活(ResourceHttpServer REST 端点)
 * - 桥 echo 自测(IDE 内真实往返;纯浏览器走 mock 回环)
 * API 术语(GET /api/health、bridge:echo 等)保留原文。
 */
import { onUnmounted, ref } from 'vue'
import { TOPIC_ECHO, TOPIC_ECHO_REPLY, cqBridge } from '../bridge/cq-bridge'

const PAGES = [
  { href: '/problems/', label: '题库', desc: '按平台浏览与筛选题目(平台/关键字/难度)' },
  { href: '/contest/', label: '竞赛', desc: 'CodeForces 竞赛列表与开始提醒' },
  { href: '/stats/', label: '统计', desc: '提交热力图与趋势' },
  { href: '/search/', label: '本地搜索', desc: '跨题目全文检索(FTS)' },
  { href: '/problem-view/', label: '题面', desc: '题目描述渲染(编辑器侧)' },
  { href: '/submissions/', label: '提交记录', desc: '提交结果与历史(编辑器侧)' },
]

const bridge = cqBridge()
const echoState = ref<'idle' | 'waiting' | 'ok'>('idle')
const echoDetail = ref('')
const health = ref('未探测')

const offReply = bridge.on(TOPIC_ECHO_REPLY, (payload) => {
  echoState.value = 'ok'
  echoDetail.value = JSON.stringify(payload)
})

onUnmounted(offReply)

function runEchoTest(): void {
  echoState.value = 'waiting'
  echoDetail.value = ''
  bridge.send(TOPIC_ECHO, { hello: 'cq' })
}

async function probeHealth(): Promise<void> {
  try {
    const response = await fetch('/api/health')
    health.value = `${response.status} ${await response.text()}`
  } catch (error) {
    health.value = `失败: ${String(error)}`
  }
}
</script>

<template>
  <main class="landing">
    <h1>CodeQuest Webview</h1>
    <p class="landing__sub">
      六页面入口 · 主题自适应 · highlight.js + KaTeX(本地)
    </p>

    <ul class="landing__pages">
      <li v-for="page in PAGES" :key="page.href">
        <a :href="page.href">{{ page.label }}</a>
        <span class="landing__desc">{{ page.desc }}</span>
      </li>
    </ul>

    <section class="landing__probes">
      <div>
        <button type="button" @click="probeHealth">GET /api/health 探活</button>
        <span class="landing__result">{{ health }}</span>
      </div>
      <div>
        <button type="button" @click="runEchoTest">桥自测(bridge:echo)</button>
        <span class="landing__result" :data-ok="echoState === 'ok'">
          <template v-if="echoState === 'idle'">未运行</template>
          <template v-else-if="echoState === 'waiting'">等待 bridge:echo:reply 回复…</template>
          <template v-else>回复 {{ echoDetail }}</template>
        </span>
        <span class="landing__mode">
          {{ bridge.isRealBridge() ? '真实桥' : '模拟回环' }}
        </span>
      </div>
    </section>
  </main>
</template>

<style scoped>
.landing {
  max-width: 720px;
  margin: 0 auto;
  padding: 32px 16px;
}

.landing h1 {
  margin: 0 0 4px;
}

.landing__sub {
  color: var(--cq-muted);
  font-size: 12px;
  margin: 0 0 24px;
}

.landing__pages {
  list-style: none;
  padding: 0;
  margin: 0 0 24px;
  display: grid;
  gap: 8px;
}

.landing__pages li {
  display: flex;
  gap: 12px;
  align-items: baseline;
  padding: 10px 12px;
  border: 1px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel);
}

.landing__desc {
  color: var(--cq-muted);
  font-size: 12px;
}

.landing__probes {
  display: grid;
  gap: 10px;
}

.landing__probes button {
  border: 1px solid var(--cq-border);
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  border-radius: 6px;
  padding: 4px 12px;
  cursor: pointer;
  margin-right: 10px;
}

.landing__result {
  font-size: 12px;
  color: var(--cq-muted);
}

.landing__result[data-ok='true'] {
  color: var(--cq-success);
}

.landing__mode {
  margin-left: 10px;
  font-size: 12px;
  color: var(--cq-muted);
  border: 1px solid var(--cq-border);
  border-radius: 999px;
  padding: 1px 8px;
}
</style>
