import {allPermissions} from '@/auth/mock-user'
import type {
    RoleCreateRequest,
    RoleRecord,
    RoleUpdateRequest,
} from '@/api/roles'

const mockRoles: RoleRecord[] = [
    {
        roleId: 'OPS_ADMIN',
        name: 'Ops Admin',
        description: 'Full operator access for platform review and control.',
        permissions: [...allPermissions],
        systemRole: true,
        updatedAt: '2026-06-05T00:00:00Z',
    },
    {
        roleId: 'OPS_VIEWER',
        name: 'Ops Viewer',
        description: 'Read-only operational review access.',
        permissions: [
            'task:view',
            'worker:view',
            'rule:view',
            'config:view',
            'audit:view',
        ],
        systemRole: true,
        updatedAt: '2026-06-05T00:00:00Z',
    },
    {
        roleId: 'SDK_OPERATOR',
        name: 'SDK Operator',
        description: 'API-key lifecycle and task submission review access.',
        permissions: [
            'task:view',
            'task:create',
            'api-key:view',
            'api-key:apply',
            'api-usage:view',
        ],
        systemRole: false,
        updatedAt: '2026-06-05T00:00:00Z',
    },
]

function delay<T>(value: T): Promise<T> {
    return new Promise((resolve) => {
        window.setTimeout(() => resolve(value), 80)
    })
}

function cloneRole(role: RoleRecord): RoleRecord {
    return {
        ...role,
        permissions: [...role.permissions],
    }
}

function timestamp(): string {
    return new Date().toISOString()
}

export async function listRolesMock(): Promise<RoleRecord[]> {
    return delay(mockRoles.map(cloneRole))
}

export async function getRoleMock(roleId: string): Promise<RoleRecord> {
    const role = mockRoles.find((item) => item.roleId === roleId)
    if (!role) {
        throw new Error(`Mock role not found: ${roleId}`)
    }
    return delay(cloneRole(role))
}

export async function listPermissionsMock(): Promise<string[]> {
    return delay([...allPermissions])
}

export async function createRoleMock(
    request: RoleCreateRequest,
): Promise<RoleRecord> {
    const role: RoleRecord = {
        roleId: request.roleId,
        name: request.name,
        description: request.description ?? '',
        permissions: [...request.permissions],
        systemRole: false,
        updatedAt: timestamp(),
    }
    mockRoles.unshift(role)
    return delay(cloneRole(role))
}

export async function updateRoleMock(
    roleId: string,
    request: RoleUpdateRequest,
): Promise<RoleRecord> {
    const role = mockRoles.find((item) => item.roleId === roleId)
    if (!role) {
        throw new Error(`Mock role not found: ${roleId}`)
    }
    if (role.systemRole) {
        throw new Error('System roles are read-only in mock mode.')
    }
    role.name = request.name ?? role.name
    role.description = request.description ?? role.description
    role.permissions = request.permissions ? [...request.permissions] : role.permissions
    role.updatedAt = timestamp()
    return delay(cloneRole(role))
}
