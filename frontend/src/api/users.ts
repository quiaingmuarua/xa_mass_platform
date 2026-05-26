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

export interface UserCreateRequest {
    userId: string
    displayName?: string
    email?: string | null
    status?: UserRecord['status']
    attributes?: Record<string, string>
}

export interface UserUpdateRequest {
    displayName?: string
    email?: string | null
    status?: UserRecord['status']
    attributes?: Record<string, string>
}

export interface UserRoleBindingRecord {
    userId: string
    roleId: string
    boundBy: string
    boundAt: string
}

export async function listUsers(): Promise<UserRecord[]> {
    return requestApiData<UserRecord[]>('/api/v1/users')
}

export async function getUser(userId: string): Promise<UserRecord> {
    return requestApiData<UserRecord>(
        `/api/v1/users/${encodeURIComponent(userId)}`,
    )
}

export async function createUser(
    request: UserCreateRequest,
): Promise<UserRecord> {
    return requestApiData<UserRecord>('/api/v1/users', {
        method: 'POST',
        body: JSON.stringify(request),
    })
}

export async function updateUser(
    userId: string,
    request: UserUpdateRequest,
): Promise<UserRecord> {
    return requestApiData<UserRecord>(`/api/v1/users/${encodeURIComponent(userId)}`, {
        method: 'PATCH',
        body: JSON.stringify(request),
    })
}

export async function bindUserRole(
    userId: string,
    roleId: string,
): Promise<UserRoleBindingRecord> {
    return requestApiData<UserRoleBindingRecord>(
        `/api/v1/users/${encodeURIComponent(userId)}/roles/${encodeURIComponent(roleId)}`,
        {
            method: 'POST',
        },
    )
}

export async function unbindUserRole(
    userId: string,
    roleId: string,
): Promise<{ userId: string; roleId: string; removed: boolean }> {
    return requestApiData<{ userId: string; roleId: string; removed: boolean }>(
        `/api/v1/users/${encodeURIComponent(userId)}/roles/${encodeURIComponent(roleId)}`,
        {
            method: 'DELETE',
        },
    )
}
