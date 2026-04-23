import {getAppConfig} from '@/app/config'
import {getRuleMetaMock, listRulesMock} from '@/api/rules.mock'
import {getRuleMetaReal, listRulesReal} from '@/api/rules.real'
import type {RuleListResponse, RuleMetaResponse} from '@/types/rules'

export async function listRules(): Promise<RuleListResponse> {
    if (getAppConfig().useMockApi) {
        return listRulesMock()
    }

    return listRulesReal()
}

export async function getRuleMeta(): Promise<RuleMetaResponse> {
    if (getAppConfig().useMockApi) {
        return getRuleMetaMock()
    }

    return getRuleMetaReal()
}
