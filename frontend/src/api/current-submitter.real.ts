import {ApiError, requestApiData} from '@/api/http'
import type {CurrentSubmitterProfile, CurrentSubmitterSnapshot,} from '@/types/current-submitter'

export async function getCurrentSubmitterReal(): Promise<CurrentSubmitterSnapshot> {
    try {
        const profile = await requestApiData<CurrentSubmitterProfile>('/api/v1/submitters/me')
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
