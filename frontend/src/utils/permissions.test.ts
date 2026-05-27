import type {AppRouteMeta} from '@/types/routes'
import {mockViewerUser} from '@/auth/mock-user'
import {resetMockAuth, setMockCurrentUser} from '@/auth/use-auth'
import {canAccessRoute, hasAnyPermission, hasPermission,} from '@/utils/permissions'

const baseMeta: AppRouteMeta = {
    shell: 'operator',
    navGroup: 'tasks',
    title: 'Tasks',
    icon: 'Tickets',
    order: 1,
    hidden: false,
    keepAlive: false,
    requiresAuth: true,
    permissions: ['task:view'],
    menuVisible: true,
}

describe('permission helpers', () => {
    afterEach(() => {
        resetMockAuth()
    })

    it('returns false when there is no current user', () => {
        expect(hasPermission('task:view')).toBe(false)
        expect(canAccessRoute(baseMeta)).toBe(false)
    })

    it('supports exact permission checks', () => {
        setMockCurrentUser(mockViewerUser)

        expect(hasPermission('task:view')).toBe(true)
        expect(hasPermission('task:control')).toBe(false)
    })

    it('supports any-permission checks', () => {
        setMockCurrentUser(mockViewerUser)

        expect(hasAnyPermission(['task:control', 'task:view'])).toBe(true)
        expect(hasAnyPermission(['task:control', 'task:govern'])).toBe(false)
    })

    it('allows routes without explicit permissions when authenticated', () => {
        setMockCurrentUser(mockViewerUser)

        expect(canAccessRoute({ ...baseMeta, permissions: [] })).toBe(true)
    })
})
