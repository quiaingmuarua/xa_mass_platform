import type {
    AdapterNodeCapability,
    EventDefinition,
    EventCapability,
    NodeGroupBindingCapability,
    WorkerGroupCapability,
} from '@/types/catalog'
import type {ProjectDefinition} from '@/types/projects'

export const mockProjects: ProjectDefinition[] = [
    {
        tenantId: 'default',
        code: 'publicProbe',
        name: 'Public Probe',
        description:
            'Public API probe project for weather, FX, URL DNS, HTTP status, and IP metadata checks.',
        enabled: true,
        eventCodes: [
            'probe.weather.current',
            'probe.fx.latest',
            'probe.url.dns',
            'probe.http.status',
        ],
        ownerPrincipalId: 'public-probe-ops',
    },
    {
        tenantId: 'default',
        code: 'deviceProbe',
        name: 'Device Probe',
        description:
            'Phone and device metadata probe project for fingerprint-aware routing.',
        enabled: true,
        eventCodes: ['probe.phone.metadata'],
        ownerPrincipalId: 'device-probe-runner',
    },
    {
        tenantId: 'default',
        code: 'dataQualityProbe',
        name: 'Data Quality Probe',
        description:
            'Deterministic CSV and JSON validation project for CI-safe probe items.',
        enabled: true,
        eventCodes: ['probe.market.daily-csv', 'probe.csv.validate', 'probe.json.schema'],
        ownerPrincipalId: 'data-quality-runner',
    },
]

export const mockEvents: EventDefinition[] = [
    {
        code: 'probe.url.dns',
        name: 'URL DNS Inspection',
        description:
            'Resolve a URL domain and classify reachable, NXDOMAIN, timeout, or malformed inputs.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
        priorityClass: 'STANDARD',
        responseMode: 'FINAL_RESULT',
        targetScope: 'WORKER',
    },
    {
        code: 'probe.weather.current',
        name: 'Open-Meteo Current Weather',
        description:
            'Fetch current weather JSON and validate temperature, humidity, and wind fields.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
        priorityClass: 'STANDARD',
        responseMode: 'FINAL_RESULT',
        targetScope: 'WORKER',
    },
    {
        code: 'probe.fx.latest',
        name: 'Exchange Rate Snapshot',
        description:
            'Fetch latest exchange rates and validate required currency fields.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
        priorityClass: 'STANDARD',
        responseMode: 'FINAL_RESULT',
        targetScope: 'WORKER',
    },
    {
        code: 'probe.http.status',
        name: 'HTTP Status Probe',
        description:
            'Verify expected HTTP status and latency threshold on test endpoints.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
        priorityClass: 'STANDARD',
        responseMode: 'FINAL_RESULT',
        targetScope: 'WORKER',
    },
    {
        code: 'probe.phone.metadata',
        name: 'Phone Metadata Probe',
        description:
            'Validate phone metadata and carrier hints with fingerprint-matched device workers.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
        priorityClass: 'STANDARD',
        responseMode: 'FINAL_RESULT',
        targetScope: 'WORKER',
    },
    {
        code: 'probe.market.daily-csv',
        name: 'Market Daily CSV',
        description:
            'Parse daily market CSV payloads and validate date, price, and volume columns.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
        priorityClass: 'STANDARD',
        responseMode: 'FINAL_RESULT',
        targetScope: 'WORKER',
    },
    {
        code: 'probe.csv.validate',
        name: 'CSV Validation',
        description:
            'Validate deterministic local CSV records and classify malformed rows.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
        priorityClass: 'STANDARD',
        responseMode: 'FINAL_RESULT',
        targetScope: 'WORKER',
    },
    {
        code: 'probe.json.schema',
        name: 'JSON Schema Validation',
        description:
            'Validate JSON documents against local schema fixtures.',
        payloadTypes: ['JSON'],
        taskModes: ['SINGLE_RUN'],
        enabled: true,
        priorityClass: 'STANDARD',
        responseMode: 'FINAL_RESULT',
        targetScope: 'WORKER',
    },
]

const pollingAdapterNode: AdapterNodeCapability = {
    adapterNodeId: 'control-console-polling',
    adapterType: 'polling',
    adapterVersion: null,
    endpointId: 'polling',
    enabled: true,
    online: true,
    attributes: {},
}

const websocketAdapterNode: AdapterNodeCapability = {
    adapterNodeId: 'control-console-websocket',
    adapterType: 'websocket',
    adapterVersion: null,
    endpointId: 'ws',
    enabled: true,
    online: true,
    attributes: {},
}

function enabledBinding(
    adapterNodeId: string,
    workerGroupId: string,
): NodeGroupBindingCapability {
    return {
        adapterNodeId,
        workerGroupId,
        pluginVersion: null,
        deploymentVersion: null,
        enabled: true,
        draining: false,
        attributes: {},
    }
}

