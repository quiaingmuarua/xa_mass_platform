import ElementPlus from 'element-plus'
import {flushPromises, mount} from '@vue/test-utils'
import {setRuntimeConfigOverrides} from '@/app/config'
import {mockAdminUser} from '@/auth/mock-user'
import {setMockCurrentUser} from '@/auth/use-auth'
import DashboardPage from '@/pages/dashboard/DashboardPage.vue'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('DashboardPage', () => {
    it('loads overview data from real API mode', async () => {
        setRuntimeConfigOverrides({
            useMockApi: false,
            useMockAuth: false,
        })
        setMockCurrentUser(mockAdminUser)

        vi.stubGlobal(
            'fetch',
            vi.fn((input: string) => {
                if (input.includes('/status/api/tasks')) {
                    return Promise.resolve(
                        jsonResponse({
                            code: 0,
                            msg: 'ok',
                            data: {
                                items: [
                                    {
                                        id: 'task-001',
                                        taskName: 'Warm worker pool',
                                        project: 'demoApp',
                                        status: 'RUNNING',
                                        terminalReason: null,
                                        successCount: 6,
                                        eligibleCount: 10,
                                        batchSize: 2,
                                        updatedAt: '2026-04-21 09:30:00',
                                    },
                                ],
                                total: 1,
                            },
                        }),
                    )
                }
                if (input.includes('/status/api/worker-contexts')) {
                    return Promise.resolve(
                        jsonResponse({
                            code: 0,
                            msg: 'ok',
                            data: {
                                items: [
                                    {
                                        workerContextId: 'ctx-us-01',
                                        workerId: 'worker-us-01',
                                        project: 'demoApp',
                                        status: 'OCCUPIED',
                                        routingTags: ['primary'],
                                        attributes: {},
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
                if (input.includes('/status/api/workers')) {
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
                                        supportedEventCodes: ['demo.dispatch'],
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
                }

                return Promise.resolve(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: {
                            items: [
                                {
                                    ruleId: 'rule-001',
                                    name: 'Prefer online workers',
                                    type: 'QL_EXPRESS',
                                    content: "worker.status == 'ONLINE'",
                                    description: 'Only online workers.',
                                    enabled: true,
                                    priority: 10,
                                },
                            ],
                            total: 1,
                        },
                    }),
                )
            }),
        )

        const wrapper = mount(DashboardPage, {
            global: {
                plugins: [ElementPlus],
            },
        })

        await flushPromises()

        expect(wrapper.text()).toContain('Backend')
        expect(wrapper.text()).toContain('Warm worker pool')
        expect(wrapper.text()).toContain('Running tasks')
        expect(wrapper.text()).toContain('Online workers')
    })
})
