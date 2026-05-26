import {afterEach, vi} from 'vitest'
import {resetRuntimeConfigOverrides} from '@/app/config'
import {resetMockAuth} from '@/auth/use-auth'
import {resetOperatorMode} from '@/auth/operator-mode'

afterEach(() => {
    window.history.replaceState({}, '', '/')
    resetRuntimeConfigOverrides()
    resetMockAuth()
    resetOperatorMode()
    vi.unstubAllGlobals()
})
