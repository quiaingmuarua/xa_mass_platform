import type { EventMetadata, ProjectMetadata } from '@/types/metadata'

const mockProjects: ProjectMetadata[] = [
    {
        code: 'demoApp',
        name: 'Demo App',
        description:
            'General demo project used for orchestration smoke tests and worker readiness checks.',
        enabled: true,
        eventCodes: ['demo.message.send', 'demo.message.audit'],
    },
    {
        code: 'telegramApp',
        name: 'Telegram App',
        description:
            'Telegram-oriented runtime project for message delivery and session-oriented work.',
        enabled: true,
        eventCodes: ['telegram.message.send', 'telegram.session.refresh'],
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
        code: 'demo.message.send',
        name: 'Send demo message',
        description:
            'Dispatch a demo message payload to an online worker.',
        payloadTypes: ['JSON', 'TEXT'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
    },
    {
        code: 'demo.message.audit',
        name: 'Audit demo message',
        description:
            'Validate previous demo message output and return an audit result.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
    },
    {
        code: 'telegram.message.send',
        name: 'Send Telegram message',
        description:
            'Send a Telegram-style message through a worker-owned channel.',
        payloadTypes: ['JSON', 'TEXT'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
    },
    {
        code: 'telegram.session.refresh',
        name: 'Refresh Telegram session',
        description:
            'Refresh or validate a long-lived Telegram worker context.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN', 'STREAMING'],
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
