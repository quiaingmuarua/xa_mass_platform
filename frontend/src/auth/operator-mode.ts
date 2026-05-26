import {ref} from 'vue'

export type OperatorMode = 'admin' | 'viewer'

const STORAGE_KEY = 'xa.mass.operatorMode'

function readStoredMode(): OperatorMode {
    if (typeof window === 'undefined') {
        return 'admin'
    }

    const value = window.localStorage.getItem(STORAGE_KEY)
    return value === 'viewer' ? 'viewer' : 'admin'
}

const operatorMode = ref<OperatorMode>(readStoredMode())

export function useOperatorMode() {
    function setOperatorMode(mode: OperatorMode): void {
        operatorMode.value = mode
        if (typeof window !== 'undefined') {
            window.localStorage.setItem(STORAGE_KEY, mode)
        }
    }

    return {
        operatorMode,
        setOperatorMode,
    }
}

export function currentOperatorModeHeader(): OperatorMode {
    return operatorMode.value
}

export function resetOperatorMode(): void {
    operatorMode.value = 'admin'
    if (typeof window !== 'undefined') {
        window.localStorage.removeItem(STORAGE_KEY)
    }
}
