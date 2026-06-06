import ElementPlus from 'element-plus'
import {flushPromises, mount} from '@vue/test-utils'
import {createMemoryHistory, createRouter} from 'vue-router'
import {resetRuntimeConfigOverrides, setRuntimeConfigOverrides} from '@/app/config'
import RuntimeDiscoveryPage from '@/pages/runtime/RuntimeDiscoveryPage.vue'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

function jsonResponseWithStatus(body: unknown, status: number): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

function discoveryFetch(apiKeyResponse: Response): (input: string) => Promise<Response> {
    return (input: string) => {
        if (input.includes('/api/v1/api-keys:current')) {
            return Promise.resolve(apiKeyResponse)
        }
        if (input.includes('/api/v1/catalog/event-capabilities')) {
            return Promise.resolve(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: [
                        {
                            eventCode: 'probe.url.dns',
                            eventName: 'URL DNS Inspection',
                            enabled: true,
                            priorityClass: 'STANDARD',
                            responseMode: 'FINAL_RESULT',
                            targetScope: 'WORKER',
                            invocationModel: 'TASK_BACKED',
                            projectCodes: ['publicProbe'],
                            workerIds: ['dns-url-inspector-poll-001', 'dns-url-inspector-ws-001'],
                            onlineWorkerIds: ['dns-url-inspector-poll-001', 'dns-url-inspector-ws-001'],
                            hasDirectRuntimeHandler: false,
                            hasOnlineWorkerCoverage: true,
                            ready: true,
                        },
                        {
                            eventCode: 'probe.phone.metadata',
                            eventName: 'Phone Metadata Probe',
                            enabled: true,
                            priorityClass: 'STANDARD',
                            responseMode: 'FINAL_RESULT',
                            targetScope: 'WORKER',
                            invocationModel: 'TASK_BACKED',
                            projectCodes: ['deviceProbe'],
                            workerIds: ['phone-device-probe-ws-sg-001'],
                            onlineWorkerIds: [],
                            hasDirectRuntimeHandler: false,
                            hasOnlineWorkerCoverage: false,
                            ready: false,
                        },
                        {
                            eventCode: 'tool.country.capital.lookup',
                            eventName: 'Tool Country Capital Lookup',
                            enabled: true,
                            priorityClass: 'STANDARD',
                            responseMode: 'FINAL_RESULT',
                            targetScope: 'WORKER',
                            invocationModel: 'DIRECT_RUNTIME',
                            projectCodes: [],
                            workerIds: [],
                            onlineWorkerIds: [],
                            hasDirectRuntimeHandler: true,
                            hasOnlineWorkerCoverage: false,
                            ready: true,
                        },
                    ],
                }),
            )
        }
        if (input.includes('/api/v1/projects')) {
            return Promise.resolve(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: [
                        {
                            code: 'publicProbe',
                            name: 'Public Probe',
                            description: 'Public probe project.',
                            enabled: true,
                            eventCodes: ['probe.url.dns'],
                        },
                        {
                            code: 'deviceProbe',
                            name: 'Device Probe',
                            description: 'Device probe project.',
                            enabled: true,
                            eventCodes: ['probe.phone.metadata'],
                        },
                    ],
                }),
            )
        }
        if (input.includes('/api/v1/catalog/events')) {
            return Promise.resolve(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: [
                        {
                            code: 'probe.url.dns',
                            name: 'URL DNS Inspection',
                            description: 'Resolve URL domains.',
                            payloadTypes: ['JSON'],
                            taskModes: ['SINGLE_RUN'],
                            enabled: true,
                            priorityClass: 'STANDARD',
                            responseMode: 'FINAL_RESULT',
                            targetScope: 'WORKER',
                        },
                        {
                            code: 'probe.phone.metadata',
                            name: 'Phone Metadata Probe',
                            description: 'Validate phone metadata.',
                            payloadTypes: ['JSON'],
                            taskModes: ['SINGLE_RUN'],
                            enabled: true,
                            priorityClass: 'STANDARD',
                            responseMode: 'FINAL_RESULT',
                            targetScope: 'WORKER',
                        },
                        {
                            code: 'tool.country.capital.lookup',
                            name: 'Tool Country Capital Lookup',
                            description: 'Resolve a country code to a capital city.',
                            payloadTypes: ['JSON'],
                            taskModes: [],
                            enabled: true,
                            priorityClass: 'STANDARD',
                            responseMode: 'FINAL_RESULT',
                            targetScope: 'WORKER',
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
                            workerId: 'dns-url-inspector-poll-001',
                            status: 'ONLINE',
                            transportReachability: 'ONLINE',
                            transportOnline: true,
                            workerGroupId: 'dns-url-inspector',
                            adapterNodeId: 'control-console-polling',
                            transportHint: 'polling',
                            agentVersion: '1.4.0',
                            supportedProjects: ['publicProbe'],
                            supportedEventCodes: ['probe.url.dns'],
                            attributes: {},
                            lastHeartbeat: '2026-04-21 09:45:00',
                            locked: false,
                            updateTime: '2026-04-21 09:45:00',
                        },
                        {
                            workerId: 'dns-url-inspector-ws-001',
                            status: 'ONLINE',
                            transportReachability: 'ONLINE',
                            transportOnline: true,
                            workerGroupId: 'dns-url-inspector',
                            adapterNodeId: 'control-console-websocket',
                            transportHint: 'realtime',
                            agentVersion: '1.4.1',
                            supportedProjects: [],
                            supportedEventCodes: ['probe.url.dns'],
                            attributes: {},
                            lastHeartbeat: '2026-04-21 09:47:00',
                            locked: false,
                            updateTime: '2026-04-21 09:47:00',
                        },
                        {
                            workerId: 'phone-device-probe-ws-sg-001',
                            status: 'ONLINE',
                            transportReachability: 'OFFLINE',
                            transportOnline: false,
                            workerGroupId: 'phone-device-probe',
                            agentVersion: '1.3.7',
                            supportedProjects: ['deviceProbe'],
                            supportedEventCodes: ['probe.phone.metadata'],
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
    }
}

async function mountRuntimeDiscoveryPage() {
    const router = createRouter({
        history: createMemoryHistory(),
        routes: [
            { path: '/', component: RuntimeDiscoveryPage },
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

    const wrapper = mount(RuntimeDiscoveryPage, {
        global: {
            plugins: [router, ElementPlus],
        },
    })

    await flushPromises()
    return { wrapper, router }
}

describe('RuntimeDiscoveryPage', () => {
    afterEach(() => {
        resetRuntimeConfigOverrides()
        vi.unstubAllGlobals()
    })

    it('combines the catalog and live workers into a discovery view', async () => {
        setRuntimeConfigOverrides({ useMockApi: false })
        vi.stubGlobal(
            'fetch',
            vi.fn(
                discoveryFetch(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: {
                            principalId: 'public-probe-runner',
                            userId: 'public-probe-runner',
                            projectScope: 'publicProbe',
                            permissions: ['task:create', 'catalog:view'],
                            projectScopes: ['publicProbe'],
                            eventScopes: ['probe.url.dns'],
                            attributes: {
                                transport: 'polling',
                            },
                        },
                    }),
                ),
            ),
        )

        const { wrapper, router } = await mountRuntimeDiscoveryPage()

        expect(wrapper.text()).toContain('Control-plane Discovery')
        expect(wrapper.text()).toContain(
            'Project directory, event capability inventory, and live worker presence in one control-plane view',
        )
        expect(wrapper.text()).toContain('supportedEventCodes')
        expect(wrapper.text()).toContain('Public Probe')
        expect(wrapper.text()).toContain('dns-url-inspector-poll-001')
        expect(wrapper.text()).toContain('dns-url-inspector-ws-001')
        expect(wrapper.text()).toContain('probe.url.dns')
        expect(wrapper.text()).toContain('STANDARD')
        expect(wrapper.text()).toContain('FINAL_RESULT')
        expect(wrapper.text()).toContain('WORKER')
        expect(wrapper.text()).toContain('1 / 2')
        expect(wrapper.text()).not.toContain('phone-device-probe-ws-sg-001')
        expect(wrapper.text()).toContain('Start event draft')
        expect(wrapper.text()).toContain('API-key credential access')
        expect(wrapper.text()).toContain('Credential resolved')
        expect(wrapper.text()).toContain('public-probe-runner')
        expect(wrapper.text()).toContain('task:create')
        expect(wrapper.text()).toContain('probe.url.dns')
        expect(wrapper.text()).toContain('POST /api/v1/tasks')

        const inspectButtons = wrapper
            .findAll('button')
            .filter((button) => button.text().trim() === 'Inspect')
        expect(inspectButtons.length).toBeGreaterThan(1)

        await inspectButtons[1]!.trigger('click')
        await flushPromises()

        expect(wrapper.text()).toContain(
            'Scoped by selected project events plus optional project hints',
        )
        expect(wrapper.text()).toContain('dns-url-inspector-ws-001')
        expect(wrapper.text()).toContain('dns-open-meteo')
        expect(wrapper.text()).toContain('DNS_NXDOMAIN')

        const startDraftButton = wrapper
            .findAll('button')
            .find((button) => button.text().includes('Start event draft'))
        expect(startDraftButton).toBeDefined()

        await startDraftButton!.trigger('click')
        await flushPromises()

        expect(router.currentRoute.value.name).toBe('tasks')
        expect(router.currentRoute.value.query.create).toBe('1')
        expect(router.currentRoute.value.query.project).toBe('publicProbe')
        expect(router.currentRoute.value.query.eventCode).toBe('probe.url.dns')
        expect(router.currentRoute.value.query.taskName).toBeUndefined()
    })

    it('renders API-key credential unauthorized state without treating it as console auth', async () => {
        setRuntimeConfigOverrides({ useMockApi: false })
        vi.stubGlobal(
            'fetch',
            vi.fn(
                discoveryFetch(
                    jsonResponseWithStatus(
                        {
                            code: 401,
                            msg: 'Invalid or missing API-key credential',
                            data: null,
                        },
                        401,
                    ),
                ),
            ),
        )

        const { wrapper } = await mountRuntimeDiscoveryPage()

        expect(wrapper.text()).toContain('No API-key credential in this browser session')
        expect(wrapper.text()).toContain('It is not the control-console login state')
    })

    it('renders API-key credential unavailable state when introspection is not exposed', async () => {
        setRuntimeConfigOverrides({ useMockApi: false })
        vi.stubGlobal(
            'fetch',
            vi.fn(
                discoveryFetch(
                    jsonResponseWithStatus(
                        {
                            code: 404,
                            msg: 'Not found',
                            data: null,
                        },
                        404,
                    ),
                ),
            ),
        )

        const { wrapper } = await mountRuntimeDiscoveryPage()

        expect(wrapper.text()).toContain('Endpoint unavailable or mock mode')
    })

    it('treats direct runtime events as discovery-only and does not offer task drafting', async () => {
        setRuntimeConfigOverrides({ useMockApi: false })
        vi.stubGlobal(
            'fetch',
            vi.fn((input: string) => {
                if (input.includes('/api/v1/api-keys:current')) {
                    return Promise.resolve(
                        jsonResponseWithStatus(
                            {
                                code: 404,
                                msg: 'Not found',
                                data: null,
                            },
                            404,
                        ),
                    )
                }
                if (input.includes('/api/v1/projects')) {
                    return Promise.resolve(
                        jsonResponse({
                            code: 0,
                            msg: 'ok',
                            data: [],
                        }),
                    )
                }
                if (input.includes('/api/v1/catalog/event-capabilities')) {
                    return Promise.resolve(
                        jsonResponse({
                            code: 0,
                            msg: 'ok',
                            data: [
                                {
                                    eventCode: 'tool.phone.country.detect',
                                    eventName: 'Tool Phone Country Detect',
                                    enabled: true,
                                    priorityClass: 'STANDARD',
                                    responseMode: 'FINAL_RESULT',
                                    targetScope: 'WORKER',
                                    invocationModel: 'DIRECT_RUNTIME',
                                    projectCodes: [],
                                    workerIds: [],
                                    onlineWorkerIds: [],
                                    hasDirectRuntimeHandler: true,
                                    hasOnlineWorkerCoverage: false,
                                    ready: true,
                                },
                            ],
                        }),
                    )
                }
                if (input.includes('/api/v1/catalog/events')) {
                    return Promise.resolve(
                        jsonResponse({
                            code: 0,
                            msg: 'ok',
                            data: [
                                {
                                    code: 'tool.phone.country.detect',
                                    name: 'Tool Phone Country Detect',
                                    description: 'Detect a phone number country by dial code.',
                                    payloadTypes: ['JSON'],
                                    taskModes: [],
                                    enabled: true,
                                    priorityClass: 'STANDARD',
                                    responseMode: 'FINAL_RESULT',
                                    targetScope: 'WORKER',
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
                            items: [],
                            total: 0,
                        },
                    }),
                )
            }),
        )

        const { wrapper, router } = await mountRuntimeDiscoveryPage()

        expect(wrapper.text()).toContain('Direct runtime event')

        const startDraftButton = wrapper
            .findAll('button')
            .find((button) => button.text().includes('Start event draft'))
        expect(startDraftButton).toBeDefined()
        expect(startDraftButton!.attributes('disabled')).toBeDefined()

        await startDraftButton!.trigger('click')
        await flushPromises()

        expect(router.currentRoute.value.path).toBe('/')
    })
})
