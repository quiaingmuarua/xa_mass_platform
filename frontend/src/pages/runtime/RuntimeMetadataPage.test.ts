import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { resetRuntimeConfigOverrides, setRuntimeConfigOverrides } from '@/app/config'
import RuntimeMetadataPage from '@/pages/runtime/RuntimeMetadataPage.vue'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('RuntimeMetadataPage', () => {
    afterEach(() => {
        resetRuntimeConfigOverrides()
        vi.unstubAllGlobals()
    })

    it('combines SDK metadata and live workers into a discovery view', async () => {
        setRuntimeConfigOverrides({ useMockApi: false })
        vi.stubGlobal(
            'fetch',
            vi.fn((input: string) => {
                if (input.includes('/sdk/meta/projects')) {
                    return Promise.resolve(
                        jsonResponse({
                            code: 0,
                            msg: 'ok',
                            data: [
                                {
                                    code: 'demoApp',
                                    name: 'Demo App',
                                    description: 'Demo project.',
                                    enabled: true,
                                    eventCodes: ['demo.dispatch.run'],
                                },
                                {
                                    code: 'crawlerApp',
                                    name: 'Crawler App',
                                    description: 'Crawler project.',
                                    enabled: true,
                                    eventCodes: ['worker.fetch-page'],
                                },
                            ],
                        }),
                    )
                }
                if (input.includes('/sdk/meta/events')) {
                    return Promise.resolve(
                        jsonResponse({
                            code: 0,
                            msg: 'ok',
                            data: [
                                {
                                    code: 'demo.dispatch.run',
                                    name: 'Run demo dispatch',
                                    description: 'Run a demo dispatch.',
                                    payloadTypes: ['JSON'],
                                    taskModes: ['SINGLE_RUN'],
                                    enabled: true,
                                },
                                {
                                    code: 'worker.fetch-page',
                                    name: 'Fetch crawler page',
                                    description: 'Fetch through a crawler worker.',
                                    payloadTypes: ['JSON'],
                                    taskModes: ['SINGLE_RUN'],
                                    enabled: true,
                                },
                            ],
                        }),
                    )
                }

                return Promise.resolve(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: {
                            items: [
                                {
                                    workerId: 'worker-us-01',
                                    status: 'ONLINE',
                                    workerGroupId: 'us-routing',
                                    agentVersion: '1.4.0',
                                    supportedProjects: ['demoApp'],
                                    attributes: {},
                                    lastHeartbeat: '2026-04-21 09:45:00',
                                    locked: false,
                                    updateTime: '2026-04-21 09:45:00',
                                },
                                {
                                    workerId: 'worker-sg-01',
                                    status: 'OFFLINE',
                                    workerGroupId: 'sg-routing',
                                    agentVersion: '1.3.7',
                                    supportedProjects: ['crawlerApp'],
                                    attributes: {},
                                    lastHeartbeat: '2026-04-21 08:45:00',
                                    locked: false,
                                    updateTime: '2026-04-21 08:45:00',
                                },
                            ],
                            total: 2,
                        },
                    }),
                )
            }),
        )

        const router = createRouter({
            history: createMemoryHistory(),
            routes: [
                { path: '/', component: RuntimeMetadataPage },
                {
                    path: '/tasks',
                    name: 'tasks',
                    component: { template: '<div>tasks page</div>' },
                },
                {
                    path: '/resources/workers/:workerId',
                    name: 'worker-detail',
                    component: { template: '<div />' },
                },
            ],
        })
        await router.push('/')
        await router.isReady()

        const wrapper = mount(RuntimeMetadataPage, {
            global: {
                plugins: [router, ElementPlus],
            },
        })

        await flushPromises()

        expect(wrapper.text()).toContain('Metadata & Discovery')
        expect(wrapper.text()).toContain('Demo App')
        expect(wrapper.text()).toContain('worker-us-01')
        expect(wrapper.text()).toContain('demo.dispatch.run')
        expect(wrapper.text()).toContain('1 / 2')
        expect(wrapper.text()).not.toContain('worker-sg-01')
        expect(wrapper.text()).toContain('Start event draft')
        expect(wrapper.text()).toContain('hello from demo.dispatch.run')
        expect(wrapper.text()).toContain('"recipient":"alpha"')

        const startDraftButton = wrapper
            .findAll('button')
            .find((button) => button.text().includes('Start event draft'))
        expect(startDraftButton).toBeDefined()

        await startDraftButton!.trigger('click')
        await flushPromises()

        expect(router.currentRoute.value.name).toBe('tasks')
        expect(router.currentRoute.value.query.create).toBe('1')
        expect(router.currentRoute.value.query.project).toBe('demoApp')
        expect(router.currentRoute.value.query.eventCode).toBe(
            'demo.dispatch.run',
        )
        expect(router.currentRoute.value.query.taskName).toBeUndefined()
    })
})
