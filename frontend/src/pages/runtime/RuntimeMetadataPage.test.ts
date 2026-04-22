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
                                    eventCodes: ['demo.message.send'],
                                },
                                {
                                    code: 'telegramApp',
                                    name: 'Telegram App',
                                    description: 'Telegram project.',
                                    enabled: true,
                                    eventCodes: ['telegram.message.send'],
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
                                    code: 'demo.message.send',
                                    name: 'Send demo message',
                                    description: 'Send a demo message.',
                                    payloadTypes: ['JSON'],
                                    taskModes: ['SINGLE_RUN'],
                                    enabled: true,
                                },
                                {
                                    code: 'telegram.message.send',
                                    name: 'Send Telegram message',
                                    description: 'Send through Telegram.',
                                    payloadTypes: ['TEXT'],
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
                                    supportedProjects: ['telegramApp'],
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
        expect(wrapper.text()).toContain('demo.message.send')
        expect(wrapper.text()).toContain('1 / 2')
        expect(wrapper.text()).not.toContain('worker-sg-01')
    })
})
