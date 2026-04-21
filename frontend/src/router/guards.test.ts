import type { RouteRecordRaw } from 'vue-router'
import { createMemoryHistory, createRouter } from 'vue-router'
import { mockViewerUser } from '@/auth/mock-user'
import { resetMockAuth, setMockCurrentUser } from '@/auth/use-auth'
import { installRouterGuards } from '@/router/guards'
import { appRoutes } from '@/router/routes'

describe('router guards', () => {
    afterEach(() => {
        resetMockAuth()
    })

    it('redirects unauthorized routes to forbidden', async () => {
        setMockCurrentUser(mockViewerUser)

        const router = createRouter({
            history: createMemoryHistory(),
            routes: appRoutes as unknown as RouteRecordRaw[],
        })

        installRouterGuards(router)
        await router.push('/system/users')

        expect(router.currentRoute.value.path).toBe('/forbidden')
    })
})
