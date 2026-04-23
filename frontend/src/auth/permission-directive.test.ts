import {mount} from '@vue/test-utils'
import {mockViewerUser} from '@/auth/mock-user'
import {permissionDirective} from '@/auth/permission-directive'
import {resetMockAuth, setMockCurrentUser} from '@/auth/use-auth'

describe('permission directive', () => {
    afterEach(() => {
        resetMockAuth()
    })

    it('removes elements without the required permission', () => {
        setMockCurrentUser(mockViewerUser)

        const wrapper = mount(
            {
                template: `
          <div>
            <button v-permission="'task:view'" data-testid="allowed">Allowed</button>
            <button v-permission="'task:terminate'" data-testid="blocked">Blocked</button>
          </div>
        `,
            },
            {
                global: {
                    directives: {
                        permission: permissionDirective,
                    },
                },
            },
        )

        expect(wrapper.find('[data-testid="allowed"]').exists()).toBe(true)
        expect(wrapper.find('[data-testid="blocked"]').exists()).toBe(false)
    })
})
