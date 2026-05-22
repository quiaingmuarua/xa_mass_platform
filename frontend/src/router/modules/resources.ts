import type {AppRouteRecordRaw} from '@/router/types'

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
                path: 'projects',
                name: 'projects',
                component: () =>
                    import('@/pages/resources/projects/ProjectsPage.vue'),
                meta: {
                    title: 'Projects',
                    icon: 'Notebook',
                    order: 21,
                    hidden: false,
                    keepAlive: true,
                    requiresAuth: true,
                    permissions: ['task:view'],
                    menuVisible: true,
                },
            },
            {
                path: 'projects/:projectCode',
                name: 'project-detail',
                component: () =>
                    import('@/pages/resources/projects/ProjectDetailPage.vue'),
                meta: {
                    title: 'Project Detail',
                    icon: 'Notebook',
                    order: 22,
                    hidden: true,
                    keepAlive: false,
                    requiresAuth: true,
                    permissions: ['task:view'],
                    menuVisible: false,
                },
            },
            {
                path: 'workers',
                name: 'workers',
                component: () =>
                    import('@/pages/resources/workers/WorkersPage.vue'),
                meta: {
                    title: 'Workers',
                    icon: 'Cpu',
                    order: 23,
                    hidden: false,
                    keepAlive: true,
                    requiresAuth: true,
                    permissions: ['worker:view'],
                    menuVisible: true,
                },
            },
            {
                path: 'workers/:workerId',
                name: 'worker-detail',
                component: () =>
                    import('@/pages/resources/workers/WorkerDetailPage.vue'),
                meta: {
                    title: 'Worker Detail',
                    icon: 'Cpu',
                    order: 23,
                    hidden: true,
                    keepAlive: false,
                    requiresAuth: true,
                    permissions: ['worker:view'],
                    menuVisible: false,
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
                    order: 25,
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
                    order: 26,
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
