import {appRoutes} from '@/router/routes'

function walkRoutes(
    routes: typeof appRoutes,
): Array<(typeof appRoutes)[number]> {
    return routes.flatMap((route) => [
        route,
        ...(route.children ? walkRoutes(route.children) : []),
    ])
}

describe('route metadata', () => {
    it('defines required meta fields on every route', () => {
        const flatRoutes = walkRoutes(appRoutes)

        flatRoutes.forEach((route) => {
            expect(route.meta.title).toBeTruthy()
            expect(typeof route.meta.icon).toBe('string')
            expect(typeof route.meta.order).toBe('number')
            expect(typeof route.meta.hidden).toBe('boolean')
            expect(typeof route.meta.keepAlive).toBe('boolean')
            expect(typeof route.meta.requiresAuth).toBe('boolean')
            expect(Array.isArray(route.meta.permissions)).toBe(true)
            expect(typeof route.meta.menuVisible).toBe('boolean')
        })
    })
})
