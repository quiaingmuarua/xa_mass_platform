import {resetRuntimeConfigOverrides, setRuntimeConfigOverrides} from '@/app/config'
import {
    listEventCapabilities,
    listEventDefinitions,
    listWorkerGroupCapabilities,
} from '@/api/catalog'
import {
    listEventCapabilitiesReal,
    listEventDefinitionsReal,
    listWorkerGroupCapabilitiesReal,
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
        const workerGroups = await listWorkerGroupCapabilities()

        expect(events.some((event) => event.code === 'probe.url.dns')).toBe(
            true,
        )
        expect(capabilities.some((item) => item.eventCode === 'probe.phone.metadata')).toBe(true)
        expect(workerGroups.some((item) => item.groupId === 'phone-device-probe')).toBe(true)
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
            if (input.endsWith('/api/v1/catalog/worker-group-capabilities')) {
                return Promise.resolve(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: [
                            {
                                groupId: 'phone-device-probe',
                                eventBindings: [
                                    {eventCode: 'probe.phone.metadata', projectCodes: ['deviceProbe']},
                                ],
                                projectCodes: ['deviceProbe'],
                                workerCount: 30,
                                declaredWorkerIds: ['phone-device-probe-ws-sg-001'],
                                transportCounts: {realtime: 10, polling: 20},
                                reachableWorkerCountsByTransport: {realtime: 10, polling: 20},
                                runtimeStatusCounts: {ONLINE: 30},
                                lockedCount: 0,
                                reachableUnlockedBindingCount: 30,
                                fingerprintDistribution: {'fp-android-sg-a': 3},
                            },
                        ],
                    }),
                )
            }
            if (input.endsWith('/api/v1/catalog/event-capabilities')) {
                return Promise.resolve(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: [
                            {
                                eventCode: 'probe.phone.metadata',
                                eventName: 'Phone Metadata Probe',
                                enabled: true,
                                priorityClass: 'STANDARD',
                                responseMode: 'FINAL_RESULT',
                                targetScope: 'WORKER',
                                invocationModel: 'TASK_BACKED',
                                projectCodes: ['deviceProbe'],
                                declaredWorkerIds: ['phone-device-probe-ws-sg-001'],
                                reachableWorkerIds: ['phone-device-probe-ws-sg-001'],
                                hasDirectRuntimeHandler: false,
                                hasReachableWorkerCoverage: true,
                                hasInvocationCoverage: true,
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
        const workerGroups = await listWorkerGroupCapabilitiesReal()
        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/catalog/events',
            expect.any(Object),
        )
        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/catalog/event-capabilities',
            expect.any(Object),
        )
        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/catalog/worker-group-capabilities',
            expect.any(Object),
        )
        expect(events[0].code).toBe('probe.phone.metadata')
        expect(events[0].priorityClass).toBe('STANDARD')
        expect(capabilities[0].eventCode).toBe('probe.phone.metadata')
        expect(capabilities[0].responseMode).toBe('FINAL_RESULT')
        expect(workerGroups[0].fingerprintDistribution['fp-android-sg-a']).toBe(3)
    })
})
