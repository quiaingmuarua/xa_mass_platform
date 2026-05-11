import ElementPlus from 'element-plus'
import {flushPromises, mount} from '@vue/test-utils'
import {createMemoryHistory, createRouter} from 'vue-router'
import {mockAdminUser} from '@/auth/mock-user'
import {resetMockAuth, setMockCurrentUser} from '@/auth/use-auth'
import {resetRuntimeConfigOverrides, setRuntimeConfigOverrides} from '@/app/config'
import ProjectDetailPage from '@/pages/resources/projects/ProjectDetailPage.vue'

async function waitForMockData(): Promise<void> {
    await flushPromises()
    await new Promise((resolve) => window.setTimeout(resolve, 120))
    await flushPromises()
}

describe('ProjectDetailPage', () => {
    afterEach(() => {
        resetRuntimeConfigOverrides()
        resetMockAuth()
    })

    it('loads a project-owned view and starts a task draft with project scope', async () => {
        setRuntimeConfigOverrides({ useMockApi: true })
        setMockCurrentUser(mockAdminUser)

        const router = createRouter({
            history: createMemoryHistory(),
            routes: [
                {
                    path: '/resources/projects/:projectCode',
                    component: ProjectDetailPage,
                },
                {
                    path: '/tasks',
                    name: 'tasks',
                    component: { template: '<div>tasks page</div>' },
                },
                {
                    path: '/tasks/:taskId',
                    name: 'task-detail',
                    component: { template: '<div>task detail</div>' },
                },
            ],
        })

        await router.push('/resources/projects/demoApp')
        await router.isReady()

        const wrapper = mount(ProjectDetailPage, {
            global: {
                plugins: [router, ElementPlus],
            },
        })

        await waitForMockData()

        expect(wrapper.text()).toContain('Demo App (demoApp)')
        expect(wrapper.text()).toContain('Project summary')
        expect(wrapper.text()).toContain('Authorized events')
        expect(wrapper.text()).toContain('Scoped principals')
        expect(wrapper.text()).toContain('Worker coverage')
        expect(wrapper.text()).toContain('Project tasks')
        expect(wrapper.text()).toContain('Warm worker pool')
        expect(wrapper.text()).not.toContain('Daily worker session refresh')

        const startDraftButton = wrapper
            .findAll('button')
            .find((button) => button.text().includes('Start draft'))

        expect(startDraftButton).toBeDefined()

        await startDraftButton!.trigger('click')
        await flushPromises()

        expect(router.currentRoute.value.name).toBe('tasks')
        expect(router.currentRoute.value.query.create).toBe('1')
        expect(router.currentRoute.value.query.project).toBe('demoApp')
        expect(router.currentRoute.value.query.eventCode).toBe('demo.dispatch')
    })
})
