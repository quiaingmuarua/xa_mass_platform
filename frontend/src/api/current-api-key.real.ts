import {ApiError, requestApiData} from '@/api/http'
import type {CurrentApiKeyProfile, CurrentApiKeySnapshot,} from '@/types/current-api-key'

export async function getCurrentApiKeyReal(): Promise<CurrentApiKeySnapshot> {
    try {
        const profile = await requestApiData<CurrentApiKeyProfile>('/api/v1/api-keys:current')
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
