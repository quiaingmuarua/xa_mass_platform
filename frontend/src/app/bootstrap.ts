import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import { appConfig } from '@/app/config'
import '@/app/styles.css'
import AppRoot from '@/app/AppRoot.vue'
import { registerPermissionDirective } from '@/auth/permission-directive'
import { initializeAuth } from '@/auth/use-auth'
import { router } from '@/router'

export async function bootstrapApp(): Promise<void> {
    document.title = appConfig.appTitle
    await initializeAuth()

    const app = createApp(AppRoot)
    app.use(ElementPlus)
    app.use(router)
    registerPermissionDirective(app)

    await router.isReady()
    app.mount('#app')
}
