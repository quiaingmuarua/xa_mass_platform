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

    it('redirects unauthenticated protected routes to login', async () => {
        resetMockAuth()

        const router = createRouter({
            history: createMemoryHistory(),
            routes: appRoutes as unknown as RouteRecordRaw[],
        })

        installRouterGuards(router)
        await router.push('/system/users')

        expect(router.currentRoute.value.path).toBe('/login')
        expect(router.currentRoute.value.query.redirect).toBe('/system/users')
    })

    it('redirects unauthorized routes to forbidden after authentication', async () => {
        setMockCurrentUser(mockViewerUser)

        const router = createRouter({
            history: createMemoryHistory(),
            routes: appRoutes as unknown as RouteRecordRaw[],
        })

        installRouterGuards(router)
        await router.push('/system/users')

        expect(router.currentRoute.value.path).toBe('/forbidden')
    })

    it('redirects authenticated users away from login', async () => {
        setMockCurrentUser(mockViewerUser)

        const router = createRouter({
            history: createMemoryHistory(),
            routes: appRoutes as unknown as RouteRecordRaw[],
        })

        installRouterGuards(router)
        await router.push('/login')

        expect(router.currentRoute.value.path).toBe('/')
    })
})
