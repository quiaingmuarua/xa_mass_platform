import type { Router } from 'vue-router'
import { useAuth } from '@/auth/use-auth'
import { canAccessRoute } from '@/utils/permissions'

export function installRouterGuards(router: Router): void {
    router.beforeEach((to) => {
        const { isAuthenticated } = useAuth()

        if (to.meta.requiresAuth && !isAuthenticated.value) {
            return { path: '/forbidden', replace: true }
        }

        if (!canAccessRoute(to.meta)) {
            return { path: '/forbidden', replace: true }
        }

        return true
    })
}
