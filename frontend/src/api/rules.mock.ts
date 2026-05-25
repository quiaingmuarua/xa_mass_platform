import type {RuleListResponse, RuleMetaResponse} from '@/types/rules'

const mockRules: RuleListResponse = {
    items: [
        {
            ruleId: 'rule-worker-online',
            name: 'Online workers only',
            type: 'QL_EXPRESS',
            content: "worker.status == 'ONLINE'",
            description: 'Only dispatch work to workers with online status.',
            enabled: true,
            priority: 10,
        },
        {
            ruleId: 'rule-region-routing',
            name: 'Worker attribute routing',
            type: 'QL_EXPRESS',
            content: "worker.attributes['routingTags'] != null",
            description: 'Use worker-level scheduling attributes without relying on retired context pools.',
            enabled: true,
            priority: 20,
        },
    ],
    total: 2,
}

const mockRuleMeta: RuleMetaResponse = {
    ruleTypes: ['QL_EXPRESS', 'JSON_DSL'],
    registeredEvaluatorTypes: ['QL_EXPRESS'],
}

function delay<T>(value: T): Promise<T> {
    return new Promise((resolve) => {
        window.setTimeout(() => resolve(value), 80)
    })
}

export async function listRulesMock(): Promise<RuleListResponse> {
    return delay(mockRules)
}

export async function getRuleMetaMock(): Promise<RuleMetaResponse> {
    return delay(mockRuleMeta)
}
