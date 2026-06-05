import {getAppConfig} from '@/app/config'
import {requestApiData} from '@/api/http'
import {
    bindUserRoleMock,
    createUserMock,
    getUserMock,
    listUsersMock,
    unbindUserRoleMock,
    updateUserMock,
} from '@/api/users.mock'

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
    if (getAppConfig().useMockApi) {
        return listUsersMock()
    }
    return requestApiData<UserRecord[]>('/api/v1/users')
}

export async function getUser(userId: string): Promise<UserRecord> {
    if (getAppConfig().useMockApi) {
        return getUserMock(userId)
    }
    return requestApiData<UserRecord>(
        `/api/v1/users/${encodeURIComponent(userId)}`,
    )
}

export async function createUser(
    request: UserCreateRequest,
): Promise<UserRecord> {
    if (getAppConfig().useMockApi) {
        return createUserMock(request)
    }
    return requestApiData<UserRecord>('/api/v1/users', {
        method: 'POST',
        body: JSON.stringify(request),
    })
}

export async function updateUser(
    userId: string,
    request: UserUpdateRequest,
): Promise<UserRecord> {
    if (getAppConfig().useMockApi) {
        return updateUserMock(userId, request)
    }
    return requestApiData<UserRecord>(`/api/v1/users/${encodeURIComponent(userId)}`, {
        method: 'PATCH',
        body: JSON.stringify(request),
    })
}

export async function bindUserRole(
    userId: string,
    roleId: string,
): Promise<UserRoleBindingRecord> {
    if (getAppConfig().useMockApi) {
        return bindUserRoleMock(userId, roleId)
    }
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
    if (getAppConfig().useMockApi) {
        return unbindUserRoleMock(userId, roleId)
    }
    return requestApiData<{ userId: string; roleId: string; removed: boolean }>(
        `/api/v1/users/${encodeURIComponent(userId)}/roles/${encodeURIComponent(roleId)}`,
        {
            method: 'DELETE',
        },
    )
}
