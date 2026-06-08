import {resetRuntimeConfigOverrides, setRuntimeConfigOverrides} from '@/app/config'
import {
    getProject,
    listProjectEventDefinitions,
    listProjects,
} from '@/api/projects'
import {
    getProjectReal,
    listProjectEventDefinitionsReal,
    listProjectsReal,
} from '@/api/projects.real'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('projects API facade', () => {
    afterEach(() => {
        resetRuntimeConfigOverrides()
        vi.unstubAllGlobals()
    })

    it('serves project resources from the mock adapter', async () => {
        setRuntimeConfigOverrides({ useMockApi: true })

        const projects = await listProjects()
        const probeProject = await getProject('publicProbe')
        const probeEvents = await listProjectEventDefinitions('publicProbe')

        expect(projects.some((project) => project.code === 'publicProbe')).toBe(true)
        expect(probeProject.code).toBe('publicProbe')
        expect(probeEvents.map((event) => event.code)).toContain('probe.url.dns')
    })
})

describe('projects.real', () => {
    afterEach(() => {
        resetRuntimeConfigOverrides()
        vi.unstubAllGlobals()
    })

    it('calls backend project resource endpoints', async () => {
        setRuntimeConfigOverrides({ apiBaseUrl: '/backend' })
        const fetchMock = vi.fn((input: string) => {
            if (input.endsWith('/api/v1/projects/publicProbe/events')) {
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
                        ],
                    }),
                )
            }
            if (input.endsWith('/api/v1/projects/publicProbe')) {
                return Promise.resolve(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: {
                            code: 'publicProbe',
                            name: 'Public Probe',
                            description: 'Public probe project.',
                            enabled: true,
                            eventCodes: ['probe.url.dns'],
                        },
                    }),
                )
            }

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
                    ],
                }),
            )
        })
        vi.stubGlobal('fetch', fetchMock)

        const projects = await listProjectsReal()
        const project = await getProjectReal('publicProbe')
        const events = await listProjectEventDefinitionsReal('publicProbe')

        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/projects',
            expect.any(Object),
        )
        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/projects/publicProbe',
            expect.any(Object),
        )
        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/projects/publicProbe/events',
            expect.any(Object),
        )
        expect(projects[0].code).toBe('publicProbe')
        expect(project.code).toBe('publicProbe')
        expect(events[0].code).toBe('probe.url.dns')
        expect(events[0].targetScope).toBe('WORKER')
    })
})
