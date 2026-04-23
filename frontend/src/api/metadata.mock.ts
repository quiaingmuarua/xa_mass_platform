import type { EventMetadata, ProjectMetadata } from '@/types/metadata'

const mockProjects: ProjectMetadata[] = [
    {
        code: 'demoApp',
        name: 'Demo App',
        description:
            'General demo project used for orchestration smoke tests and worker readiness checks.',
        enabled: true,
        eventCodes: ['demo.dispatch.run', 'demo.dispatch.audit'],
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
        code: 'demo.dispatch.run',
        name: 'Run demo dispatch',
        description:
            'Dispatch a generic demo payload to an online worker.',
        payloadTypes: ['JSON', 'TEXT'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
    },
    {
        code: 'demo.dispatch.audit',
        name: 'Audit demo dispatch',
        description:
            'Validate previous demo dispatch output and return an audit result.',
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

export async function getEventMetadataMock(
    eventCode: string,
): Promise<EventMetadata> {
    const event = mockEvents.find((item) => item.code === eventCode)
    if (!event) {
        throw new Error(`Event metadata not found: ${eventCode}`)
    }

    return delay(event)
}
