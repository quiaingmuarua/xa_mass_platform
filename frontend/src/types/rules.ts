export interface RuleListItem {
    ruleId: string
    name: string | null
    type: string | null
    content: string | null
    description: string | null
    enabled: boolean
    priority: number
}

export interface RuleListResponse {
    items: RuleListItem[]
    total: number
}

export interface RuleMetaResponse {
    ruleTypes: string[]
    registeredEvaluatorTypes: string[]
}
