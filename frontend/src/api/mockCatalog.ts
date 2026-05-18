import type {EventDefinition, EventCapability} from '@/types/catalog'
import type {
    ProjectDefinition,
    ProjectSubmitterProfile,
} from '@/types/projects'

export const mockProjects: ProjectDefinition[] = [
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

export const mockProjectSubmitters: Record<string, ProjectSubmitterProfile[]> = {
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

export const mockEvents: EventDefinition[] = [
    {
        code: 'demo.dispatch',
        name: 'Demo dispatch',
        description:
            'Dispatch a generic demo payload to an online worker.',
        payloadTypes: ['JSON', 'TEXT'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
        priorityClass: 'STANDARD',
        responseMode: 'FINAL_RESULT',
        targetScope: 'WORKER',
    },
    {
        code: 'demo.dispatch.gb',
        name: 'Demo dispatch (GB)',
        description:
            'Dispatch a generic demo payload to the GB demo lane.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
        priorityClass: 'STANDARD',
        responseMode: 'FINAL_RESULT',
        targetScope: 'WORKER',
    },
    {
        code: 'crawler.fetch-page',
        name: 'Fetch crawler page',
        description:
            'Fetch a page or URL seed for downstream processing.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
        priorityClass: 'STANDARD',
        responseMode: 'FINAL_RESULT',
        targetScope: 'WORKER',
    },
    {
        code: 'crawler.parse-result',
        name: 'Parse crawler result',
        description:
            'Parse crawler output into structured downstream records.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
        priorityClass: 'STANDARD',
        responseMode: 'FINAL_RESULT',
        targetScope: 'WORKER',
    },
    {
        code: 'test.smoke',
        name: 'Smoke test event',
        description:
            'Minimal event used to verify catalog registration and dispatch plumbing.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
        priorityClass: 'STANDARD',
        responseMode: 'FINAL_RESULT',
        targetScope: 'WORKER',
    },
    {
        code: 'tool.country.capital.lookup',
        name: 'Tool Country Capital Lookup',
        description:
            'Resolve a country code to a stable country and capital reference profile.',
        payloadTypes: ['JSON'],
        taskModes: [],
        enabled: true,
        priorityClass: 'STANDARD',
        responseMode: 'FINAL_RESULT',
        targetScope: 'WORKER',
    },
]

export function deriveMockEventCapabilities(): EventCapability[] {
    return mockEvents.map((event) => {
        const projectCodes = mockProjects
            .filter((project) => project.eventCodes.includes(event.code))
            .map((project) => project.code)
        const directRuntime = event.taskModes.length === 0

        return {
            eventCode: event.code,
            eventName: event.name,
            enabled: event.enabled,
            priorityClass: event.priorityClass,
            responseMode: event.responseMode,
            targetScope: event.targetScope,
            invocationModel: directRuntime ? 'DIRECT_RUNTIME' : 'TASK_BACKED',
            projectCodes,
            workerIds: directRuntime ? [] : ['mock-worker-1'],
            onlineWorkerIds: directRuntime ? [] : ['mock-worker-1'],
            hasDirectRuntimeHandler: directRuntime,
            hasOnlineWorkerCoverage: !directRuntime,
            ready: true,
        }
    })
}
