import {ApiError, requestApiData} from '@/api/http'
import type {SdkSubmitterProfile, SdkSubmitterSnapshot,} from '@/types/sdk-submitter'

export async function getCurrentSdkSubmitterReal(): Promise<SdkSubmitterSnapshot> {
    try {
        const profile = await requestApiData<SdkSubmitterProfile>('/api/v1/submitters/me')
        return {
            state: 'available',
            profile,
        }
    } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
            return {
                state: 'unauthorized',
                profile: null,
            }
        }
        if (error instanceof ApiError && error.status === 404) {
            return {
                state: 'unavailable',
                profile: null,
            }
        }
        throw error
    }
}
