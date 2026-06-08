import ElementPlus from 'element-plus'
import {mount} from '@vue/test-utils'
import type {RouteRecordRaw} from 'vue-router'
import {createMemoryHistory, createRouter} from 'vue-router'
import {mockAdminUser} from '@/auth/mock-user'
import {setMockCurrentUser} from '@/auth/use-auth'
import AppShell from '@/layouts/AppShell.vue'
import {appRoutes} from '@/router/routes'

describe('AppShell', () => {
    afterEach(() => {
        window.sessionStorage.clear()
    })

    it('renders the sidebar, header, and routed content', async () => {
        setMockCurrentUser(mockAdminUser)

        const router = createRouter({
            history: createMemoryHistory(),
            routes: appRoutes as unknown as RouteRecordRaw[],
        })

        await router.push('/')
        await router.isReady()

        const wrapper = mount(AppShell, {
            global: {
                plugins: [router, ElementPlus],
            },
        })

        expect(wrapper.text()).toContain('Mass Console')
        expect(wrapper.text()).toContain('Overview')
    })

    it('toggles the operator sidebar collapsed state from the navbar', async () => {
        setMockCurrentUser(mockAdminUser)

        const router = createRouter({
            history: createMemoryHistory(),
            routes: appRoutes as unknown as RouteRecordRaw[],
        })

        await router.push('/')
        await router.isReady()

        const wrapper = mount(AppShell, {
            global: {
                plugins: [router, ElementPlus],
            },
        })

        expect(wrapper.get('.sidebar').classes()).not.toContain(
            'sidebar--collapsed',
        )

        await wrapper.get('.header-icon-button').trigger('click')

        expect(wrapper.get('.sidebar').classes()).toContain(
            'sidebar--collapsed',
        )
    })

    it('does not wrap API-key viewer with the operator sidebar', async () => {
        const router = createRouter({
            history: createMemoryHistory(),
            routes: appRoutes as unknown as RouteRecordRaw[],
        })

        await router.push('/api-key-viewer')
        await router.isReady()

        const wrapper = mount(AppShell, {
            global: {
                plugins: [router, ElementPlus],
            },
        })

        expect(wrapper.text()).toContain('API Key Viewer')
        expect(wrapper.text()).not.toContain('Mass Console')
        expect(wrapper.text()).not.toContain('Ops Admin')
    })
})
