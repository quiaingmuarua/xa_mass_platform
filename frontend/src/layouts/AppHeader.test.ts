import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import type { RouteRecordRaw } from 'vue-router'
import { createMemoryHistory, createRouter } from 'vue-router'
import { resetRuntimeConfigOverrides, setRuntimeConfigOverrides } from '@/app/config'
import { setBackendAuthConfig } from '@/auth/backend-auth'
import { mockAdminUser } from '@/auth/mock-user'
import { resetMockAuth, setMockCurrentUser } from '@/auth/use-auth'
import AppHeader from '@/layouts/AppHeader.vue'
import { appRoutes } from '@/router/routes'

async function mountHeader(path = '/', sidebarCollapsed = false) {
    const router = createRouter({
        history: createMemoryHistory(),
        routes: appRoutes as unknown as RouteRecordRaw[],
    })
    await router.push(path)
    await router.isReady()
    const wrapper = mount(AppHeader, {
        props: {
            sidebarCollapsed,
        },
        attachTo: document.body,
        global: {
            plugins: [router, ElementPlus],
        },
    })
    return { wrapper, router }
}

describe('AppHeader', () => {
    afterEach(() => {
        resetRuntimeConfigOverrides()
        resetMockAuth()
        document.body.innerHTML = ''
    })

    it('shows the dev-header operator selector only when the backend supports it', async () => {
        setRuntimeConfigOverrides({
            useMockApi: false,
            useMockAuth: false,
        })
        setMockCurrentUser(mockAdminUser)
        setBackendAuthConfig({
            authMode: 'session',
            operatorHeaderSupported: false,
            sessionCookieSupported: true,
            csrfHeaderName: 'X-Mass-Csrf-Token',
        })

        const { wrapper: sessionWrapper } = await mountHeader()
        await sessionWrapper.get('.user-trigger').trigger('click')
        await flushPromises()
        expect(sessionWrapper.find('.operator-select').exists()).toBe(false)
        expect(document.body.querySelector('.operator-select')).toBeNull()

        setBackendAuthConfig({
            authMode: 'dev-header',
            operatorHeaderSupported: true,
            sessionCookieSupported: false,
        })
        const { wrapper: devHeaderWrapper } = await mountHeader()
        await devHeaderWrapper.get('.user-trigger').trigger('click')
        await flushPromises()
        expect(document.body.querySelector('.operator-select')).not.toBeNull()
    })

    it('shows the operator selector in mock auth mode', async () => {
        setRuntimeConfigOverrides({
            useMockAuth: true,
        })
        setMockCurrentUser(mockAdminUser)

        const { wrapper } = await mountHeader()
        await wrapper.get('.user-trigger').trigger('click')
        await flushPromises()

        expect(document.body.querySelector('.operator-select')).not.toBeNull()
    })

    it('renders route breadcrumb and emits sidebar toggle', async () => {
        setMockCurrentUser(mockAdminUser)

        const { wrapper } = await mountHeader('/tasks')

        expect(wrapper.text()).toContain('Tasks')
        await wrapper.get('.header-icon-button').trigger('click')
        expect(wrapper.emitted('toggle-sidebar')).toHaveLength(1)
    })

    it('logs out through the existing auth flow and routes to login', async () => {
        setRuntimeConfigOverrides({
            useMockAuth: true,
        })
        setMockCurrentUser(mockAdminUser)

        const { wrapper, router } = await mountHeader()
        const setupState = (
            wrapper.vm.$ as unknown as {
                setupState: {
                    handleLogout: () => Promise<void>
                }
            }
        ).setupState

        await setupState.handleLogout()
        await flushPromises()

        expect(router.currentRoute.value.path).toBe('/login')
    })
})
