import {afterEach, vi} from 'vitest'
import {resetRuntimeConfigOverrides} from '@/app/config'
import {resetMockAuth} from '@/auth/use-auth'

afterEach(() => {
    window.history.replaceState({}, '', '/')
    resetRuntimeConfigOverrides()
    resetMockAuth()
    vi.unstubAllGlobals()
})
