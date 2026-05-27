import type {AppRouteRecordRaw} from '@/router/types'

export const runtimeRoutes: AppRouteRecordRaw[] = [
    {
        path: 'runtime',
        component: () => import('@/layouts/RouteSectionView.vue'),
        meta: {
            shell: 'operator',
            navGroup: 'runtime',
            title: 'Runtime',
            icon: 'Compass',
            order: 40,
            hidden: false,
            keepAlive: false,
            requiresAuth: true,
            permissions: ['task:view'],
            menuVisible: true,
        },
        children: [
            {
                path: 'discovery',
                name: 'runtime-discovery',
                component: () =>
                    import('@/pages/runtime/RuntimeDiscoveryPage.vue'),
                meta: {
                    shell: 'operator',
                    navGroup: 'runtime',
                    title: 'Discovery',
                    icon: 'Tickets',
                    order: 41,
                    hidden: false,
                    keepAlive: true,
                    requiresAuth: true,
                    permissions: ['worker:view'],
                    menuVisible: true,
                },
            },
        ],
    },
]
