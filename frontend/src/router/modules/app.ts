import type { AppRouteRecordRaw } from '@/router/types'

export const utilityRoutes: AppRouteRecordRaw[] = [
    {
        path: 'forbidden',
        name: 'forbidden',
        component: () => import('@/pages/app/ForbiddenPage.vue'),
        meta: {
            title: 'Forbidden',
            icon: 'WarningFilled',
            order: 90,
            hidden: true,
            keepAlive: false,
            requiresAuth: false,
            permissions: [],
            menuVisible: false,
        },
    },
    {
        path: ':pathMatch(.*)*',
        name: 'not-found',
        component: () => import('@/pages/app/NotFoundPage.vue'),
        meta: {
            title: 'Not Found',
            icon: 'Warning',
            order: 99,
            hidden: true,
            keepAlive: false,
            requiresAuth: false,
            permissions: [],
            menuVisible: false,
        },
    },
]
