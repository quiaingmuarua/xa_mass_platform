import ElementPlus from 'element-plus'
import {flushPromises, mount} from '@vue/test-utils'
import {createMemoryHistory, createRouter} from 'vue-router'
import {mockAdminUser} from '@/auth/mock-user'
import {resetMockAuth, setMockCurrentUser} from '@/auth/use-auth'
import {resetRuntimeConfigOverrides, setRuntimeConfigOverrides} from '@/app/config'
import ProjectsPage from '@/pages/resources/projects/ProjectsPage.vue'

async function waitForMockData(): Promise<void> {
    await flushPromises()
    await new Promise((resolve) => window.setTimeout(resolve, 260))
    await flushPromises()
}

describe('ProjectsPage', () => {
    afterEach(() => {
        resetRuntimeConfigOverrides()
        resetMockAuth()
    })

    it('renders project-first control-plane rows and opens project detail', async () => {
        setRuntimeConfigOverrides({ useMockApi: true })
        setMockCurrentUser(mockAdminUser)

        const router = createRouter({
            history: createMemoryHistory(),
            routes: [
                { path: '/', component: ProjectsPage },
                {
                    path: '/resources/projects/:projectCode',
                    name: 'project-detail',
                    component: { template: '<div>project detail</div>' },
                },
            ],
        })

        await router.push('/')
        await router.isReady()

        const wrapper = mount(ProjectsPage, {
            global: {
                plugins: [router, ElementPlus],
            },
        })

        await waitForMockData()

        expect(wrapper.text()).toContain('Projects')
        expect(wrapper.text()).toContain('Public Probe')
        expect(wrapper.text()).toContain('Device Probe')
        expect(wrapper.text()).toContain('publicProbe')
        expect(wrapper.text()).toContain('deviceProbe')
        expect(wrapper.text()).toContain('WorkerGroups')
        expect(wrapper.text()).toContain('Online capacity')

        const openDetailButton = wrapper
            .findAll('button')
            .find((button) => button.text().includes('Open detail'))

        expect(openDetailButton).toBeDefined()

        await openDetailButton!.trigger('click')
        await flushPromises()

        expect(router.currentRoute.value.name).toBe('project-detail')
        expect(router.currentRoute.value.params.projectCode).toBe('dataQualityProbe')
    })
})
