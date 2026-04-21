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
                path: 'diagnostics',
                name: 'runtime-diagnostics',
                component: () =>
                    import('@/pages/runtime/RuntimeDiagnosticsPage.vue'),
                meta: {
                    title: 'Diagnostics',
                    icon: 'Opportunity',
                    order: 41,
                    hidden: false,
                    keepAlive: true,
                    requiresAuth: true,
                    permissions: ['task:view'],
                    menuVisible: true,
                },
            },
        ],
    },
]
