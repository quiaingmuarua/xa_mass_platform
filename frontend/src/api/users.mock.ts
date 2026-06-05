import type {
    UserCreateRequest,
    UserRecord,
    UserRoleBindingRecord,
    UserUpdateRequest,
} from '@/api/users'

const mockUsers: UserRecord[] = [
    {
        userId: 'ops-admin',
        displayName: 'Ops Admin',
        email: 'ops-admin@example.internal',
        status: 'ACTIVE',
        attributes: {
            source: 'mock-preview',
        },
        createdAt: '2026-06-05T00:00:00Z',
        updatedAt: '2026-06-05T00:00:00Z',
    },
    {
        userId: 'ops-viewer',
        displayName: 'Ops Viewer',
        email: 'ops-viewer@example.internal',
        status: 'ACTIVE',
        attributes: {
            source: 'mock-preview',
        },
        createdAt: '2026-06-05T00:00:00Z',
        updatedAt: '2026-06-05T00:00:00Z',
    },
    {
        userId: 'crawler-operator',
        displayName: 'Crawler Operator',
        email: 'crawler-operator@example.internal',
        status: 'DISABLED',
        attributes: {
            source: 'mock-preview',
        },
        createdAt: '2026-06-05T00:00:00Z',
        updatedAt: '2026-06-05T00:00:00Z',
    },
]

const mockBindings: UserRoleBindingRecord[] = [
    {
        userId: 'ops-admin',
        roleId: 'OPS_ADMIN',
        boundBy: 'mock-preview',
        boundAt: '2026-06-05T00:00:00Z',
    },
    {
        userId: 'ops-viewer',
        roleId: 'OPS_VIEWER',
        boundBy: 'mock-preview',
        boundAt: '2026-06-05T00:00:00Z',
    },
]

function delay<T>(value: T): Promise<T> {
    return new Promise((resolve) => {
        window.setTimeout(() => resolve(value), 80)
    })
}

function timestamp(): string {
    return new Date().toISOString()
}

function cloneUser(user: UserRecord): UserRecord {
    return {
        ...user,
        attributes: {...user.attributes},
    }
}

export async function listUsersMock(): Promise<UserRecord[]> {
    return delay(mockUsers.map(cloneUser))
}

export async function getUserMock(userId: string): Promise<UserRecord> {
    const user = mockUsers.find((item) => item.userId === userId)
    if (!user) {
        throw new Error(`Mock user not found: ${userId}`)
    }
    return delay(cloneUser(user))
}

export async function createUserMock(
    request: UserCreateRequest,
): Promise<UserRecord> {
    const now = timestamp()
    const user: UserRecord = {
        userId: request.userId,
        displayName: request.displayName ?? request.userId,
        email: request.email ?? '',
        status: request.status ?? 'ACTIVE',
        attributes: request.attributes ?? {},
        createdAt: now,
        updatedAt: now,
    }
    mockUsers.unshift(user)
    return delay(cloneUser(user))
}

export async function updateUserMock(
    userId: string,
    request: UserUpdateRequest,
): Promise<UserRecord> {
    const user = mockUsers.find((item) => item.userId === userId)
    if (!user) {
        throw new Error(`Mock user not found: ${userId}`)
    }
    user.displayName = request.displayName ?? user.displayName
    user.email = request.email ?? user.email
    user.status = request.status ?? user.status
    user.attributes = request.attributes ?? user.attributes
    user.updatedAt = timestamp()
    return delay(cloneUser(user))
}

export async function bindUserRoleMock(
    userId: string,
    roleId: string,
): Promise<UserRoleBindingRecord> {
    const existing = mockBindings.find(
        (item) => item.userId === userId && item.roleId === roleId,
    )
    if (existing) {
        return delay({...existing})
    }
    const binding: UserRoleBindingRecord = {
        userId,
        roleId,
        boundBy: 'mock-preview',
        boundAt: timestamp(),
    }
    mockBindings.push(binding)
    return delay({...binding})
}

export async function unbindUserRoleMock(
    userId: string,
    roleId: string,
): Promise<{ userId: string; roleId: string; removed: boolean }> {
    const index = mockBindings.findIndex(
        (item) => item.userId === userId && item.roleId === roleId,
    )
    if (index >= 0) {
        mockBindings.splice(index, 1)
    }
    return delay({userId, roleId, removed: index >= 0})
}
