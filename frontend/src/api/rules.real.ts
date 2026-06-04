import {requestApiData} from '@/api/http'
import type {RuleListResponse, RuleMetaResponse} from '@/types/rules'

export async function listRulesReal(): Promise<RuleListResponse> {
    return requestApiData<RuleListResponse>('/api/v1/admin/rules')
}

export async function getRuleMetaReal(): Promise<RuleMetaResponse> {
    return requestApiData<RuleMetaResponse>('/api/v1/admin/rules/meta')
}
