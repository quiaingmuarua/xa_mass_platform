import {
    resolveTaskStarterDraft,
    stringifyStarterInputs,
    stringifyStarterSharedConfig,
} from '@/utils/task-starters'

describe('task starter drafts', () => {
    it('resolves an explicit event starter for known projects', () => {
        const draft = resolveTaskStarterDraft({
            projectCode: 'telegramApp',
            eventCode: 'telegram.session.refresh',
        })

        expect(draft.taskName).toBe('Refresh Telegram session')
        expect(draft.routingCode).toBe('sg')
        expect(draft.sharedConfig.operation).toBe('session-refresh')
        expect(draft.inputs[0].sessionId).toBe('session-001')
    })

    it('falls back to a generic starter for unknown projects', () => {
        const draft = resolveTaskStarterDraft({
            projectCode: 'customApp',
        })

        expect(draft.taskName).toBe('New customApp task')
        expect(draft.inputs).toHaveLength(2)
        expect(draft.guidance[0]).toContain('generic fallback starter')
    })

    it('formats starter JSON for the task create form', () => {
        const draft = resolveTaskStarterDraft({
            projectCode: 'demoApp',
            eventCode: 'demo.message.send',
        })

        expect(stringifyStarterSharedConfig(draft.sharedConfig)).toContain(
            'textContent',
        )
        expect(stringifyStarterInputs(draft.inputs)).toContain('recipient')
    })
})
