import { resetRuntimeConfigOverrides, setRuntimeConfigOverrides } from '@/app/config'
import {
    listEventMetadata,
    listProjectEventMetadata,
    listProjectMetadata,
} from '@/api/metadata'
import { listEventMetadataReal, listProjectMetadataReal } from '@/api/metadata.real'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('metadata API facade', () => {
    afterEach(() => {
        resetRuntimeConfigOverrides()
        vi.unstubAllGlobals()
    })

    it('serves SDK project and event metadata from the mock adapter', async () => {
        setRuntimeConfigOverrides({ useMockApi: true })

        const projects = await listProjectMetadata()
        const events = await listEventMetadata()
        const demoEvents = await listProjectEventMetadata('demoApp')

        expect(projects.some((project) => project.code === 'demoApp')).toBe(true)
        expect(events.some((event) => event.code === 'demo.dispatch.run')).toBe(
            true,
        )
        expect(demoEvents.map((event) => event.code)).toContain(
            'demo.dispatch.run',
        )
    })
})

describe('metadata.real', () => {
    afterEach(() => {
        resetRuntimeConfigOverrides()
        vi.unstubAllGlobals()
    })

    it('calls backend SDK metadata endpoints', async () => {
        setRuntimeConfigOverrides({ apiBaseUrl: '/backend' })
        const fetchMock = vi.fn((input: string) => {
            if (input.endsWith('/sdk/meta/events')) {
                return Promise.resolve(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: [
                            {
                                code: 'demo.dispatch.run',
                                name: 'Run demo dispatch',
                                description: 'Run a demo dispatch.',
                                payloadTypes: ['JSON'],
                                taskModes: ['SINGLE_RUN'],
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
                    data: [
                        {
                            code: 'demoApp',
                            name: 'Demo App',
                            description: 'Demo project.',
                            enabled: true,
                            eventCodes: ['demo.dispatch.run'],
                        },
                    ],
                }),
            )
        })
        vi.stubGlobal('fetch', fetchMock)

        const projects = await listProjectMetadataReal()
        const events = await listEventMetadataReal()

        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/sdk/meta/projects',
            expect.any(Object),
        )
        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/sdk/meta/events',
            expect.any(Object),
        )
        expect(projects[0].code).toBe('demoApp')
        expect(events[0].code).toBe('demo.dispatch.run')
    })
})
