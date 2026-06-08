import type {CurrentApiKeySnapshot} from '@/types/current-api-key'

export async function getCurrentApiKeyMock(): Promise<CurrentApiKeySnapshot> {
    return {
        state: 'unavailable',
        profile: null,
    }
}
