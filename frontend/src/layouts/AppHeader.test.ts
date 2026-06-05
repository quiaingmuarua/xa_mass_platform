import ElementPlus from 'element-plus'
import { mount } from '@vue/test-utils'
import type { RouteRecordRaw } from 'vue-router'
import { createMemoryHistory, createRouter } from 'vue-router'
import { setRuntimeConfigOverrides } from '@/app/config'
import { setBackendAuthConfig } from '@/auth/backend-auth'
import { mockAdminUser } from '@/auth/mock-user'
import { setMockCurrentUser } from '@/auth/use-auth'
import AppHeader from '@/layouts/AppHeader.vue'
import { appRoutes } from '@/router/routes'

async function mountHeader() {
    const router = createRouter({
        history: createMemoryHistory(),
        routes: appRoutes as unknown as RouteRecordRaw[],
    })
    await router.push('/')
    await router.isReady()
    return mount(AppHeader, {
        global: {
            plugins: [router, ElementPlus],
        },
    })
}

describe('AppHeader', () => {
    it('shows the dev-header operator selector only when the backend supports it', async () => {
        setRuntimeConfigOverrides({
            useMockAuth: false,
        })
        setMockCurrentUser(mockAdminUser)
        setBackendAuthConfig({
            authMode: 'session',
            operatorHeaderSupported: false,
            sessionCookieSupported: true,
            csrfHeaderName: 'X-Mass-Csrf-Token',
        })

        const sessionWrapper = await mountHeader()
        expect(sessionWrapper.find('.operator-select').exists()).toBe(false)

        setBackendAuthConfig({
            authMode: 'dev-header',
            operatorHeaderSupported: true,
            sessionCookieSupported: false,
        })
        const devHeaderWrapper = await mountHeader()
        expect(devHeaderWrapper.find('.operator-select').exists()).toBe(true)
    })
})
