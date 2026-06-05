import {getAppConfig} from '@/app/config'
import {requestApiData} from '@/api/http'
import {
    createRoleMock,
    getRoleMock,
    listPermissionsMock,
    listRolesMock,
    updateRoleMock,
} from '@/api/roles.mock'

export interface RoleRecord {
    roleId: string
    name: string
    description: string
    permissions: string[]
    systemRole: boolean
    updatedAt: string
}

export interface RoleCreateRequest {
    roleId: string
    name: string
    description?: string | null
    permissions: string[]
}

export interface RoleUpdateRequest {
    name?: string
    description?: string | null
    permissions?: string[]
}

export async function listRoles(): Promise<RoleRecord[]> {
    if (getAppConfig().useMockApi) {
        return listRolesMock()
    }
    return requestApiData<RoleRecord[]>('/api/v1/roles')
}

export async function getRole(roleId: string): Promise<RoleRecord> {
    if (getAppConfig().useMockApi) {
        return getRoleMock(roleId)
    }
    return requestApiData<RoleRecord>(
        `/api/v1/roles/${encodeURIComponent(roleId)}`,
    )
}

export async function listPermissions(): Promise<string[]> {
    if (getAppConfig().useMockApi) {
        return listPermissionsMock()
    }
    return requestApiData<string[]>('/api/v1/permissions')
}

export async function createRole(
    request: RoleCreateRequest,
): Promise<RoleRecord> {
    if (getAppConfig().useMockApi) {
        return createRoleMock(request)
    }
    return requestApiData<RoleRecord>('/api/v1/roles', {
        method: 'POST',
        body: JSON.stringify(request),
    })
}

export async function updateRole(
    roleId: string,
    request: RoleUpdateRequest,
): Promise<RoleRecord> {
    if (getAppConfig().useMockApi) {
        return updateRoleMock(roleId, request)
    }
    return requestApiData<RoleRecord>(`/api/v1/roles/${encodeURIComponent(roleId)}`, {
        method: 'PATCH',
        body: JSON.stringify(request),
    })
}
