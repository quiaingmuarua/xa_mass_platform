import {createApp} from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import {getAppConfig} from '@/app/config'
import '@/app/styles.css'
import AppRoot from '@/app/AppRoot.vue'
import {registerPermissionDirective} from '@/auth/permission-directive'
import {initializeAuth} from '@/auth/use-auth'
import {router} from '@/router'

export async function bootstrapApp(): Promise<void> {
    try {
        document.title = getAppConfig().appTitle
        await initializeAuth()

        const app = createApp(AppRoot)
        app.use(ElementPlus)
        app.use(router)
        registerPermissionDirective(app)

        await router.isReady()
        app.mount('#app')
    } catch (error) {
        renderStartupError(error)
    }
}

function renderStartupError(error: unknown): void {
    console.error('Failed to bootstrap XA Mass Control Console.', error)

    const mountTarget = document.querySelector('#app')
    if (!mountTarget) {
        return
    }

    const message =
        error instanceof Error && error.message.trim().length > 0
            ? error.message
            : 'The console failed to start.'

    mountTarget.innerHTML = `
        <main class="startup-error">
            <section class="startup-error-card">
                <p class="startup-error-eyebrow">XA Mass Control Console</p>
                <h1>Console startup failed</h1>
                <p class="startup-error-message">${escapeHtml(message)}</p>
                <p class="startup-error-hint">
                    Check backend availability, /api/auth/me, and browser console logs.
                </p>
            </section>
        </main>
    `
}

function escapeHtml(value: string): string {
    return value
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#039;')
}
