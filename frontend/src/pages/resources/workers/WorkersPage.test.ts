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
                            workerId: 'phone-device-probe-ws-sg-001',
                            runtimeStatus: 'ONLINE',
                            reachability: 'ONLINE',
                            reachable: true,
                            workerGroupId: 'phone-device-probe',
                            transportHint: 'realtime',
                            agentVersion: '1.4.0',
                            supportedProjects: ['deviceProbe'],
                            supportedEventCodes: ['probe.phone.metadata'],
                            eventBindings: [
                                {
                                    eventCode: 'probe.phone.metadata',
                                    filterExpression: '',
                                    concurrency: 1,
                                },
                            ],
                            attributes: {
                                country: 'SG',
                                fingerprintProfile: 'fp-android-sg-a',
                            },
                            locked: true,
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

        expect(wrapper.text()).toContain('phone-device-probe-ws-sg-001')
        expect(wrapper.text()).toContain('ONLINE')
        expect(wrapper.text()).toContain('probe.phone.metadata')
        expect(wrapper.text()).toContain('fp-android-sg-a')
        expect(wrapper.text()).toContain('Open debug view')
        expect(wrapper.text()).not.toContain('Edit projects')
        expect(fetchMock).toHaveBeenCalledTimes(1)
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

        expect(wrapper.text()).toContain('phone-device-probe-ws-sg-001')
        expect(wrapper.text()).toContain('probe.phone.metadata')
        expect(wrapper.text()).not.toContain('Edit projects')
        expect(fetchMock).toHaveBeenCalledTimes(1)
    })
})
