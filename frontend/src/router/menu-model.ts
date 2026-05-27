import {isRouteVisibleInShell} from '@/router/visible-routes'
import type {AppRouteShell} from '@/types/routes'
import type {AppRouteRecordRaw, MenuItem} from '@/router/types'

function joinPaths(parentPath: string, routePath: string): string {
    if (routePath.startsWith('/')) {
        return routePath
    }

    const base = parentPath.endsWith('/') ? parentPath.slice(0, -1) : parentPath
    return `${base}/${routePath}`.replace(/\/+/g, '/')
}

export function buildMenuModel(
    routes: AppRouteRecordRaw[],
    shell: AppRouteShell,
    parentPath = '',
): MenuItem[] {
    return routes
        .slice()
        .sort((left, right) => left.meta.order - right.meta.order)
        .flatMap((route) => {
            const fullPath = joinPaths(parentPath || '/', route.path)
            const children = route.children
                ? buildMenuModel(route.children, shell, fullPath)
                : []

            if (!isRouteVisibleInShell(route, shell)) {
                return children
            }

            return [
                {
                    path: fullPath,
                    title: route.meta.title,
                    icon: route.meta.icon,
                    children,
                },
            ]
        })
}
