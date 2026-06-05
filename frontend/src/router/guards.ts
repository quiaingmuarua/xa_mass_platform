import type { Router } from 'vue-router'
import { useAuth } from '@/auth/use-auth'
import { canAccessRoute } from '@/utils/permissions'

export function installRouterGuards(router: Router): void {
    router.beforeEach((to) => {
        const { isAuthenticated } = useAuth()

        if (to.name === 'login' && isAuthenticated.value) {
            return { path: '/', replace: true }
        }

        if (to.meta.requiresAuth && !isAuthenticated.value) {
            return {
                path: '/login',
                query: {
                    redirect: to.fullPath,
                },
                replace: true,
            }
        }

        if (!canAccessRoute(to.meta)) {
            return { path: '/forbidden', replace: true }
        }

        return true
    })
}
