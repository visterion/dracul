import { createApp } from 'vue'
import { createPinia } from 'pinia'
import vuetify from './plugins/vuetify'
import router from './plugins/router'
import App from './App.vue'
import { i18n, setLocale } from './i18n'
import { useApi } from './api'
import './styles/global.css'

const app = createApp(App)
app.use(createPinia())
app.use(vuetify)
app.use(router)
app.use(i18n)

// Bootstrap locale from the backend (server-authoritative). Fall back to `de`.
useApi().getLanguage()
  .then(({ language }) => setLocale(language))
  .catch(() => setLocale('de'))

app.mount('#app')
