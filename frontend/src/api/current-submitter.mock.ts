import type {CurrentSubmitterSnapshot} from '@/types/current-submitter'

export async function getCurrentSubmitterMock(): Promise<CurrentSubmitterSnapshot> {
    return {
        state: 'unavailable',
        profile: null,
    }
}
