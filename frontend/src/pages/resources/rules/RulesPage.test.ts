import ElementPlus from 'element-plus'
import {flushPromises, mount} from '@vue/test-utils'
import {setRuntimeConfigOverrides} from '@/app/config'
import RulesPage from '@/pages/resources/rules/RulesPage.vue'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('RulesPage', () => {
    it('loads rules from the real API mode', async () => {
        setRuntimeConfigOverrides({ useMockApi: false })
        vi.stubGlobal(
            'fetch',
            vi.fn((input: string) => {
                if (input.includes('/meta')) {
                    return Promise.resolve(
                        jsonResponse({
                            code: 0,
                            msg: 'ok',
                            data: {
                                ruleTypes: ['QL_EXPRESS'],
                                registeredEvaluatorTypes: ['QL_EXPRESS'],
                            },
                        }),
                    )
                }

                return Promise.resolve(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: {
                            items: [
                                {
                                    ruleId: 'rule-worker-online',
                                    name: 'Online workers only',
                                    type: 'QL_EXPRESS',
                                    content: "worker.status == 'ONLINE'",
                                    description: 'Only online workers.',
                                    enabled: true,
                                    priority: 10,
                                },
                            ],
                            total: 1,
                        },
                    }),
                )
            }),
        )

        const wrapper = mount(RulesPage, {
            global: {
                plugins: [ElementPlus],
            },
        })

        await flushPromises()

        expect(wrapper.text()).toContain('Online workers only')
        expect(wrapper.text()).toContain('QL_EXPRESS')
        expect(wrapper.text()).toContain("worker.status == 'ONLINE'")
    })
})
