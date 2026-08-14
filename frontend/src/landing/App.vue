<script setup lang="ts">
/**
 * 落地页(/index.html,ResourceHttpServer 的默认入口):
 * 列出六页面入口,并提供两个沙箱验收探针——
 * - /api/health 探活(ResourceHttpServer REST 端点)
 * - 桥 echo 自测(IDE 内真实往返;纯浏览器走 mock 回环)
 */
import { onUnmounted, ref } from 'vue'
import { TOPIC_ECHO, TOPIC_ECHO_REPLY, cqBridge } from '../bridge/cq-bridge'

const PAGES = [
  { href: '/problems/', label: 'Problems', desc: 'Problem list (platform filters / keywords / tags)' },
  { href: '/contest/', label: 'Contest', desc: 'CodeForces contests with reminders' },
  { href: '/stats/', label: 'Statistics', desc: 'Heatmap and trends' },
  { href: '/search/', label: 'Search', desc: 'Cross-problem full-text search (FTS)' },
  { href: '/problem-view/', label: 'Problem View', desc: 'Statement rendering (editor side)' },
  { href: '/submissions/', label: 'Submissions', desc: 'Submission results / history (editor side)' },
]

const bridge = cqBridge()
const echoState = ref<'idle' | 'waiting' | 'ok'>('idle')
const echoDetail = ref('')
const health = ref('not probed')

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
    health.value = `failed: ${String(error)}`
  }
}
</script>

<template>
  <main class="landing">
    <h1>CodeQuest Webview</h1>
    <p class="landing__sub">
      WP12 scaffolding · per-page entries · theme-aware · highlight.js + KaTeX (local)
    </p>

    <ul class="landing__pages">
      <li v-for="page in PAGES" :key="page.href">
        <a :href="page.href">{{ page.label }}</a>
        <span class="landing__desc">{{ page.desc }}</span>
      </li>
    </ul>

    <section class="landing__probes">
      <div>
        <button type="button" @click="probeHealth">GET /api/health</button>
        <span class="landing__result">{{ health }}</span>
      </div>
      <div>
        <button type="button" @click="runEchoTest">Bridge self-test (bridge:echo)</button>
        <span class="landing__result" :data-ok="echoState === 'ok'">
          <template v-if="echoState === 'idle'">not run</template>
          <template v-else-if="echoState === 'waiting'">waiting for bridge:echo:reply…</template>
          <template v-else>reply {{ echoDetail }}</template>
        </span>
        <span class="landing__mode">
          {{ bridge.isRealBridge() ? 'real bridge' : 'mock loopback' }}
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
