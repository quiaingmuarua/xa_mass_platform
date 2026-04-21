import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { mockAdminUser } from '@/auth/mock-user'
import { setMockCurrentUser } from '@/auth/use-auth'
import TasksListPage from '@/pages/tasks/TasksListPage.vue'
import { permissionDirective } from '@/auth/permission-directive'

describe('TasksListPage', () => {
    it('loads mock tasks and renders the list', async () => {
        setMockCurrentUser(mockAdminUser)

        const router = createRouter({
            history: createMemoryHistory(),
            routes: [{ path: '/', component: TasksListPage }],
        })

        await router.push('/')
        await router.isReady()

        const wrapper = mount(TasksListPage, {
            global: {
                plugins: [router, ElementPlus],
                directives: {
                    permission: permissionDirective,
                },
            },
        })

        await flushPromises()
        await new Promise((resolve) => window.setTimeout(resolve, 100))
        await flushPromises()

        expect(wrapper.text()).toContain('Warm worker pool for us-routing')
        expect(wrapper.text()).toContain('Review failed delivery backlog')
    })
})
