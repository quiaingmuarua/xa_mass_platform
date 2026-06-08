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

export interface ApiKeyViewerSessionView {
    sessionId: string
    keyId: string
    principalId: string
    createdForUserId: string
    keyPrefix: string
    permissions: string[]
    projectScopes: string[]
    eventScopes: string[]
    attributes: Record<string, string>
    createdAt: string
    expiresAt: string
    revokedAt: string | null
}

export interface ApiKeyViewerSessionCreateResponse {
    session: ApiKeyViewerSessionView
    rawSecret: string
}

export type ApiUsageOperation =
    | 'TASK_CREATE'
    | 'TASK_ITEM_APPEND'
    | 'TASK_SYNC_APPEND'
    | 'TASK_RESULT_READ'
    | 'TASK_ARCHIVE_DOWNLOAD'

export type ApiUsageStatus =
    | 'ACCEPTED'
    | 'REJECTED'
    | 'FAILED_AFTER_ACCEPT'

export interface ApiUsageLedgerRecord {
    usageId: string
    keyId: string
    principalId: string
    operation: ApiUsageOperation
    status: ApiUsageStatus
    project: string | null
    eventCode: string | null
    taskId: string | null
    messageId: string | null
    requestId: string | null
    units: number
    createdAt: string
}

export interface CurrentApiKeyUsageResponse {
    keyId: string
    principalId: string
    items: ApiUsageLedgerRecord[]
    total: number
}
