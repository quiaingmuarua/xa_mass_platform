import type {AppRouteRecordRaw} from '@/router/types'

export const taskRoutes: AppRouteRecordRaw[] = [
    {
        path: 'tasks',
        component: () => import('@/layouts/RouteSectionView.vue'),
        meta: {
            shell: 'operator',
            navGroup: 'tasks',
            title: 'Tasks',
            icon: 'Tickets',
            order: 30,
            hidden: false,
            keepAlive: false,
            requiresAuth: true,
            permissions: ['task:view'],
            menuVisible: true,
        },
        children: [
            {
                path: '',
                name: 'tasks',
                component: () => import('@/pages/tasks/TasksListPage.vue'),
                meta: {
                    shell: 'operator',
                    navGroup: 'tasks',
                    title: 'Task List',
                    icon: 'List',
                    order: 31,
                    hidden: false,
                    keepAlive: true,
                    requiresAuth: true,
                    permissions: ['task:view'],
                    menuVisible: true,
                },
            },
            {
                path: ':taskId',
                name: 'task-detail',
                component: () => import('@/pages/tasks/TaskDetailPage.vue'),
                meta: {
                    shell: 'operator',
                    navGroup: 'tasks',
                    title: 'Task Detail',
                    icon: 'Document',
                    order: 32,
                    hidden: true,
                    keepAlive: false,
                    requiresAuth: true,
                    permissions: ['task:view'],
                    menuVisible: false,
                },
            },
        ],
    },
]
