import {resetRuntimeConfigOverrides, setRuntimeConfigOverrides} from '@/app/config'
import {
    listEventCapabilities,
    listEventDefinitions,
} from '@/api/catalog'
import {
    listEventCapabilitiesReal,
    listEventDefinitionsReal,
} from '@/api/catalog.real'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('catalog API facade', () => {
    afterEach(() => {
        resetRuntimeConfigOverrides()
        vi.unstubAllGlobals()
    })

    it('serves catalog event definitions from the mock adapter', async () => {
        setRuntimeConfigOverrides({ useMockApi: true })

        const events = await listEventDefinitions()
        const capabilities = await listEventCapabilities()

        expect(events.some((event) => event.code === 'demo.dispatch')).toBe(
            true,
        )
        expect(capabilities.some((item) => item.eventCode === 'tool.country.capital.lookup')).toBe(true)
    })
})

describe('catalog.real', () => {
    afterEach(() => {
        resetRuntimeConfigOverrides()
        vi.unstubAllGlobals()
    })

    it('calls backend catalog endpoints', async () => {
        setRuntimeConfigOverrides({ apiBaseUrl: '/backend' })
        const fetchMock = vi.fn((input: string) => {
            if (input.endsWith('/api/v1/catalog/event-capabilities')) {
                return Promise.resolve(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: [
                            {
                                eventCode: 'demo.dispatch',
                                eventName: 'Demo dispatch',
                                enabled: true,
                                priorityClass: 'STANDARD',
                                responseMode: 'FINAL_RESULT',
                                targetScope: 'WORKER',
                                invocationModel: 'TASK_BACKED',
                                projectCodes: ['demoApp'],
                                workerIds: ['worker-1'],
                                onlineWorkerIds: ['worker-1'],
                                hasDirectRuntimeHandler: false,
                                hasOnlineWorkerCoverage: true,
                                ready: true,
                            },
                        ],
                    }),
                )
            }
            if (input.endsWith('/api/v1/catalog/events')) {
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
                    data: [],
                }),
            )
        })
        vi.stubGlobal('fetch', fetchMock)

        const events = await listEventDefinitionsReal()
        const capabilities = await listEventCapabilitiesReal()
        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/catalog/events',
            expect.any(Object),
        )
        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/catalog/event-capabilities',
            expect.any(Object),
        )
        expect(events[0].code).toBe('demo.dispatch')
        expect(events[0].priorityClass).toBe('STANDARD')
        expect(capabilities[0].eventCode).toBe('demo.dispatch')
        expect(capabilities[0].responseMode).toBe('FINAL_RESULT')
    })
})
