import {getAppConfig} from '@/app/config'
import {requestApiData} from '@/api/http'

export interface ConfigSummary {
    key: string
    value: string
}

export async function listProjectCodes(): Promise<string[]> {
    if (getAppConfig().useMockApi) {
        return ['demoApp', 'crawlerApp', 'testApp']
    }

    return requestApiData<string[]>('/api/config/projects')
}
