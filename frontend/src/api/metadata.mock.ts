import type {EventCapability, EventMetadata, ProjectMetadata} from '@/types/metadata'

const mockProjects: ProjectMetadata[] = [
    {
        code: 'demoApp',
        name: 'Demo App',
        description:
            'General demo project used for orchestration smoke tests and worker readiness checks.',
        enabled: true,
        eventCodes: ['demo.dispatch', 'demo.dispatch.gb'],
    },
    {
        code: 'crawlerApp',
        name: 'Crawler App',
        description:
            'Crawler-oriented project used to validate generic fetch and parse workloads.',
        enabled: true,
        eventCodes: ['crawler.fetch-page', 'crawler.parse-result'],
    },
    {
        code: 'testApp',
        name: 'Test Harness',
        description:
            'Small project used by local and CI validation flows.',
        enabled: true,
        eventCodes: ['test.smoke'],
    },
]

const mockEvents: EventMetadata[] = [
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

export async function listProjectEventMetadataMock(
    projectCode: string,
): Promise<EventMetadata[]> {
    const project = mockProjects.find((item) => item.code === projectCode)
    if (!project) {
        throw new Error(`Project metadata not found: ${projectCode}`)
    }

    const projectEvents = mockEvents.filter((event) =>
        project.eventCodes.includes(event.code),
    )
    return delay(projectEvents)
}

export async function listEventMetadataMock(): Promise<EventMetadata[]> {
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

export async function getEventMetadataMock(
    eventCode: string,
): Promise<EventMetadata> {
    const event = mockEvents.find((item) => item.code === eventCode)
    if (!event) {
        throw new Error(`Event metadata not found: ${eventCode}`)
    }

    return delay(event)
}
