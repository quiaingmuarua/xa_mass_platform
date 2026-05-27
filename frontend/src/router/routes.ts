import {utilityRoutes} from '@/router/modules/app'
import {dashboardRoutes} from '@/router/modules/dashboard'
import {resourceRoutes} from '@/router/modules/resources'
import {runtimeRoutes} from '@/router/modules/runtime'
import {systemRoutes} from '@/router/modules/system'
import {taskRoutes} from '@/router/modules/tasks'
import type {AppRouteRecordRaw} from '@/router/types'

export const appRoutes: AppRouteRecordRaw[] = [
    {
        path: '/',
        component: () => import('@/layouts/AppShell.vue'),
        meta: {
            shell: 'operator',
            title: 'Control Console',
            icon: 'Monitor',
            order: 0,
            hidden: true,
            keepAlive: false,
            requiresAuth: true,
            permissions: [],
            menuVisible: false,
        },
        children: [
            ...dashboardRoutes,
            ...resourceRoutes,
            ...taskRoutes,
            ...runtimeRoutes,
            ...systemRoutes,
            ...utilityRoutes,
        ],
    },
]
