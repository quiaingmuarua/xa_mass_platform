import ElementPlus from 'element-plus'
import {flushPromises, mount} from '@vue/test-utils'
import {createMemoryHistory, createRouter} from 'vue-router'
import {resetRuntimeConfigOverrides, setRuntimeConfigOverrides} from '@/app/config'
import RuntimeMetadataPage from '@/pages/runtime/RuntimeMetadataPage.vue'

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

function discoveryFetch(submitterResponse: Response): (input: string) => Promise<Response> {
    return (input: string) => {
        if (input.includes('/api/v1/submitters/me')) {
            return Promise.resolve(submitterResponse)
        }
        if (input.includes('/api/v1/meta/event-capabilities')) {
            return Promise.resolve(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: [
                        {
                            eventCode: 'demo.dispatch',
                            eventName: 'Demo dispatch',
                            enabled: true,
                            invocationModel: 'TASK_BACKED',
                            projectCodes: ['demoApp'],
                            workerIds: ['worker-us-01', 'worker-event-only'],
                            onlineWorkerIds: ['worker-us-01', 'worker-event-only'],
                            hasDirectRuntimeHandler: false,
                            hasOnlineWorkerCoverage: true,
                            ready: true,
                        },
                        {
                            eventCode: 'crawler.fetch-page',
                            eventName: 'Fetch crawler page',
                            enabled: true,
                            invocationModel: 'TASK_BACKED',
                            projectCodes: ['crawlerApp'],
                            workerIds: ['worker-sg-01'],
                            onlineWorkerIds: [],
                            hasDirectRuntimeHandler: false,
                            hasOnlineWorkerCoverage: false,
                            ready: false,
                        },
                        {
                            eventCode: 'tool.country.capital.lookup',
                            eventName: 'Tool Country Capital Lookup',
                            enabled: true,
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
                            code: 'demoApp',
                            name: 'Demo App',
                            description: 'Demo project.',
                            enabled: true,
                            eventCodes: ['demo.dispatch'],
                        },
                        {
                            code: 'crawlerApp',
                            name: 'Crawler App',
                            description: 'Crawler project.',
                            enabled: true,
                            eventCodes: ['crawler.fetch-page'],
                        },
                    ],
                }),
            )
        }
        if (input.includes('/api/v1/meta/events')) {
            return Promise.resolve(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: [
                        {
                            code: 'demo.dispatch',
                            name: 'Demo dispatch',
                            description: 'Run a demo dispatch.',
                            payloadTypes: ['JSON'],
                            taskModes: ['SINGLE_RUN'],
                            enabled: true,
                        },
                        {
                            code: 'crawler.fetch-page',
                            name: 'Fetch crawler page',
                            description: 'Fetch through a crawler worker.',
                            payloadTypes: ['JSON'],
                            taskModes: ['SINGLE_RUN'],
                            enabled: true,
                        },
                        {
                            code: 'tool.country.capital.lookup',
                            name: 'Tool Country Capital Lookup',
                            description: 'Resolve a country code to a capital city.',
                            payloadTypes: ['JSON'],
                            taskModes: [],
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
                            supportedEventCodes: ['demo.dispatch'],
                            attributes: {},
                            lastHeartbeat: '2026-04-21 09:45:00',
                            locked: false,
                            updateTime: '2026-04-21 09:45:00',
                        },
                        {
                            workerId: 'worker-event-only',
                            status: 'ONLINE',
                            workerGroupId: 'shared-pool',
                            agentVersion: '1.4.1',
                            supportedProjects: [],
                            supportedEventCodes: ['demo.dispatch'],
                            attributes: {},
                            lastHeartbeat: '2026-04-21 09:47:00',
                            locked: false,
                            updateTime: '2026-04-21 09:47:00',
                        },
                        {
                            workerId: 'worker-sg-01',
                            status: 'OFFLINE',
                            workerGroupId: 'sg-routing',
                            agentVersion: '1.3.7',
                            supportedProjects: ['crawlerApp'],
                            supportedEventCodes: ['crawler.fetch-page'],
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

async function mountRuntimeMetadataPage() {
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
    return { wrapper, router }
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
            vi.fn(
                discoveryFetch(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: {
                            principalId: 'crawler-agent',
                            userId: 'crawler-user',
                            projectScope: 'crawlerApp',
                            permissions: ['task:create', 'metadata:view'],
                            projectScopes: ['crawlerApp'],
                            eventScopes: ['crawler.fetch-page'],
                            attributes: {
                                transport: 'polling',
                            },
                        },
                    }),
                ),
            ),
        )

        const { wrapper, router } = await mountRuntimeMetadataPage()

        expect(wrapper.text()).toContain('Control-plane Discovery')
        expect(wrapper.text()).toContain(
            'Project directory, event capability inventory, and live worker presence in one control-plane view',
        )
        expect(wrapper.text()).toContain('supportedEventCodes')
        expect(wrapper.text()).toContain('Demo App')
        expect(wrapper.text()).toContain('worker-us-01')
        expect(wrapper.text()).toContain('worker-event-only')
        expect(wrapper.text()).toContain('demo.dispatch')
        expect(wrapper.text()).toContain('1 / 2')
        expect(wrapper.text()).not.toContain('worker-sg-01')
        expect(wrapper.text()).toContain('Start event draft')
        expect(wrapper.text()).toContain('SDK submitter access')
        expect(wrapper.text()).toContain('Credential resolved')
        expect(wrapper.text()).toContain('crawler-agent')
        expect(wrapper.text()).toContain('task:create')
        expect(wrapper.text()).toContain('crawler.fetch-page')
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
        expect(wrapper.text()).toContain('worker-event-only')
        expect(wrapper.text()).toContain('hello from demo.dispatch')
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
        expect(router.currentRoute.value.query.eventCode).toBe('demo.dispatch')
        expect(router.currentRoute.value.query.taskName).toBeUndefined()
    })

    it('renders SDK submitter unauthorized state without treating it as console auth', async () => {
        setRuntimeConfigOverrides({ useMockApi: false })
        vi.stubGlobal(
            'fetch',
            vi.fn(
                discoveryFetch(
                    jsonResponseWithStatus(
                        {
                            code: 401,
                            msg: 'Invalid or missing SDK credential',
                            data: null,
                        },
                        401,
                    ),
                ),
            ),
        )

        const { wrapper } = await mountRuntimeMetadataPage()

        expect(wrapper.text()).toContain('No SDK credential in this browser session')
        expect(wrapper.text()).toContain('It is not the control-console login state')
    })

    it('renders SDK submitter unavailable state when introspection is not exposed', async () => {
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

        const { wrapper } = await mountRuntimeMetadataPage()

        expect(wrapper.text()).toContain('Endpoint unavailable or mock mode')
    })

    it('treats direct runtime events as discovery-only and does not offer task drafting', async () => {
        setRuntimeConfigOverrides({ useMockApi: false })
        vi.stubGlobal(
            'fetch',
            vi.fn((input: string) => {
                if (input.includes('/api/v1/submitters/me')) {
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
                if (input.includes('/api/v1/meta/event-capabilities')) {
                    return Promise.resolve(
                        jsonResponse({
                            code: 0,
                            msg: 'ok',
                            data: [
                                {
                                    eventCode: 'tool.phone.country.detect',
                                    eventName: 'Tool Phone Country Detect',
                                    enabled: true,
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
                if (input.includes('/api/v1/meta/events')) {
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

        const { wrapper, router } = await mountRuntimeMetadataPage()

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
