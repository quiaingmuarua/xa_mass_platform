import { getWorkerDebugHistory, sendWorkerDebugMessage } from '@/api/workers'

describe('workers API facade', () => {
    it('records manual debug messages through the mock adapter', async () => {
        const historyBefore = await getWorkerDebugHistory('worker-us-01')
        expect(historyBefore.items).toHaveLength(0)

        const result = await sendWorkerDebugMessage({
            workerId: 'worker-us-01',
            project: 'demoApp',
            event: 'mock.state.get',
            payload: {
                text: 'hello worker',
            },
        })

        expect(result.workerId).toBe('worker-us-01')
        expect(result.event).toBe('mock.state.get')
        expect(result.requestId).toContain('mock-request-')

        const historyAfter = await getWorkerDebugHistory('worker-us-01')
        expect(historyAfter.items).toHaveLength(2)
        expect(historyAfter.items[0].direction).toBe('OUTBOUND')
        expect(historyAfter.items[0].status).toBe('DELIVERED')
        expect(historyAfter.items[0].subMsgType).toBe('event')
        expect(historyAfter.items[1].direction).toBe('INBOUND')
        expect(historyAfter.items[1].replyToMessageId).toBe(result.messageId)
    })
})
