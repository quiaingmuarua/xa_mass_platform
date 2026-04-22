import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { mockAdminUser } from '@/auth/mock-user'
import { setMockCurrentUser } from '@/auth/use-auth'
import TasksListPage from '@/pages/tasks/TasksListPage.vue'
import { permissionDirective } from '@/auth/permission-directive'

describe('TasksListPage', () => {
    it('loads mock tasks and renders the list', async () => {
        setMockCurrentUser(mockAdminUser)

        const router = createRouter({
            history: createMemoryHistory(),
            routes: [{ path: '/', component: TasksListPage }],
        })

        await router.push('/')
        await router.isReady()

        const wrapper = mount(TasksListPage, {
            global: {
                plugins: [router, ElementPlus],
                directives: {
                    permission: permissionDirective,
                },
            },
        })

        await flushPromises()
        await new Promise((resolve) => window.setTimeout(resolve, 100))
        await flushPromises()

        expect(wrapper.text()).toContain('Warm worker pool for us-routing')
        expect(wrapper.text()).toContain('Review failed delivery backlog')
    })

    it('opens a starter task draft from route query context', async () => {
        setMockCurrentUser(mockAdminUser)

        const router = createRouter({
            history: createMemoryHistory(),
            routes: [{ path: '/', component: TasksListPage }],
        })

        await router.push(
            '/?create=1&project=demoApp&taskName=Send%20demo%20message&eventCode=demo.message.send',
        )
        await router.isReady()

        const wrapper = mount(TasksListPage, {
            global: {
                plugins: [router, ElementPlus],
                directives: {
                    permission: permissionDirective,
                },
                stubs: {
                    teleport: true,
                },
            },
        })

        await flushPromises()
        await new Promise((resolve) => window.setTimeout(resolve, 100))
        await flushPromises()

        const setupState = (
            wrapper.vm.$ as unknown as {
                setupState: {
                    createDialogVisible: boolean
                    starterEventCode: string
                    createForm: {
                        taskName: string
                        project: string
                        routingCode: string
                        batchSize: number
                        sharedConfigText: string
                        inputsText: string
                    }
                    starterGuidance: string[]
                }
            }
        ).setupState

        expect(setupState.createDialogVisible).toBe(true)
        expect(setupState.starterEventCode).toBe('demo.message.send')
        expect(setupState.createForm.taskName).toBe('Send demo message')
        expect(setupState.createForm.project).toBe('demoApp')
        expect(setupState.createForm.routingCode).toBe('us')
        expect(setupState.createForm.batchSize).toBe(1)
        expect(setupState.createForm.sharedConfigText).toContain(
            'hello from demo.message.send',
        )
        expect(setupState.createForm.inputsText).toContain('"recipient":"alpha"')
        expect(setupState.starterGuidance.length).toBeGreaterThan(0)
    })
})
