import {
    currentOperatorCsrfHeader,
    reloadOperatorCsrfTokenFromStorage,
    resetBackendAuthRuntime,
    setBackendAuthConfig,
    setOperatorCsrfToken,
} from '@/auth/backend-auth'

describe('backend auth runtime', () => {
    beforeEach(() => {
        sessionStorage.clear()
        resetBackendAuthRuntime()
    })

    afterEach(() => {
        sessionStorage.clear()
        resetBackendAuthRuntime()
    })

    it('restores operator CSRF token from session storage after page reload', () => {
        setBackendAuthConfig({
            authMode: 'session',
            operatorHeaderSupported: false,
            sessionCookieSupported: true,
            csrfHeaderName: 'X-Mass-Csrf-Token',
        })
        sessionStorage.setItem('xa.mass.operator.csrf-token', 'csrf-token-1')
        reloadOperatorCsrfTokenFromStorage()

        expect(currentOperatorCsrfHeader()).toEqual({
            'X-Mass-Csrf-Token': 'csrf-token-1',
        })
    })

    it('stores operator CSRF token in session storage', () => {
        setOperatorCsrfToken('csrf-token-1')

        expect(sessionStorage.getItem('xa.mass.operator.csrf-token')).toBe(
            'csrf-token-1',
        )
    })

    it('removes stored operator CSRF token when session auth is cleared', () => {
        setBackendAuthConfig({
            authMode: 'session',
            operatorHeaderSupported: false,
            sessionCookieSupported: true,
            csrfHeaderName: 'X-Mass-Csrf-Token',
        })
        setOperatorCsrfToken('csrf-token-1')

        setOperatorCsrfToken(null)

        expect(sessionStorage.getItem('xa.mass.operator.csrf-token')).toBeNull()
        expect(currentOperatorCsrfHeader()).toEqual({})
    })
})
