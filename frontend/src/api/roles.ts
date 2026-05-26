import {requestApiData} from '@/api/http'

export interface RoleRecord {
    roleId: string
    name: string
    description: string
    permissions: string[]
    systemRole: boolean
    updatedAt: string
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
