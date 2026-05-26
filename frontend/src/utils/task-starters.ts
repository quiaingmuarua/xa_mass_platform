export interface TaskStarterDraft {
    projectCode: string
    eventCode?: string
    taskName: string
    batchSize: number
    keepIntakeOpen: boolean
    maxRuntimeSeconds: number
    sharedConfig: Record<string, unknown>
    items: Array<Record<string, unknown>>
    guidance: string[]
}

interface TaskStarterOverride {
    taskName?: string
    batchSize?: number
    keepIntakeOpen?: boolean
    maxRuntimeSeconds?: number
    sharedConfig?: Record<string, unknown>
    items?: Array<Record<string, unknown>>
    guidance?: string[]
}

interface TaskStarterDefinition extends TaskStarterDraft {
    eventOverrides?: Record<string, TaskStarterOverride>
}

const taskStarterDefinitions: Record<string, TaskStarterDefinition> = {
    publicProbe: {
        projectCode: 'publicProbe',
        taskName: 'Run public probe batch',
        batchSize: 20,
        keepIntakeOpen: false,
        maxRuntimeSeconds: 300,
        sharedConfig: {
            scenario: 'control-console-realistic',
            objective: 'verify public probe providers and URL reachability',
        },
        items: [
            {
                url: 'https://api.open-meteo.com/v1/forecast?latitude=22.5431&longitude=114.0579&current=temperature_2m,relative_humidity_2m,wind_speed_10m',
                provider: 'open-meteo',
                sleepMs: 120,
                timeoutMs: 5000,
                expectedOutcome: 'VALID_WEATHER_JSON',
                traceLabel: 'public-weather-shenzhen',
            },
            {
                url: 'https://does-not-exist.public-probe.invalid/',
                sleepMs: 120,
                timeoutMs: 2500,
                expectedOutcome: 'DNS_NXDOMAIN',
                traceLabel: 'dns-nxdomain-fixture',
            },
        ],
        guidance: [
            'Tasks are sealed by default; approve explicitly when you want dispatch to begin.',
            'Expected failures such as NXDOMAIN and timeout are valid probe classifications, not task terminal states.',
        ],
        eventOverrides: {
            'probe.url.dns': {
                taskName: 'Inspect URL DNS batch',
                sharedConfig: {
                    scenario: 'control-console-realistic',
                    objective: 'classify DNS and URL reachability',
                },
                items: [
                    {
                        url: 'https://open-meteo.com/',
                        sleepMs: 80,
                        timeoutMs: 3000,
                        expectedOutcome: 'DNS_OK',
                        traceLabel: 'dns-open-meteo',
                    },
                    {
                        url: 'https://does-not-exist.public-probe.invalid/',
                        sleepMs: 80,
                        timeoutMs: 3000,
                        expectedOutcome: 'DNS_NXDOMAIN',
                        traceLabel: 'dns-invalid-fixture',
                    },
                ],
            },
            'probe.http.status': {
                taskName: 'Check HTTP status fixtures',
                sharedConfig: {
                    scenario: 'control-console-realistic',
                    objective: 'verify status and latency classification',
                },
                items: [
                    {
                        url: 'https://httpbin.org/status/200',
                        expectedStatus: 200,
                        sleepMs: 100,
                        timeoutMs: 5000,
                        expectedOutcome: 'HTTP_STATUS_OK',
                        traceLabel: 'httpbin-200',
                    },
                ],
            },
        },
    },
    deviceProbe: {
        projectCode: 'deviceProbe',
        taskName: 'Run phone metadata probe',
        batchSize: 10,
        keepIntakeOpen: false,
        maxRuntimeSeconds: 180,
        sharedConfig: {
            scenario: 'control-console-realistic',
            objective: 'verify phone metadata with fingerprint-matched workers',
            workerGroupId: 'phone-device-probe',
            requiredFingerprintProfile: 'fp-android-sg-a',
        },
        items: [
            {
                phoneNumber: '+6591234567',
                defaultRegion: 'SG',
                sleepMs: 90,
                timeoutMs: 3000,
                expectedOutcome: 'VALID_E164',
                traceLabel: 'phone-sg-valid',
                requiredFingerprintProfile: 'fp-android-sg-a',
            },
            {
                phoneNumber: 'not-a-phone',
                defaultRegion: 'SG',
                sleepMs: 90,
                timeoutMs: 3000,
                expectedOutcome: 'INVALID_PHONE',
                traceLabel: 'phone-invalid-local',
                requiredFingerprintProfile: 'fp-android-sg-a',
            },
        ],
        guidance: [
            'Fingerprint requirements stay in task/shared item payload; Stage-2 worker rules match worker attributes inside the selected group.',
        ],
        eventOverrides: {
            'probe.phone.metadata': {
                taskName: 'Validate phone metadata',
            },
        },
    },
    dataQualityProbe: {
        projectCode: 'dataQualityProbe',
        taskName: 'Run local data quality probes',
        batchSize: 25,
        keepIntakeOpen: false,
        maxRuntimeSeconds: 180,
        sharedConfig: {
            scenario: 'control-console-realistic',
            objective: 'validate local CSV and JSON fixtures',
        },
        items: [
            {
                csv: 'Date,Open,High,Low,Close,Volume\n2026-05-25,10,12,9,11,12000',
                sleepMs: 60,
                timeoutMs: 2000,
                expectedOutcome: 'CSV_VALID',
                traceLabel: 'csv-valid-local',
            },
            {
                csv: 'Date,Open,High\nbad-row',
                sleepMs: 60,
                timeoutMs: 2000,
                expectedOutcome: 'CSV_INVALID',
                traceLabel: 'csv-invalid-local',
            },
        ],
        guidance: [
            'These fixtures are CI-safe and do not depend on public provider availability.',
        ],
        eventOverrides: {
            'probe.json.schema': {
                taskName: 'Validate JSON schema fixtures',
                items: [
                    {
                        document: {base: 'USD', rates: {CNY: 7.1, EUR: 0.9}},
                        schemaRef: 'exchange-rate-basic',
                        sleepMs: 50,
                        timeoutMs: 2000,
                        expectedOutcome: 'JSON_SCHEMA_VALID',
                        traceLabel: 'json-rate-valid',
                    },
                ],
            },
        },
    },
}

