import {getAppConfig} from '@/app/config'
import {getCurrentSdkSubmitterMock} from '@/api/sdk-submitter.mock'
import {getCurrentSdkSubmitterReal} from '@/api/sdk-submitter.real'
import type {SdkSubmitterSnapshot} from '@/types/sdk-submitter'

export async function getCurrentSdkSubmitter(): Promise<SdkSubmitterSnapshot> {
    if (getAppConfig().useMockApi) {
        return getCurrentSdkSubmitterMock()
    }

    return getCurrentSdkSubmitterReal()
}
