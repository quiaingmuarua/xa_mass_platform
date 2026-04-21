export interface AuthUser {
    id: string
    name: string
    email: string
    roles: string[]
    permissions: string[]
}

export interface AuthState {
    currentUser: AuthUser | null
    initialized: boolean
}
