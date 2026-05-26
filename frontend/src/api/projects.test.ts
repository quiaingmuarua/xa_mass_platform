import {resetRuntimeConfigOverrides, setRuntimeConfigOverrides} from '@/app/config'
import {
    getProject,
    listProjectEventDefinitions,
    listProjects,
    listProjectSubmitters,
} from '@/api/projects'
import {
    getProjectReal,
    listProjectEventDefinitionsReal,
    listProjectsReal,
    listProjectSubmittersReal,
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
        const probeSubmitters = await listProjectSubmitters('publicProbe')

        expect(projects.some((project) => project.code === 'publicProbe')).toBe(true)
        expect(probeProject.code).toBe('publicProbe')
        expect(probeEvents.map((event) => event.code)).toContain('probe.url.dns')
        expect(
            probeSubmitters.some(
                (submitter) => submitter.principalId === 'public-probe-runner',
            ),
        ).toBe(true)
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
            if (input.endsWith('/api/v1/projects/publicProbe/submitters')) {
                return Promise.resolve(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: [
                            {
                                principalId: 'public-probe-runner',
                                principalType: 'SERVICE',
                                keyPrefix: 'pubp',
                                userId: 'public-probe-runner',
                                projectScope: 'publicProbe',
                                permissions: ['task:create'],
                                projectScopes: ['publicProbe'],
                                eventScopes: ['probe.url.dns'],
                                enabled: true,
                                attributes: {},
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
        const submitters = await listProjectSubmittersReal('publicProbe')

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
        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/projects/publicProbe/submitters',
            expect.any(Object),
        )
        expect(projects[0].code).toBe('publicProbe')
        expect(project.code).toBe('publicProbe')
        expect(events[0].code).toBe('probe.url.dns')
        expect(events[0].targetScope).toBe('WORKER')
        expect(submitters[0].principalId).toBe('public-probe-runner')
    })
})
