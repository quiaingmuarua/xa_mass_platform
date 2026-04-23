import type {AuthUser} from '@/types/auth'

export const allPermissions = [
    'task:view',
    'task:create',
    'task:edit',
    'task:approve',
    'task:pause',
    'task:resume',
    'task:terminate',
    'worker:view',
    'worker:edit',
    'rule:view',
    'rule:edit',
    'config:view',
    'config:edit',
    'user:view',
    'user:edit',
    'role:view',
    'role:edit',
    'audit:view',
] as const

export const mockAdminUser: AuthUser = {
    id: 'ops-admin-001',
    name: 'Ops Admin',
    email: 'ops-admin@example.internal',
    roles: ['OPS_ADMIN'],
    permissions: [...allPermissions],
}

export const mockViewerUser: AuthUser = {
    id: 'ops-viewer-001',
    name: 'Ops Viewer',
    email: 'ops-viewer@example.internal',
    roles: ['OPS_VIEWER'],
    permissions: [
        'task:view',
        'worker:view',
        'rule:view',
        'config:view',
        'audit:view',
    ],
}
