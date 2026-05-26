import {requestApiData} from '@/api/http'

export interface UserRecord {
    userId: string
    displayName: string
    email: string
    status: 'ACTIVE' | 'DISABLED' | 'DELETED'
    attributes: Record<string, string>
    createdAt: string
    updatedAt: string
}

export async function listUsers(): Promise<UserRecord[]> {
    return requestApiData<UserRecord[]>('/api/v1/users')
}

export async function getUser(userId: string): Promise<UserRecord> {
    return requestApiData<UserRecord>(
        `/api/v1/users/${encodeURIComponent(userId)}`,
    )
}
