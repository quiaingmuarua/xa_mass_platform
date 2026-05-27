import type {AppRouteRecordRaw} from '@/router/types'

export const dashboardRoutes: AppRouteRecordRaw[] = [
    {
        path: '',
        name: 'dashboard',
        component: () => import('@/pages/dashboard/DashboardPage.vue'),
        meta: {
            shell: 'operator',
            navGroup: 'dashboard',
            title: 'Overview',
            icon: 'DataAnalysis',
            order: 10,
            hidden: false,
            keepAlive: true,
            requiresAuth: true,
            permissions: [],
            menuVisible: true,
        },
    },
]
