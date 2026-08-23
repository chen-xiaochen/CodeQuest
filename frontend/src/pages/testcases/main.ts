import { createApp } from 'vue'
import { cqBridge } from '../../bridge/cq-bridge'
import { initTheme } from '../../theme/theme'
import App from './App.vue'

initTheme()
cqBridge()
createApp(App).mount('#app')
