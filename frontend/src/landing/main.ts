import { createApp } from 'vue'
import { initTheme } from '../theme/theme'
import App from './App.vue'

initTheme()
createApp(App).mount('#app')
