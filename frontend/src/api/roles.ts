import {requestApiData} from '@/api/http'

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
    return requestApiData<RoleRecord[]>('/api/v1/roles')
}

export async function getRole(roleId: string): Promise<RoleRecord> {
    return requestApiData<RoleRecord>(
        `/api/v1/roles/${encodeURIComponent(roleId)}`,
    )
}

export async function listPermissions(): Promise<string[]> {
    return requestApiData<string[]>('/api/v1/permissions')
}

export async function createRole(
    request: RoleCreateRequest,
): Promise<RoleRecord> {
    return requestApiData<RoleRecord>('/api/v1/roles', {
        method: 'POST',
        body: JSON.stringify(request),
    })
}

export async function updateRole(
    roleId: string,
    request: RoleUpdateRequest,
): Promise<RoleRecord> {
    return requestApiData<RoleRecord>(`/api/v1/roles/${encodeURIComponent(roleId)}`, {
        method: 'PATCH',
        body: JSON.stringify(request),
    })
}
