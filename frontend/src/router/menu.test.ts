import {mockAdminUser, mockViewerUser} from '@/auth/mock-user'
import {resetMockAuth, setMockCurrentUser} from '@/auth/use-auth'
import {buildMenuModel} from '@/router/menu-model'
import {appRoutes} from '@/router/routes'

describe('menu filtering', () => {
    afterEach(() => {
        resetMockAuth()
    })

    it('shows only permitted routes for viewer users', () => {
        setMockCurrentUser(mockViewerUser)

        const rootChildren = appRoutes[0].children ?? []
        const menu = buildMenuModel(rootChildren, 'operator', '/')
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
        expect(
            system?.children.some((child) => child.title === 'API Keys'),
        ).toBe(false)
    })

    it('shows admin routes when permissions are granted', () => {
        setMockCurrentUser(mockAdminUser)

        const rootChildren = appRoutes[0].children ?? []
        const menu = buildMenuModel(rootChildren, 'operator', '/')
        const system = menu.find((item) => item.title === 'System')
        const resources = menu.find((item) => item.title === 'Resources')

        expect(system?.children.some((child) => child.title === 'Users')).toBe(
            true,
        )
        expect(system?.children.some((child) => child.title === 'Roles')).toBe(
            true,
        )
        expect(
            system?.children.some((child) => child.title === 'API Keys'),
        ).toBe(true)
        expect(
            resources?.children.some((child) => child.title === 'Projects'),
        ).toBe(true)
    })

    it('keeps API-key viewer out of the operator menu', () => {
        setMockCurrentUser(mockAdminUser)

        const rootChildren = appRoutes[0].children ?? []
        const operatorMenu = buildMenuModel(rootChildren, 'operator', '/')
        const viewerMenu = buildMenuModel(rootChildren, 'api-key-viewer', '/')

        expect(
            operatorMenu.some((item) => item.title === 'API-Key Viewer'),
        ).toBe(false)
        expect(viewerMenu.map((item) => item.title)).toEqual([
            'API-Key Viewer',
        ])
    })
})
