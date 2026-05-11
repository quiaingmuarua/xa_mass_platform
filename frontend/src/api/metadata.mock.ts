import type {
    EventCapability,
    ProjectMetadata,
    ProjectSubmitterMetadata,
    SdkEventDefinition,
} from '@/types/metadata'

const mockProjects: ProjectMetadata[] = [
    {
        tenantId: 'default',
        code: 'demoApp',
        name: 'Demo App',
        description:
            'General demo project used for orchestration smoke tests and worker readiness checks.',
        enabled: true,
        eventCodes: ['demo.dispatch', 'demo.dispatch.gb'],
        ownerPrincipalId: 'demo-admin-submitter',
    },
    {
        tenantId: 'default',
        code: 'crawlerApp',
        name: 'Crawler App',
        description:
            'Crawler-oriented project used to validate generic fetch and parse workloads.',
        enabled: true,
        eventCodes: ['crawler.fetch-page', 'crawler.parse-result'],
        ownerPrincipalId: 'crawler-admin-submitter',
    },
    {
        tenantId: 'default',
        code: 'testApp',
        name: 'Test Harness',
        description:
            'Small project used by local and CI validation flows.',
        enabled: true,
        eventCodes: ['test.smoke'],
        ownerPrincipalId: 'test-admin-submitter',
    },
]

const mockProjectSubmitters: Record<string, ProjectSubmitterMetadata[]> = {
    demoApp: [
        {
            principalId: 'demo-app-submitter',
            principalType: 'SERVICE',
            keyPrefix: 'demo',
            userId: 'demo-app-user',
            projectScope: 'demoApp',
            permissions: ['task:create'],
            projectScopes: ['demoApp'],
            eventScopes: ['demo.dispatch', 'demo.dispatch.gb'],
            enabled: true,
            attributes: {
                label: 'Demo App Submitter',
            },
        },
        {
            principalId: 'demo-admin-submitter',
            principalType: 'SERVICE',
            keyPrefix: 'demo',
            userId: 'demo-admin',
            projectScope: null,
            permissions: ['*'],
            projectScopes: ['demoApp', 'crawlerApp', 'testApp'],
            eventScopes: ['*'],
            enabled: true,
            attributes: {
                label: 'Demo Admin Submitter',
            },
        },
    ],
    crawlerApp: [
        {
            principalId: 'crawler-submitter',
            principalType: 'SERVICE',
            keyPrefix: 'crawl',
            userId: 'crawler-user',
            projectScope: 'crawlerApp',
            permissions: ['task:create'],
            projectScopes: ['crawlerApp'],
            eventScopes: ['crawler.fetch-page', 'crawler.parse-result'],
            enabled: true,
            attributes: {
                label: 'Crawler Submitter',
            },
        },
        {
            principalId: 'demo-admin-submitter',
            principalType: 'SERVICE',
            keyPrefix: 'demo',
            userId: 'demo-admin',
            projectScope: null,
            permissions: ['*'],
            projectScopes: ['demoApp', 'crawlerApp', 'testApp'],
            eventScopes: ['*'],
            enabled: true,
            attributes: {
                label: 'Demo Admin Submitter',
            },
        },
    ],
    testApp: [
        {
            principalId: 'test-admin-submitter',
            principalType: 'SERVICE',
            keyPrefix: 'test',
            userId: 'test-user',
            projectScope: 'testApp',
            permissions: ['task:create'],
            projectScopes: ['testApp'],
            eventScopes: ['test.smoke'],
            enabled: true,
            attributes: {
                label: 'Test Submitter',
            },
        },
    ],
}

const mockEvents: SdkEventDefinition[] = [
    {
        code: 'demo.dispatch',
        name: 'Demo dispatch',
        description:
            'Dispatch a generic demo payload to an online worker.',
        payloadTypes: ['JSON', 'TEXT'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
    },
    {
        code: 'demo.dispatch.gb',
        name: 'Demo dispatch (GB)',
        description:
            'Dispatch a generic demo payload to the GB demo lane.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
    },
    {
        code: 'crawler.fetch-page',
        name: 'Fetch crawler page',
        description:
            'Fetch a page or URL seed for downstream processing.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
    },
    {
        code: 'crawler.parse-result',
        name: 'Parse crawler result',
        description:
            'Parse crawler output into structured downstream records.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
    },
    {
        code: 'test.smoke',
        name: 'Smoke test event',
        description:
            'Minimal event used to verify SDK metadata and dispatch plumbing.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
    },
    {
        code: 'tool.country.capital.lookup',
        name: 'Tool Country Capital Lookup',
        description:
            'Resolve a country code to a stable country and capital reference profile.',
        payloadTypes: ['JSON'],
        taskModes: [],
        enabled: true,
    },
]

function delay<T>(value: T): Promise<T> {
    return new Promise((resolve) => {
        window.setTimeout(() => resolve(value), 80)
    })
}

export async function listProjectMetadataMock(): Promise<ProjectMetadata[]> {
    return delay(mockProjects)
}

export async function getProjectMetadataMock(
    projectCode: string,
): Promise<ProjectMetadata> {
    const project = mockProjects.find((item) => item.code === projectCode)
    if (!project) {
        throw new Error(`Project metadata not found: ${projectCode}`)
    }

    return delay(project)
}

export async function listProjectEventDefinitionsMock(
    projectCode: string,
): Promise<SdkEventDefinition[]> {
    const project = mockProjects.find((item) => item.code === projectCode)
    if (!project) {
        throw new Error(`Project metadata not found: ${projectCode}`)
    }

    const projectEvents = mockEvents.filter((event) =>
        project.eventCodes.includes(event.code),
    )
    return delay(projectEvents)
}

export async function listProjectSubmittersMock(
    projectCode: string,
): Promise<ProjectSubmitterMetadata[]> {
    const project = mockProjects.find((item) => item.code === projectCode)
    if (!project) {
        throw new Error(`Project metadata not found: ${projectCode}`)
    }

    return delay(mockProjectSubmitters[projectCode] ?? [])
}

export async function listEventDefinitionsMock(): Promise<SdkEventDefinition[]> {
    return delay(mockEvents)
}

export async function listEventCapabilitiesMock(): Promise<EventCapability[]> {
    return delay(
        mockEvents.map((event) => {
            const projectCodes = mockProjects
                .filter((project) => project.eventCodes.includes(event.code))
                .map((project) => project.code)
            const directRuntime = event.taskModes.length === 0

            return {
                eventCode: event.code,
                eventName: event.name,
                enabled: event.enabled,
                invocationModel: directRuntime ? 'DIRECT_RUNTIME' : 'TASK_BACKED',
                projectCodes,
                workerIds: directRuntime ? [] : ['mock-worker-1'],
                onlineWorkerIds: directRuntime ? [] : ['mock-worker-1'],
                hasDirectRuntimeHandler: directRuntime,
                hasOnlineWorkerCoverage: !directRuntime,
                ready: true,
            }
        }),
    )
}

export async function getEventDefinitionMock(
    eventCode: string,
): Promise<SdkEventDefinition> {
    const event = mockEvents.find((item) => item.code === eventCode)
    if (!event) {
        throw new Error(`SDK event definition not found: ${eventCode}`)
    }

    return delay(event)
}
