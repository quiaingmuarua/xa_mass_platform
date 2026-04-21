import type { AppRouteRecordRaw } from '@/router/types'

export const resourceRoutes: AppRouteRecordRaw[] = [
    {
        path: 'resources',
        component: () => import('@/layouts/RouteSectionView.vue'),
        meta: {
            title: 'Resources',
            icon: 'FolderOpened',
            order: 20,
            hidden: false,
            keepAlive: false,
            requiresAuth: true,
            permissions: [],
            menuVisible: true,
        },
        children: [
            {
                path: 'workers',
                name: 'workers',
                component: () =>
                    import('@/pages/resources/workers/WorkersPage.vue'),
                meta: {
                    title: 'Workers',
                    icon: 'Cpu',
                    order: 21,
                    hidden: false,
                    keepAlive: true,
                    requiresAuth: true,
                    permissions: ['worker:view'],
                    menuVisible: true,
                },
            },
            {
                path: 'worker-contexts',
                name: 'worker-contexts',
                component: () =>
                    import('@/pages/resources/worker-contexts/WorkerContextsPage.vue'),
                meta: {
                    title: 'Worker Contexts',
                    icon: 'Connection',
                    order: 22,
                    hidden: false,
                    keepAlive: true,
                    requiresAuth: true,
                    permissions: ['worker:view'],
                    menuVisible: true,
                },
            },
            {
                path: 'rules',
                name: 'rules',
                component: () =>
                    import('@/pages/resources/rules/RulesPage.vue'),
                meta: {
                    title: 'Rules',
                    icon: 'SetUp',
                    order: 23,
                    hidden: false,
                    keepAlive: true,
                    requiresAuth: true,
                    permissions: ['rule:view'],
                    menuVisible: true,
                },
            },
            {
                path: 'configs',
                name: 'configs',
                component: () =>
                    import('@/pages/resources/configs/ConfigsPage.vue'),
                meta: {
                    title: 'Configs',
                    icon: 'Tools',
                    order: 24,
                    hidden: false,
                    keepAlive: true,
                    requiresAuth: true,
                    permissions: ['config:view'],
                    menuVisible: true,
                },
            },
        ],
    },
]
