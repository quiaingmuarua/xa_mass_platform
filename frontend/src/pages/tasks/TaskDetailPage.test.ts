import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { mockAdminUser } from '@/auth/mock-user'
import { setMockCurrentUser } from '@/auth/use-auth'
import { permissionDirective } from '@/auth/permission-directive'
import TaskDetailPage from '@/pages/tasks/TaskDetailPage.vue'

describe('TaskDetailPage', () => {
    it('loads the task detail from the mock API', async () => {
        setMockCurrentUser(mockAdminUser)

        const router = createRouter({
            history: createMemoryHistory(),
            routes: [{ path: '/tasks/:taskId', component: TaskDetailPage }],
        })

        await router.push('/tasks/task-001')
        await router.isReady()

        const wrapper = mount(TaskDetailPage, {
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
        expect(wrapper.text()).toContain('BUSINESS_SUCCESS')
    })
})
