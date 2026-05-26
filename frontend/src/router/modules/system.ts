import type {AppRouteRecordRaw} from '@/router/types'

export const systemRoutes: AppRouteRecordRaw[] = [
    {
        path: 'system',
        component: () => import('@/layouts/RouteSectionView.vue'),
        meta: {
            title: 'System',
            icon: 'Setting',
            order: 50,
            hidden: false,
            keepAlive: false,
            requiresAuth: true,
            permissions: [],
            menuVisible: true,
        },
        children: [
            {
                path: 'users',
                name: 'users',
                component: () => import('@/pages/system/users/UsersPage.vue'),
                meta: {
                    title: 'Users',
                    icon: 'User',
                    order: 51,
                    hidden: false,
                    keepAlive: true,
                    requiresAuth: true,
                    permissions: ['user:view'],
                    menuVisible: true,
                },
            },
            {
                path: 'roles',
                name: 'roles',
                component: () => import('@/pages/system/roles/RolesPage.vue'),
                meta: {
                    title: 'Roles',
                    icon: 'Avatar',
                    order: 52,
                    hidden: false,
                    keepAlive: true,
                    requiresAuth: true,
                    permissions: ['role:view'],
                    menuVisible: true,
                },
            },
            {
                path: 'api-keys',
                name: 'api-keys',
                component: () =>
                    import('@/pages/system/api-keys/ApiKeysPage.vue'),
                meta: {
                    title: 'API Keys',
                    icon: 'Tickets',
                    order: 53,
                    hidden: false,
                    keepAlive: true,
                    requiresAuth: true,
                    permissions: ['api-key:view'],
                    menuVisible: true,
                },
            },
            {
                path: 'audit',
                name: 'audit',
                component: () => import('@/pages/system/audit/AuditPage.vue'),
                meta: {
                    title: 'Audit Logs',
                    icon: 'Notebook',
                    order: 54,
                    hidden: false,
                    keepAlive: true,
                    requiresAuth: true,
                    permissions: ['audit:view'],
                    menuVisible: true,
                },
            },
        ],
    },
]
