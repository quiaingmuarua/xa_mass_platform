import {
    bindUserRole,
    createUser,
    unbindUserRole,
    updateUser,
} from '@/api/users'
import {createRole, updateRole} from '@/api/roles'
import {
    resetRuntimeConfigOverrides,
    setRuntimeConfigOverrides,
} from '@/app/config'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('identity access API clients', () => {
    afterEach(() => {
        resetRuntimeConfigOverrides()
        vi.unstubAllGlobals()
    })

    it('calls user and role mutation endpoints', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
            useMockApi: false,
            useMockAuth: false,
        })
        const fetchMock = vi.fn().mockImplementation(() =>
            Promise.resolve(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: {},
                }),
            ),
        )
        vi.stubGlobal('fetch', fetchMock)

        await createUser({ userId: 'ops-new', displayName: 'Ops New' })
        await updateUser('ops-new', { status: 'DISABLED' })
        await bindUserRole('ops-new', 'OPS_VIEWER')
        await unbindUserRole('ops-new', 'OPS_VIEWER')
        await createRole({
            roleId: 'CUSTOM_VIEWER',
            name: 'Custom Viewer',
            permissions: ['task:view'],
        })
        await updateRole('CUSTOM_VIEWER', {
            name: 'Custom Viewer',
            permissions: ['task:view', 'api-usage:view'],
        })

        expect(fetchMock.mock.calls.map(([input]) => input)).toEqual([
            '/backend/api/v1/users',
            '/backend/api/v1/users/ops-new',
            '/backend/api/v1/users/ops-new/roles/OPS_VIEWER',
            '/backend/api/v1/users/ops-new/roles/OPS_VIEWER',
            '/backend/api/v1/roles',
            '/backend/api/v1/roles/CUSTOM_VIEWER',
        ])
        expect(fetchMock.mock.calls.map(([, init]) => init.method)).toEqual([
            'POST',
            'PATCH',
            'POST',
            'DELETE',
            'POST',
            'PATCH',
        ])
    })
})
