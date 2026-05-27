export type AppRouteShell = 'operator' | 'submitter-viewer' | 'public'

export type AppRouteNavGroup =
    | 'dashboard'
    | 'resources'
    | 'tasks'
    | 'runtime'
    | 'system'
    | 'viewer'
    | 'public'

export interface AppRouteMeta {
    shell: AppRouteShell
    navGroup?: AppRouteNavGroup
    title: string
    icon: string
    order: number
    hidden: boolean
    keepAlive: boolean
    requiresAuth: boolean
    permissions: string[]
    menuVisible: boolean
}
