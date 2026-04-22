export interface TaskStarterDraft {
    projectCode: string
    eventCode?: string
    taskName: string
    routingCode: string
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
    routingCode?: string
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
        taskName: 'Warm demo dispatch lane',
        routingCode: 'us',
        batchSize: 1,
        defaultMsgMaxRetryCount: 3,
        openEnded: false,
        maxRuntimeSeconds: 0,
        sharedConfig: {
            textContent: 'hello from control console',
            channel: 'demo',
        },
        inputs: [
            { target: 'demo-target-001' },
            { target: 'demo-target-002' },
        ],
        guidance: [
            'Use Task.sharedConfig for common dispatch payload such as text or channel.',
            'Keep per-work-item identifiers inside inputs; do not reintroduce targetList.',
        ],
        eventOverrides: {
            'demo.message.send': {
                taskName: 'Send demo message',
                sharedConfig: {
                    textContent: 'hello from demo.message.send',
                    channel: 'demo',
                },
                inputs: [
                    { target: 'demo-target-001', recipient: 'alpha' },
                    { target: 'demo-target-002', recipient: 'beta' },
                ],
                guidance: [
                    'This starter assumes a send-style event; keep recipient identity in each input.',
                ],
            },
            'demo.message.audit': {
                taskName: 'Audit demo message',
                sharedConfig: {
                    auditMode: 'summary',
                    expectedEvent: 'demo.message.send',
                },
                inputs: [
                    {
                        target: 'demo-target-001',
                        previousMessageId: 'msg-001',
                        expectedStatus: 'SUCCESS',
                    },
                ],
                guidance: [
                    'Audit-style tasks should point at prior execution artifacts through input fields.',
                ],
            },
        },
    },
    telegramApp: {
        projectCode: 'telegramApp',
        taskName: 'Send Telegram message',
        routingCode: 'sg',
        batchSize: 1,
        defaultMsgMaxRetryCount: 3,
        openEnded: false,
        maxRuntimeSeconds: 300,
        sharedConfig: {
            channel: 'telegram',
            textContent: 'hello from telegram starter',
        },
        inputs: [
            { target: 'chat-001', chatId: 'chat-001' },
        ],
        guidance: [
            'Telegram tasks usually carry channel metadata in sharedConfig and chat/session identifiers in inputs.',
        ],
        eventOverrides: {
            'telegram.message.send': {
                taskName: 'Send Telegram message',
                sharedConfig: {
                    channel: 'telegram',
                    textContent: 'hello from telegram.message.send',
                },
                inputs: [
                    {
                        target: 'chat-001',
                        chatId: 'chat-001',
                        parseMode: 'Markdown',
                    },
                ],
            },
            'telegram.session.refresh': {
                taskName: 'Refresh Telegram session',
                sharedConfig: {
                    channel: 'telegram',
                    operation: 'session-refresh',
                },
                inputs: [
                    {
                        target: 'session-001',
                        sessionId: 'session-001',
                        account: 'ops-sg-a',
                    },
                ],
                guidance: [
                    'Session refresh tasks are usually single-item operational tasks tied to one context or account.',
                ],
            },
        },
    },
    testApp: {
        projectCode: 'testApp',
        taskName: 'Run smoke validation',
        routingCode: 'local',
        batchSize: 1,
        defaultMsgMaxRetryCount: 1,
        openEnded: false,
        maxRuntimeSeconds: 60,
        sharedConfig: {
            textContent: 'smoke',
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
        routingCode: override?.routingCode ?? definition.routingCode,
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
        routingCode: '',
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
