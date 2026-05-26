export type ApiKeyCredentialStatus =
    | 'ACTIVE'
    | 'REVOKED'
    | 'DISABLED'
    | 'EXPIRED'

export type ApiKeyApplicationStatus =
    | 'PENDING'
    | 'APPROVED'
    | 'REJECTED'
    | 'CANCELLED'

export interface ApiKeyCredentialView {
    keyId: string
    principalId: string
    createdForUserId: string
    keyPrefix: string
    projectScopes: string[]
    eventScopes: string[]
    permissions: string[]
    status: ApiKeyCredentialStatus
    applicationId: string | null
    createdBy: string | null
    createdAt: string
    expiresAt: string | null
    revokedAt: string | null
    revokedBy: string | null
    revokeReason: string | null
    attributes: Record<string, string>
}

export interface ApiKeyCreateRequest {
    principalId: string
    createdForUserId: string
    projectScopes: string[]
    eventScopes: string[]
    permissions: string[]
    expiresAt?: string | null
    attributes?: Record<string, string>
}

export interface ApiKeyCreateResponse {
    credential: ApiKeyCredentialView
    rawSecret: string
}

export interface ApiKeyApplicationRecord {
    applicationId: string
    applicantUserId: string
    applicantName: string | null
    requestedPrincipalId: string | null
    requestedUserId: string
    requestedProjectScopes: string[]
    requestedEventScopes: string[]
    requestedPermissions: string[]
    purpose: string
    status: ApiKeyApplicationStatus
    reviewReason: string | null
    reviewedBy: string | null
    createdAt: string
    reviewedAt: string | null
    attributes: Record<string, string>
}

export interface ApiKeyApplicationCreateRequest {
    requestedPrincipalId?: string | null
    requestedUserId: string
    requestedProjectScopes: string[]
    requestedEventScopes: string[]
    requestedPermissions: string[]
    purpose: string
    attributes?: Record<string, string>
}
