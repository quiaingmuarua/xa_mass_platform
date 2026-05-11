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
        const demoProject = await getProject('demoApp')
        const demoEvents = await listProjectEventDefinitions('demoApp')
        const demoSubmitters = await listProjectSubmitters('demoApp')

        expect(projects.some((project) => project.code === 'demoApp')).toBe(true)
        expect(demoProject.code).toBe('demoApp')
        expect(demoEvents.map((event) => event.code)).toContain('demo.dispatch')
        expect(
            demoSubmitters.some(
                (submitter) => submitter.principalId === 'demo-app-submitter',
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
            if (input.endsWith('/api/v1/projects/demoApp/events')) {
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
                        ],
                    }),
                )
            }
            if (input.endsWith('/api/v1/projects/demoApp/submitters')) {
                return Promise.resolve(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: [
                            {
                                principalId: 'demo-app-submitter',
                                principalType: 'SERVICE',
                                keyPrefix: 'demo',
                                userId: 'demo-app-user',
                                projectScope: 'demoApp',
                                permissions: ['task:create'],
                                projectScopes: ['demoApp'],
                                eventScopes: ['demo.dispatch'],
                                enabled: true,
                                attributes: {},
                            },
                        ],
                    }),
                )
            }
            if (input.endsWith('/api/v1/projects/demoApp')) {
                return Promise.resolve(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: {
                            code: 'demoApp',
                            name: 'Demo App',
                            description: 'Demo project.',
                            enabled: true,
                            eventCodes: ['demo.dispatch'],
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
                            code: 'demoApp',
                            name: 'Demo App',
                            description: 'Demo project.',
                            enabled: true,
                            eventCodes: ['demo.dispatch'],
                        },
                    ],
                }),
            )
        })
        vi.stubGlobal('fetch', fetchMock)

        const projects = await listProjectsReal()
        const project = await getProjectReal('demoApp')
        const events = await listProjectEventDefinitionsReal('demoApp')
        const submitters = await listProjectSubmittersReal('demoApp')

        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/projects',
            expect.any(Object),
        )
        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/projects/demoApp',
            expect.any(Object),
        )
        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/projects/demoApp/events',
            expect.any(Object),
        )
        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/projects/demoApp/submitters',
            expect.any(Object),
        )
        expect(projects[0].code).toBe('demoApp')
        expect(project.code).toBe('demoApp')
        expect(events[0].code).toBe('demo.dispatch')
        expect(submitters[0].principalId).toBe('demo-app-submitter')
    })
})
