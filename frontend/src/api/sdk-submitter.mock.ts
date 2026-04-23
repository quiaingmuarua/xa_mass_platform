import type { SdkSubmitterSnapshot } from '@/types/sdk-submitter'

export async function getCurrentSdkSubmitterMock(): Promise<SdkSubmitterSnapshot> {
    return {
        state: 'unavailable',
        profile: null,
    }
}
