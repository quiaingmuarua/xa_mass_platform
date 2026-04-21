import type { RouteRecordRaw } from 'vue-router'
import { createRouter, createWebHistory } from 'vue-router'
import { installRouterGuards } from '@/router/guards'
import { appRoutes } from '@/router/routes'

export const router = createRouter({
    history: createWebHistory(),
    routes: appRoutes as unknown as RouteRecordRaw[],
    scrollBehavior() {
        return { top: 0 }
    },
})

installRouterGuards(router)
