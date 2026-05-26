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

        const fetchMock = vi.fn((input: string) => {
                if (input.includes('/api/v1/tasks')) {
                    return Promise.resolve(
                        jsonResponse({
                            code: 0,
                            msg: 'ok',
                            data: {
                                items: [
                                    {
                                        id: 'task-001',
                                        taskName: 'Public provider reachability batch',
                                        project: 'publicProbe',
                                        status: 'NEW',
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
                if (input.includes('/api/v1/runtime/workers')) {
                    return Promise.resolve(
                        jsonResponse({
                            code: 0,
                            msg: 'ok',
                            data: {
                                items: [
                                    {
                                        workerId: 'public-probe-http-poll-use1-001',
                                        status: 'ONLINE',
                                        transportReachability: 'ONLINE',
                                        transportOnline: true,
                                        workerGroupId: 'public-probe-http',
                                        agentVersion: '1.4.0',
                                        supportedProjects: ['publicProbe'],
                                        supportedEventCodes: ['probe.weather.current'],
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
            })
        vi.stubGlobal('fetch', fetchMock)

        const wrapper = mount(DashboardPage, {
            global: {
                plugins: [ElementPlus],
            },
        })

        await flushPromises()

        expect(wrapper.text()).toContain('Backend')
        expect(wrapper.text()).toContain('Public provider reachability batch')
        expect(wrapper.text()).toContain('Running tasks')
        expect(wrapper.text()).toContain('Online workers')
        expect(wrapper.text()).toContain('Capabilities')
        expect(fetchMock).toHaveBeenCalledTimes(3)
    })
})
