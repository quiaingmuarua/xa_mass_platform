import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { setRuntimeConfigOverrides } from '@/app/config'
import { mockAdminUser, mockViewerUser } from '@/auth/mock-user'
import { permissionDirective } from '@/auth/permission-directive'
import { setMockCurrentUser } from '@/auth/use-auth'
import WorkersPage from '@/pages/resources/workers/WorkersPage.vue'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

function stubWorkersApi(): void {
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
                                attributes: { region: 'us' },
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
}

describe('WorkersPage', () => {
    it('loads workers from the real API mode and shows edit actions for editors', async () => {
        setRuntimeConfigOverrides({ useMockApi: false })
        setMockCurrentUser(mockAdminUser)
        stubWorkersApi()

        const router = createRouter({
            history: createMemoryHistory(),
            routes: [{ path: '/', component: WorkersPage }],
        })

        await router.push('/')
        await router.isReady()

        const wrapper = mount(WorkersPage, {
            global: {
                plugins: [router, ElementPlus],
                directives: {
                    permission: permissionDirective,
                },
            },
        })

        await flushPromises()

        expect(wrapper.text()).toContain('worker-us-01')
        expect(wrapper.text()).toContain('ONLINE')
        expect(wrapper.text()).toContain('Edit projects')
    })

    it('hides edit actions for read-only users', async () => {
        setRuntimeConfigOverrides({ useMockApi: false })
        setMockCurrentUser(mockViewerUser)
        stubWorkersApi()

        const router = createRouter({
            history: createMemoryHistory(),
            routes: [{ path: '/', component: WorkersPage }],
        })

        await router.push('/')
        await router.isReady()

        const wrapper = mount(WorkersPage, {
            global: {
                plugins: [router, ElementPlus],
                directives: {
                    permission: permissionDirective,
                },
            },
        })

        await flushPromises()

        expect(wrapper.text()).toContain('worker-us-01')
        expect(wrapper.text()).not.toContain('Edit projects')
    })
})
