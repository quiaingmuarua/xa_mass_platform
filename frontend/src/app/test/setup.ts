import { afterEach, vi } from 'vitest'
import { resetRuntimeConfigOverrides } from '@/app/config'
import { resetBackendAuthRuntime } from '@/auth/backend-auth'
import { resetMockAuth } from '@/auth/use-auth'
import { resetOperatorMode } from '@/auth/operator-mode'

afterEach(() => {
    window.history.replaceState({}, '', '/')
    resetRuntimeConfigOverrides()
    resetBackendAuthRuntime()
    resetMockAuth()
    resetOperatorMode()
    vi.unstubAllGlobals()
})
