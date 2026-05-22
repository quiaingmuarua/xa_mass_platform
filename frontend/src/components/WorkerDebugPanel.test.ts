import ElementPlus from 'element-plus'
import {flushPromises, mount} from '@vue/test-utils'
import {mockAdminUser, mockViewerUser} from '@/auth/mock-user'
import {setMockCurrentUser} from '@/auth/use-auth'
import WorkerDebugPanel from '@/components/WorkerDebugPanel.vue'

const invokeSyncTaskDebug = vi.hoisted(() => vi.fn())

vi.mock('@/api/tasks', () => ({
    invokeSyncTaskDebug,
}))

describe('WorkerDebugPanel', () => {
    const worker = {
        workerId: 'worker-us-01',
        status: 'ONLINE',
        workerGroupId: null,
        agentVersion: '1.0.0',
        supportedProjects: ['demoApp'],
        supportedEventCodes: ['mock.state.get'],
        attributes: {},
        lastHeartbeat: '2026-04-21 09:45:00',
        locked: false,
        updateTime: '2026-04-21 09:45:00',
    }

    beforeEach(() => {
        invokeSyncTaskDebug.mockReset()
    })

    it('submits a targeted task instead of a direct worker message', async () => {
        setMockCurrentUser(mockAdminUser)
        invokeSyncTaskDebug.mockResolvedValue({
            taskId: 'task-debug-001',
            messageId: 'msg-debug-001',
            synced: true,
            timedOut: false,
            status: 'SUCCESS',
        })

        const wrapper = mount(WorkerDebugPanel, {
            props: {
                worker,
                projectOptions: ['demoApp'],
            },
            global: {
                plugins: [ElementPlus],
            },
        })

        await flushPromises()

        const submitButton = wrapper
            .findAll('button')
            .find((item) => item.text().includes('Create targeted task'))
        expect(submitButton).toBeTruthy()

        await submitButton!.trigger('click')
        await flushPromises()

        expect(invokeSyncTaskDebug).toHaveBeenCalledWith({
            userId: mockAdminUser.id,
            project: 'demoApp',
            eventCode: 'mock.state.get',
            sharedConfig: {
                targetWorkerId: 'worker-us-01',
            },
            items: [
                {
                    includeRuntime: true,
                },
            ],
            maxRuntimeSeconds: 60,
        })
        expect(wrapper.text()).toContain('task-debug-001')
    })

    it('disables task submission for users without task:create permission', async () => {
        setMockCurrentUser(mockViewerUser)

        const wrapper = mount(WorkerDebugPanel, {
            props: {
                worker,
                projectOptions: ['demoApp'],
            },
            global: {
                plugins: [ElementPlus],
            },
        })

        await flushPromises()

        expect(wrapper.text()).toContain('task:create permission is required')
        const submitButton = wrapper
            .findAll('button')
            .find((item) => item.text().includes('Create targeted task'))
        expect(submitButton?.attributes('disabled')).toBeDefined()
    })
})
