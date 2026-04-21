import type { AppRouteMeta } from '@/types/routes'
import { useAuth } from '@/auth/use-auth'

export function hasPermission(permission: string): boolean {
    const { user } = useAuth()
    const permissions = user.value?.permissions ?? []
    return permissions.includes(permission)
}

export function hasAnyPermission(permissions: string[]): boolean {
    if (permissions.length === 0) {
        return true
    }

    return permissions.some((permission) => hasPermission(permission))
}

export function canAccessRoute(
    meta: Partial<AppRouteMeta> | undefined,
): boolean {
    if (!meta) {
        return true
    }

    const { isAuthenticated } = useAuth()
    const requiresAuth = meta.requiresAuth ?? true
    const permissions = meta.permissions ?? []

    if (requiresAuth && !isAuthenticated.value) {
        return false
    }

    if (permissions.length === 0) {
        return true
    }

    return hasAnyPermission(permissions)
}