export const mockWorkerGroupCapabilities: WorkerGroupCapability[] = [
    {
        groupId: 'public-probe-http',
        eventBindings: [
            {eventCode: 'probe.weather.current', projectCodes: ['publicProbe']},
            {eventCode: 'probe.fx.latest', projectCodes: ['publicProbe']},
            {eventCode: 'probe.http.status', projectCodes: ['publicProbe']},
        ],
        projectCodes: ['publicProbe'],
        defaultAttributes: {executionProfile: 'public-http'},
        defaultMaxConcurrentWork: 4,
        adapterNodes: [pollingAdapterNode, websocketAdapterNode],
        nodeGroupBindings: [
            enabledBinding('control-console-polling', 'public-probe-http'),
            enabledBinding('control-console-websocket', 'public-probe-http'),
        ],
        workerCount: 80,
        declaredWorkerIds: ['public-probe-http-poll-use1-001', 'public-probe-http-ws-euw1-001'],
        transportCounts: {polling: 60, realtime: 20},
        reachableWorkerCountsByTransport: {polling: 60, realtime: 20},
        runtimeStatusCounts: {ONLINE: 80},
        lockedCount: 2,
        reachableUnlockedWorkerCount: 78,
        fingerprintDistribution: {},
    },
    {
        groupId: 'dns-url-inspector',
        eventBindings: [
            {eventCode: 'probe.url.dns', projectCodes: ['publicProbe']},
        ],
        projectCodes: ['publicProbe'],
        defaultAttributes: {executionProfile: 'dns-url-inspector'},
        defaultMaxConcurrentWork: 2,
        adapterNodes: [pollingAdapterNode],
        nodeGroupBindings: [
            enabledBinding('control-console-polling', 'dns-url-inspector'),
        ],
        workerCount: 15,
        declaredWorkerIds: ['dns-url-inspector-poll-001'],
        transportCounts: {polling: 10, realtime: 5},
        reachableWorkerCountsByTransport: {polling: 10, realtime: 5},
        runtimeStatusCounts: {ONLINE: 15},
        lockedCount: 0,
        reachableUnlockedWorkerCount: 15,
        fingerprintDistribution: {},
    },
    {
        groupId: 'phone-device-probe',
        eventBindings: [
            {eventCode: 'probe.phone.metadata', projectCodes: ['deviceProbe']},
        ],
        projectCodes: ['deviceProbe'],
        defaultAttributes: {executionProfile: 'phone-device', country: 'SG'},
        defaultMaxConcurrentWork: 1,
        adapterNodes: [pollingAdapterNode, websocketAdapterNode],
        nodeGroupBindings: [
            enabledBinding('control-console-polling', 'phone-device-probe'),
            enabledBinding('control-console-websocket', 'phone-device-probe'),
        ],
        workerCount: 30,
        declaredWorkerIds: ['phone-device-probe-poll-sg-001', 'phone-device-probe-ws-sg-001'],
        transportCounts: {polling: 20, realtime: 10},
        reachableWorkerCountsByTransport: {polling: 20, realtime: 10},
        runtimeStatusCounts: {ONLINE: 30},
        lockedCount: 1,
        reachableUnlockedWorkerCount: 29,
        fingerprintDistribution: {
            'fp-android-sg-a': 3,
            'fp-android-sg-b': 3,
            'fp-android-sg-c': 3,
            'fp-android-sg-d': 3,
            'fp-android-sg-e': 3,
            'fp-android-sg-f': 3,
            'fp-android-sg-g': 3,
            'fp-android-sg-h': 3,
            'fp-android-sg-i': 3,
            'fp-android-sg-j': 3,
        },
    },
    {
        groupId: 'market-csv-parser',
        eventBindings: [
            {eventCode: 'probe.market.daily-csv', projectCodes: ['dataQualityProbe']},
            {eventCode: 'probe.csv.validate', projectCodes: ['dataQualityProbe']},
        ],
        projectCodes: ['dataQualityProbe'],
        defaultAttributes: {executionProfile: 'csv-parser'},
        defaultMaxConcurrentWork: 2,
        adapterNodes: [pollingAdapterNode],
        nodeGroupBindings: [
            enabledBinding('control-console-polling', 'market-csv-parser'),
        ],
        workerCount: 10,
        declaredWorkerIds: ['market-csv-parser-poll-001'],
        transportCounts: {polling: 10},
        reachableWorkerCountsByTransport: {polling: 10},
        runtimeStatusCounts: {ONLINE: 10},
        lockedCount: 0,
        reachableUnlockedWorkerCount: 10,
        fingerprintDistribution: {},
    },
    {
        groupId: 'local-json-validator',
        eventBindings: [
            {eventCode: 'probe.json.schema', projectCodes: ['dataQualityProbe']},
        ],
        projectCodes: ['dataQualityProbe'],
        defaultAttributes: {executionProfile: 'json-validator'},
        defaultMaxConcurrentWork: 2,
        adapterNodes: [pollingAdapterNode],
        nodeGroupBindings: [
            enabledBinding('control-console-polling', 'local-json-validator'),
        ],
        workerCount: 10,
        declaredWorkerIds: ['local-json-validator-poll-001'],
        transportCounts: {polling: 10},
        reachableWorkerCountsByTransport: {polling: 10},
        runtimeStatusCounts: {ONLINE: 10},
        lockedCount: 0,
        reachableUnlockedWorkerCount: 10,
        fingerprintDistribution: {},
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
            declaredWorkerIds: directRuntime ? [] : mockWorkerGroupCapabilities
                .filter((group) => group.eventBindings.some((binding) => binding.eventCode === event.code))
                .flatMap((group) => group.declaredWorkerIds),
            reachableWorkerIds: directRuntime ? [] : mockWorkerGroupCapabilities
                .filter((group) => group.eventBindings.some((binding) => binding.eventCode === event.code))
                .flatMap((group) => group.declaredWorkerIds),
            hasDirectRuntimeHandler: directRuntime,
            hasReachableWorkerCoverage: !directRuntime,
            hasInvocationCoverage: true,
        }
    })
}
