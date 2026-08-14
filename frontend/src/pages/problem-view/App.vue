<script setup lang="ts">
/**
 * 题面工具窗口页(WP12 空壳)。
 *
 * 附带「桥自测」区块:点击发送 bridge:echo,回声 bridge:echo:reply 展示在页面上。
 * - IDE 内(JCEF + Kotlin 注入 cqSend):真实走 CQBridge 往返
 * - 纯浏览器(dev):cq-bridge mock 回环,直接收到回声
 * 这是 WP11 留下的沙箱验收测试点的页面侧入口。
 */
import { onMounted, onUnmounted, ref } from 'vue'
import AppShell from '../../components/AppShell.vue'
import {
  TOPIC_ECHO,
  TOPIC_ECHO_REPLY,
  cqBridge,
} from '../../bridge/cq-bridge'
import { renderCode } from '../../lib/highlight'
import { renderMath } from '../../lib/katex-render'

const SAMPLE_CODE = `fun main() {
    val n = readLine()!!.toInt()
    println(if (n % 2 == 0) "even" else "odd")
}`

const bridge = cqBridge()
const echoState = ref<'idle' | 'waiting' | 'ok'>('idle')
const echoDetail = ref('')
const codeBlock = ref<HTMLElement | null>(null)
const mathInline = ref<HTMLElement | null>(null)
const mathDisplay = ref<HTMLElement | null>(null)

onMounted(() => {
  // 走通 highlight.ts / katex-render.ts 封装(产物内为本地资源,不引 CDN)
  renderCode(codeBlock.value, SAMPLE_CODE, 'kotlin')
  renderMath(mathInline.value, 'a^2 + b^2 = c^2', false)
  renderMath(mathDisplay.value, '\\int_0^1 x^2\\,dx = \\frac{1}{3}', true)
})

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
</script>

<template>
  <AppShell
    title="Problem View"
    subtitle="Problem statement rendering with markdown, code highlight and KaTeX."
  >
    <section class="placeholder">
      <p class="placeholder__mark">Problem View</p>
      <p>Problem statement rendering with markdown, code highlight and KaTeX.</p>
      <p class="placeholder__hint">
        Placeholder page mounted by WP12 scaffolding. Statement content will be
        fetched from the local ResourceHttpServer REST endpoints.
      </p>
    </section>

    <section class="render-demo">
      <h2>Renderers preview</h2>
      <div ref="codeBlock" class="render-demo__code" aria-label="code highlight sample"></div>
      <p>
        Inline math: <span ref="mathInline"></span>
      </p>
      <div ref="mathDisplay" aria-label="display math sample"></div>
    </section>

    <section class="echo">
      <button type="button" class="echo__btn" @click="runEchoTest">
        Bridge self-test (bridge:echo)
      </button>
      <span class="echo__state" :data-state="echoState">
        <template v-if="echoState === 'idle'">not run</template>
        <template v-else-if="echoState === 'waiting'">waiting for bridge:echo:reply…</template>
        <template v-else>reply {{ echoDetail }}</template>
      </span>
      <span class="echo__mode">
        {{ bridge.isRealBridge() ? 'real bridge' : 'mock loopback' }}
      </span>
    </section>
  </AppShell>
</template>

<style scoped>
.placeholder {
  color: var(--cq-muted);
  border: 1px dashed var(--cq-border);
  border-radius: 8px;
  padding: 24px;
  background: var(--cq-panel);
}

.placeholder__mark {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 650;
  color: var(--cq-fg);
}

.placeholder__hint {
  font-size: 12px;
}

.render-demo {
  margin-top: 12px;
  padding: 12px;
  border: 1px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel);
}

.render-demo h2 {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--cq-muted);
}

.render-demo__code {
  font-size: 12px;
  overflow-x: auto;
}

.echo {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--cq-muted);
}

.echo__btn {
  border: 1px solid var(--cq-border);
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  border-radius: 6px;
  padding: 4px 12px;
  cursor: pointer;
}

.echo__btn:hover {
  border-color: var(--cq-accent);
}

.echo__state[data-state='ok'] {
  color: var(--cq-success);
}

.echo__mode {
  border: 1px solid var(--cq-border);
  border-radius: 999px;
  padding: 1px 8px;
}
</style>
