export interface TaskStarterDraft {
    projectCode: string
    eventCode?: string
    taskName: string
    batchSize: number
    defaultMsgMaxRetryCount: number
    openEnded: boolean
    maxRuntimeSeconds: number
    sharedConfig: Record<string, unknown>
    inputs: Array<Record<string, unknown>>
    guidance: string[]
}

interface TaskStarterOverride {
    taskName?: string
    batchSize?: number
    defaultMsgMaxRetryCount?: number
    openEnded?: boolean
    maxRuntimeSeconds?: number
    sharedConfig?: Record<string, unknown>
    inputs?: Array<Record<string, unknown>>
    guidance?: string[]
}

interface TaskStarterDefinition extends TaskStarterDraft {
    eventOverrides?: Record<string, TaskStarterOverride>
}

const taskStarterDefinitions: Record<string, TaskStarterDefinition> = {
    demoApp: {
        projectCode: 'demoApp',
        taskName: 'Run demo dispatch task',
        batchSize: 1,
        defaultMsgMaxRetryCount: 3,
        openEnded: false,
        maxRuntimeSeconds: 0,
        sharedConfig: {
            textContent: 'hello from control console',
            objective: 'verify dispatch path',
        },
        inputs: [
            { target: 'demo-target-001' },
            { target: 'demo-target-002' },
        ],
        guidance: [
            'Use Task.sharedConfig only for task-level shared payload or options.',
            'Keep per-work-item identity and execution hints inside inputs.',
        ],
        eventOverrides: {
            'demo.dispatch.run': {
                taskName: 'Run demo dispatch',
                sharedConfig: {
                    textContent: 'hello from demo.dispatch.run',
                    objective: 'run generic dispatch payload',
                },
                inputs: [
                    { target: 'demo-target-001', recipient: 'alpha' },
                    { target: 'demo-target-002', recipient: 'beta' },
                ],
            },
            'demo.dispatch.audit': {
                taskName: 'Audit demo dispatch',
                sharedConfig: {
                    auditMode: 'summary',
                    expectedEvent: 'demo.dispatch.run',
                },
                inputs: [
                    {
                        target: 'demo-target-001',
                        previousMessageId: 'msg-001',
                        expectedStatus: 'SUCCESS',
                    },
                ],
            },
        },
    },
    testApp: {
        projectCode: 'testApp',
        taskName: 'Run smoke validation',
        batchSize: 1,
        defaultMsgMaxRetryCount: 1,
        openEnded: false,
        maxRuntimeSeconds: 60,
        sharedConfig: {
            textContent: 'smoke',
            objective: 'local validation',
        },
        inputs: [
            { target: 'smoke-target-001' },
            { target: 'smoke-target-002' },
        ],
        guidance: [
            'Use this starter for local or CI smoke validation against the verified test harness.',
        ],
        eventOverrides: {
            'test.smoke': {
                taskName: 'Run smoke event',
                inputs: [{ target: 'smoke-target-001' }],
            },
        },
    },
    otherApp: {
        projectCode: 'otherApp',
        taskName: 'Run other app dispatch task',
        batchSize: 1,
        defaultMsgMaxRetryCount: 3,
        openEnded: false,
        maxRuntimeSeconds: 0,
        sharedConfig: {
            objective: 'validate secondary project flow',
        },
        inputs: [{ target: 'other-target-001' }],
        guidance: [
            'Keep this starter generic unless the backend defines a stronger project contract.',
        ],
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
        defaultMsgMaxRetryCount:
            override?.defaultMsgMaxRetryCount ??
            definition.defaultMsgMaxRetryCount,
        openEnded: override?.openEnded ?? definition.openEnded,
        maxRuntimeSeconds:
            override?.maxRuntimeSeconds ?? definition.maxRuntimeSeconds,
        sharedConfig: {
            ...definition.sharedConfig,
            ...(override?.sharedConfig ?? {}),
        },
        inputs: cloneInputs(override?.inputs ?? definition.inputs),
        guidance: [
            ...definition.guidance,
            ...(override?.guidance ?? []),
        ],
    }
}

export function stringifyStarterInputs(
    inputs: Array<Record<string, unknown>>,
): string {
    return inputs.map((item) => JSON.stringify(item)).join('\n')
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
        defaultMsgMaxRetryCount: 3,
        openEnded: false,
        maxRuntimeSeconds: 0,
        sharedConfig: {},
        inputs: [{ target: 'alpha' }, { target: 'beta' }],
        guidance: [
            'This is a generic fallback starter. Replace sharedConfig and inputs with the real project contract before creating the task.',
        ],
    }
}

function cloneInputs(
    inputs: Array<Record<string, unknown>>,
): Array<Record<string, unknown>> {
    return inputs.map((item) => ({ ...item }))
}
