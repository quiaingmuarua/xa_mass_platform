export interface AppRouteMeta {
    title: string
    icon: string
    order: number
    hidden: boolean
    keepAlive: boolean
    requiresAuth: boolean
    permissions: string[]
    menuVisible: boolean
}
