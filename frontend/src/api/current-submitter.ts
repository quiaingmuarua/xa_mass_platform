import {getAppConfig} from '@/app/config'
import {getCurrentSubmitterMock} from '@/api/current-submitter.mock'
import {getCurrentSubmitterReal} from '@/api/current-submitter.real'
import type {CurrentSubmitterSnapshot} from '@/types/current-submitter'

export async function getCurrentSubmitter(): Promise<CurrentSubmitterSnapshot> {
    if (getAppConfig().useMockApi) {
        return getCurrentSubmitterMock()
    }

    return getCurrentSubmitterReal()
}
