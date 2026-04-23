import {
    resolveTaskStarterDraft,
    stringifyStarterInputs,
    stringifyStarterSharedConfig,
} from '@/utils/task-starters'

describe('task starter drafts', () => {
    it('resolves an explicit event starter for known projects', () => {
        const draft = resolveTaskStarterDraft({
            projectCode: 'demoApp',
            eventCode: 'demo.dispatch',
        })

        expect(draft.taskName).toBe('Demo dispatch')
        expect(draft.sharedConfig.objective).toBe('run generic dispatch payload')
        expect(draft.inputs[0].recipient).toBe('alpha')
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
            eventCode: 'demo.dispatch',
        })

        expect(stringifyStarterSharedConfig(draft.sharedConfig)).toContain(
            'textContent',
        )
        expect(stringifyStarterInputs(draft.inputs)).toContain('recipient')
    })
})
