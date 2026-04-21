import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { setRuntimeConfigOverrides } from '@/app/config'
import WorkerContextsPage from '@/pages/resources/worker-contexts/WorkerContextsPage.vue'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('WorkerContextsPage', () => {
    it('loads worker contexts from the real API mode', async () => {
        setRuntimeConfigOverrides({ useMockApi: false })
        vi.stubGlobal(
            'fetch',
            vi.fn((input: string) => {
                if (input.includes('/worker-contexts')) {
                    return Promise.resolve(
                        jsonResponse({
                            code: 0,
                            msg: 'ok',
                            data: {
                                items: [
                                    {
                                        workerContextId: 'ctx-us-01',
                                        workerId: 'worker-us-01',
                                        status: 'OCCUPIED',
                                        channel: 'telegram',
                                        attributes: { account: 'ops-us-a' },
                                        lastBindTaskId: 'task-001',
                                        lastUsedTime: '2026-04-21 09:44:00',
                                        updateTime: '2026-04-21 09:44:00',
                                    },
                                ],
                                total: 1,
                            },
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
                                    locked: true,
                                    updateTime: '2026-04-21 09:45:00',
                                },
                            ],
                            total: 1,
                        },
                    }),
                )
            }),
        )

        const wrapper = mount(WorkerContextsPage, {
            global: {
                plugins: [ElementPlus],
            },
        })

        await flushPromises()

        expect(wrapper.text()).toContain('ctx-us-01')
        expect(wrapper.text()).toContain('worker-us-01')
        expect(wrapper.text()).toContain('OCCUPIED')
        expect(wrapper.text()).toContain('ONLINE')
    })
})
