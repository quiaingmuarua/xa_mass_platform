import {resolveTaskStarterDraft, stringifyStarterItems, stringifyStarterSharedConfig,} from '@/utils/task-starters'

describe('task starter drafts', () => {
    it('resolves an explicit event starter for known projects', () => {
        const draft = resolveTaskStarterDraft({
            projectCode: 'publicProbe',
            eventCode: 'probe.url.dns',
        })

        expect(draft.taskName).toBe('Inspect URL DNS batch')
        expect(draft.sharedConfig.objective).toBe('classify DNS and URL reachability')
        expect(draft.items[0].expectedOutcome).toBe('DNS_OK')
    })

    it('falls back to a generic starter for unknown projects', () => {
        const draft = resolveTaskStarterDraft({
            projectCode: 'customApp',
        })

        expect(draft.taskName).toBe('New customApp task')
        expect(draft.items).toHaveLength(2)
        expect(draft.guidance[0]).toContain('generic fallback starter')
    })

    it('formats starter JSON for the task create form', () => {
        const draft = resolveTaskStarterDraft({
            projectCode: 'publicProbe',
            eventCode: 'probe.url.dns',
        })

        expect(stringifyStarterSharedConfig(draft.sharedConfig)).toContain(
            'control-console-realistic',
        )
        expect(stringifyStarterItems(draft.items)).toContain('traceLabel')
    })
})
