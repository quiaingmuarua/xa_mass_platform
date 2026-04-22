import type { AppRouteRecordRaw } from '@/router/types'

export const runtimeRoutes: AppRouteRecordRaw[] = [
    {
        path: 'runtime',
        component: () => import('@/layouts/RouteSectionView.vue'),
        meta: {
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
                path: 'metadata',
                name: 'runtime-metadata',
                component: () =>
                    import('@/pages/runtime/RuntimeMetadataPage.vue'),
                meta: {
                    title: 'Metadata',
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
