import {getAppConfig} from '@/app/config'
import {getCurrentApiKeyMock} from '@/api/current-api-key.mock'
import {getCurrentApiKeyReal} from '@/api/current-api-key.real'
import type {CurrentApiKeySnapshot} from '@/types/current-api-key'

export async function getCurrentApiKey(): Promise<CurrentApiKeySnapshot> {
    if (getAppConfig().useMockApi) {
        return getCurrentApiKeyMock()
    }

    return getCurrentApiKeyReal()
}