export interface TaskStarterSelection {
    projectCode?: string
    eventCode?: string
}

export function resolveTaskStarterDraft(
    selection: TaskStarterSelection,
): TaskStarterDraft {
    const projectCode = selection.projectCode?.trim() ?? ''
    const eventCode = selection.eventCode?.trim() || undefined
    const definition =
        taskStarterDefinitions[projectCode] ?? buildFallbackStarter(projectCode)
    const override = eventCode
        ? definition.eventOverrides?.[eventCode]
        : undefined

    return {
        projectCode: definition.projectCode,
        eventCode,
        taskName: override?.taskName ?? definition.taskName,
        batchSize: override?.batchSize ?? definition.batchSize,
        keepIntakeOpen:
            override?.keepIntakeOpen ?? definition.keepIntakeOpen,
        maxRuntimeSeconds:
            override?.maxRuntimeSeconds ?? definition.maxRuntimeSeconds,
        sharedConfig: {
            ...definition.sharedConfig,
            ...(override?.sharedConfig ?? {}),
        },
        items: cloneItems(override?.items ?? definition.items),
        guidance: [
            ...definition.guidance,
            ...(override?.guidance ?? []),
        ],
    }
}

export function stringifyStarterItems(
    items: Array<Record<string, unknown>>,
): string {
    return items.map((item) => JSON.stringify(item)).join('\n')
}

export function stringifyStarterSharedConfig(
    sharedConfig: Record<string, unknown>,
): string {
    return JSON.stringify(sharedConfig, null, 2)
}

function buildFallbackStarter(projectCode: string): TaskStarterDefinition {
    const resolvedProjectCode = projectCode || 'project'

    return {
        projectCode: resolvedProjectCode,
        taskName: `New ${resolvedProjectCode} task`,
        batchSize: 1,
        keepIntakeOpen: false,
        maxRuntimeSeconds: 0,
        sharedConfig: {},
        items: [{ target: 'alpha' }, { target: 'beta' }],
        guidance: [
            'This is a generic fallback starter. Replace sharedConfig and items with the real project contract before creating the task.',
        ],
    }
}

function cloneItems(
    items: Array<Record<string, unknown>>,
): Array<Record<string, unknown>> {
    return items.map((item) => ({ ...item }))
}
