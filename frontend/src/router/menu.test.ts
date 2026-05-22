import {mockAdminUser, mockViewerUser} from '@/auth/mock-user'
import {resetMockAuth, setMockCurrentUser} from '@/auth/use-auth'
import {buildMenuTree} from '@/router/menu'
import {appRoutes} from '@/router/routes'

describe('menu filtering', () => {
    afterEach(() => {
        resetMockAuth()
    })

    it('shows only permitted routes for viewer users', () => {
        setMockCurrentUser(mockViewerUser)

        const rootChildren = appRoutes[0].children ?? []
        const menu = buildMenuTree(rootChildren, '/')
        const system = menu.find((item) => item.title === 'System')
        const resources = menu.find((item) => item.title === 'Resources')

        expect(menu.some((item) => item.title === 'Tasks')).toBe(true)
        expect(
            resources?.children.some((child) => child.title === 'Projects'),
        ).toBe(true)
        expect(system?.children.some((child) => child.title === 'Users')).toBe(
            false,
        )
        expect(
            system?.children.some((child) => child.title === 'Audit Logs'),
        ).toBe(true)
    })

    it('shows admin routes when permissions are granted', () => {
        setMockCurrentUser(mockAdminUser)

        const rootChildren = appRoutes[0].children ?? []
        const menu = buildMenuTree(rootChildren, '/')
        const system = menu.find((item) => item.title === 'System')
        const resources = menu.find((item) => item.title === 'Resources')

        expect(system?.children.some((child) => child.title === 'Users')).toBe(
            true,
        )
        expect(system?.children.some((child) => child.title === 'Roles')).toBe(
            true,
        )
        expect(
            resources?.children.some((child) => child.title === 'Projects'),
        ).toBe(true)
    })
})
