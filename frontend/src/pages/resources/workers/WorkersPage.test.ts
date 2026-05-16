import ElementPlus from 'element-plus'
import {flushPromises, mount} from '@vue/test-utils'
import {createMemoryHistory, createRouter} from 'vue-router'
import {setRuntimeConfigOverrides} from '@/app/config'
import {mockAdminUser, mockViewerUser} from '@/auth/mock-user'
import {permissionDirective} from '@/auth/permission-directive'
import {setMockCurrentUser} from '@/auth/use-auth'
import WorkersPage from '@/pages/resources/workers/WorkersPage.vue'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

function stubWorkersApi() {
    const fetchMock = vi.fn((input: string) => {
        if (!input.includes('/api/v1/runtime/workers')) {
            return Promise.resolve(
                jsonResponse({
                    code: 404,
                    msg: 'unexpected worker API request',
                    data: null,
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
                            supportedEventCodes: ['demo.dispatch'],
                            eventBindings: [
                                {
                                    eventCode: 'demo.dispatch',
                                    filterExpression: '',
                                    concurrency: 1,
                                },
                            ],
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
    })
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
}

describe('WorkersPage', () => {
    it('loads workers from the real API mode and keeps the page read-only', async () => {
        setRuntimeConfigOverrides({ useMockApi: false })
        setMockCurrentUser(mockAdminUser)
        const fetchMock = stubWorkersApi()

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
        expect(wrapper.text()).toContain('demo.dispatch')
        expect(wrapper.text()).toContain('Open debug view')
        expect(wrapper.text()).not.toContain('Edit projects')
        expect(
            fetchMock.mock.calls.some(([input]) =>
                String(input).includes('/worker-contexts'),
            ),
        ).toBe(false)
    })

    it('hides edit actions for read-only users', async () => {
        setRuntimeConfigOverrides({ useMockApi: false })
        setMockCurrentUser(mockViewerUser)
        const fetchMock = stubWorkersApi()

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
        expect(wrapper.text()).toContain('demo.dispatch')
        expect(wrapper.text()).not.toContain('Edit projects')
        expect(
            fetchMock.mock.calls.some(([input]) =>
                String(input).includes('/worker-contexts'),
            ),
        ).toBe(false)
    })
})
