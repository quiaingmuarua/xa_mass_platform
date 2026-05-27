import {canAccessRoute} from '@/utils/permissions'
import type {AppRouteShell} from '@/types/routes'
import type {AppRouteRecordRaw} from '@/router/types'

export function routeBelongsToShell(
    route: AppRouteRecordRaw,
    shell: AppRouteShell,
): boolean {
    return route.meta.shell === shell
}

export function isRouteVisibleInShell(
    route: AppRouteRecordRaw,
    shell: AppRouteShell,
): boolean {
    return (
        routeBelongsToShell(route, shell) &&
        !route.meta.hidden &&
        route.meta.menuVisible &&
        canAccessRoute(route.meta)
    )
}
