import {canAccessRoute} from '@/utils/permissions'
import type {AppRouteRecordRaw, MenuItem} from '@/router/types'

function joinPaths(parentPath: string, routePath: string): string {
    if (routePath.startsWith('/')) {
        return routePath
    }

    const base = parentPath.endsWith('/') ? parentPath.slice(0, -1) : parentPath
    return `${base}/${routePath}`.replace(/\/+/g, '/')
}

export function buildMenuTree(
    routes: AppRouteRecordRaw[],
    parentPath = '',
): MenuItem[] {
    return routes
        .slice()
        .sort((left, right) => left.meta.order - right.meta.order)
        .flatMap((route) => {
            const fullPath = joinPaths(parentPath || '/', route.path)
            const children = route.children
                ? buildMenuTree(route.children, fullPath)
                : []
            const shouldShow =
                !route.meta.hidden &&
                route.meta.menuVisible &&
                canAccessRoute(route.meta)

            if (!shouldShow) {
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
