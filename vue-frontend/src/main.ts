import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import App from './App.vue'

import './styles/global.css'
import '@/utils/tac.css'
// 加载验证码 SDK（挂载 window.TAC，副作用导入）
import '@/utils/tac.min.js'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
