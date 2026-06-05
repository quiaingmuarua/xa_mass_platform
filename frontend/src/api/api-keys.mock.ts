import type {
    ApiKeyApplicationCreateRequest,
    ApiKeyApplicationRecord,
    ApiKeyCreateRequest,
    ApiKeyCreateResponse,
    ApiKeyCredentialView,
    ApiUsageLedgerRecord,
} from '@/types/api-keys'

const mockCredentials: ApiKeyCredentialView[] = [
    {
        keyId: 'ak_mock_crawler',
        principalId: 'crawler-api-key',
        createdForUserId: 'ops-admin',
        keyPrefix: 'mass_sk_mock_crawler...',
        projectScopes: ['publicProbe'],
        eventScopes: ['crawler.fetch-page'],
        permissions: ['task:create', 'task:view'],
        status: 'ACTIVE',
        applicationId: 'app_mock_crawler',
        createdBy: 'ops-admin',
        createdAt: '2026-06-05T00:00:00Z',
        expiresAt: null,
        revokedAt: null,
        revokedBy: null,
        revokeReason: null,
        attributes: {
            source: 'mock-preview',
        },
    },
    {
        keyId: 'ak_mock_viewer',
        principalId: 'viewer-api-key',
        createdForUserId: 'ops-viewer',
        keyPrefix: 'mass_sk_mock_viewer...',
        projectScopes: ['publicProbe'],
        eventScopes: [],
        permissions: ['task:view', 'api-usage:view'],
        status: 'REVOKED',
        applicationId: null,
        createdBy: 'ops-admin',
        createdAt: '2026-06-05T00:00:00Z',
        expiresAt: null,
        revokedAt: '2026-06-05T06:00:00Z',
        revokedBy: 'ops-admin',
        revokeReason: 'mock preview revoked credential',
        attributes: {
            source: 'mock-preview',
        },
    },
]

const mockApplications: ApiKeyApplicationRecord[] = [
    {
        applicationId: 'app_mock_crawler',
        applicantUserId: 'ops-admin',
        applicantName: 'Ops Admin',
        requestedPrincipalId: 'crawler-api-key',
        requestedUserId: 'ops-admin',
        requestedProjectScopes: ['publicProbe'],
        requestedEventScopes: ['crawler.fetch-page'],
        requestedPermissions: ['task:create', 'task:view'],
        purpose: 'SDK integration key for crawler worker registration.',
        status: 'APPROVED',
        reviewReason: 'approved for mock preview',
        reviewedBy: 'ops-admin',
        createdAt: '2026-06-05T00:00:00Z',
        reviewedAt: '2026-06-05T00:10:00Z',
        attributes: {
            source: 'mock-preview',
        },
    },
    {
        applicationId: 'app_mock_pending',
        applicantUserId: 'crawler-operator',
        applicantName: 'Crawler Operator',
        requestedPrincipalId: 'pending-crawler-key',
        requestedUserId: 'crawler-operator',
        requestedProjectScopes: ['publicProbe'],
        requestedEventScopes: ['crawler.fetch-page'],
        requestedPermissions: ['task:create', 'task:view'],
        purpose: 'Pending review example for the productionized console.',
        status: 'PENDING',
        reviewReason: null,
        reviewedBy: null,
        createdAt: '2026-06-05T01:00:00Z',
        reviewedAt: null,
        attributes: {
            source: 'mock-preview',
        },
    },
]

const mockUsage: ApiUsageLedgerRecord[] = [
    {
        usageId: 'usage_mock_001',
        keyId: 'ak_mock_crawler',
        principalId: 'crawler-api-key',
        operation: 'TASK_CREATE',
        status: 'ACCEPTED',
        project: 'publicProbe',
        eventCode: null,
        taskId: 'task-crawler-preview',
        messageId: null,
        requestId: 'req-mock-001',
        units: 1,
        createdAt: '2026-06-05T02:00:00Z',
    },
    {
        usageId: 'usage_mock_002',
        keyId: 'ak_mock_crawler',
        principalId: 'crawler-api-key',
        operation: 'TASK_SYNC_APPEND',
        status: 'FAILED_AFTER_ACCEPT',
        project: 'publicProbe',
        eventCode: 'crawler.fetch-page',
        taskId: 'task-crawler-preview',
        messageId: 'msg-crawler-preview-001',
        requestId: 'req-mock-002',
        units: 1,
        createdAt: '2026-06-05T02:05:00Z',
    },
]

function delay<T>(value: T): Promise<T> {
    return new Promise((resolve) => {
        window.setTimeout(() => resolve(value), 80)
    })
}

function timestamp(): string {
    return new Date().toISOString()
}

function cloneCredential(credential: ApiKeyCredentialView): ApiKeyCredentialView {
    return {
        ...credential,
        projectScopes: [...credential.projectScopes],
        eventScopes: [...credential.eventScopes],
        permissions: [...credential.permissions],
        attributes: {...credential.attributes},
    }
}

function cloneApplication(
    application: ApiKeyApplicationRecord,
): ApiKeyApplicationRecord {
    return {
        ...application,
        requestedProjectScopes: [...application.requestedProjectScopes],
        requestedEventScopes: [...application.requestedEventScopes],
        requestedPermissions: [...application.requestedPermissions],
        attributes: {...application.attributes},
    }
}

function cloneUsage(record: ApiUsageLedgerRecord): ApiUsageLedgerRecord {
    return {...record}
}

function createCredentialFromRequest(
    request: ApiKeyCreateRequest,
    applicationId: string | null,
): ApiKeyCreateResponse {
    const now = timestamp()
    const suffix = String(mockCredentials.length + 1).padStart(3, '0')
    const credential: ApiKeyCredentialView = {
        keyId: `ak_mock_${suffix}`,
        principalId: request.principalId,
        createdForUserId: request.createdForUserId,
        keyPrefix: `mass_sk_mock_${suffix}...`,
        projectScopes: [...request.projectScopes],
        eventScopes: [...request.eventScopes],
        permissions: [...request.permissions],
        status: 'ACTIVE',
        applicationId,
        createdBy: 'mock-preview',
        createdAt: now,
        expiresAt: request.expiresAt ?? null,
        revokedAt: null,
        revokedBy: null,
        revokeReason: null,
        attributes: request.attributes ?? {
            source: 'mock-preview',
        },
    }
    mockCredentials.unshift(credential)
    return {
        credential: cloneCredential(credential),
        rawSecret: `mass_sk_mock_secret_${suffix}`,
    }
}

export async function listApiKeysMock(): Promise<ApiKeyCredentialView[]> {
    return delay(mockCredentials.map(cloneCredential))
}

export async function getApiKeyMock(
    keyId: string,
): Promise<ApiKeyCredentialView> {
    const credential = mockCredentials.find((item) => item.keyId === keyId)
    if (!credential) {
        throw new Error(`Mock API key not found: ${keyId}`)
    }
    return delay(cloneCredential(credential))
}

export async function createApiKeyMock(
    request: ApiKeyCreateRequest,
): Promise<ApiKeyCreateResponse> {
    return delay(createCredentialFromRequest(request, null))
}

export async function revokeApiKeyMock(
    keyId: string,
    reason: string,
): Promise<ApiKeyCredentialView> {
    const credential = mockCredentials.find((item) => item.keyId === keyId)
    if (!credential) {
        throw new Error(`Mock API key not found: ${keyId}`)
    }
    credential.status = 'REVOKED'
    credential.revokedAt = timestamp()
    credential.revokedBy = 'mock-preview'
    credential.revokeReason = reason.trim() || 'revoked from mock preview'
    return delay(cloneCredential(credential))
}

export async function listApiKeyApplicationsMock(): Promise<
    ApiKeyApplicationRecord[]
> {
    return delay(mockApplications.map(cloneApplication))
}

export async function getApiKeyApplicationMock(
    applicationId: string,
): Promise<ApiKeyApplicationRecord> {
    const application = mockApplications.find(
        (item) => item.applicationId === applicationId,
    )
    if (!application) {
        throw new Error(`Mock API-key application not found: ${applicationId}`)
    }
    return delay(cloneApplication(application))
}

export async function createApiKeyApplicationMock(
    request: ApiKeyApplicationCreateRequest,
): Promise<ApiKeyApplicationRecord> {
    const application: ApiKeyApplicationRecord = {
        applicationId: `app_mock_${String(mockApplications.length + 1).padStart(3, '0')}`,
        applicantUserId: request.requestedUserId,
        applicantName: null,
        requestedPrincipalId: request.requestedPrincipalId ?? null,
        requestedUserId: request.requestedUserId,
        requestedProjectScopes: [...request.requestedProjectScopes],
        requestedEventScopes: [...request.requestedEventScopes],
        requestedPermissions: [...request.requestedPermissions],
        purpose: request.purpose,
        status: 'PENDING',
        reviewReason: null,
        reviewedBy: null,
        createdAt: timestamp(),
        reviewedAt: null,
        attributes: request.attributes ?? {
            source: 'mock-preview',
        },
    }
    mockApplications.unshift(application)
    return delay(cloneApplication(application))
}

export async function approveApiKeyApplicationMock(
    applicationId: string,
    reason: string,
): Promise<ApiKeyCreateResponse> {
    const application = mockApplications.find(
        (item) => item.applicationId === applicationId,
    )
    if (!application) {
        throw new Error(`Mock API-key application not found: ${applicationId}`)
    }
    application.status = 'APPROVED'
    application.reviewReason = reason.trim() || 'approved from mock preview'
    application.reviewedBy = 'mock-preview'
    application.reviewedAt = timestamp()
    return delay(
        createCredentialFromRequest(
            {
                principalId:
                    application.requestedPrincipalId ??
                    `${application.requestedUserId}-api-key`,
                createdForUserId: application.requestedUserId,
                projectScopes: application.requestedProjectScopes,
                eventScopes: application.requestedEventScopes,
                permissions: application.requestedPermissions,
                attributes: application.attributes,
            },
            application.applicationId,
        ),
    )
}

export async function rejectApiKeyApplicationMock(
    applicationId: string,
    reason: string,
): Promise<ApiKeyApplicationRecord> {
    const application = mockApplications.find(
        (item) => item.applicationId === applicationId,
    )
    if (!application) {
        throw new Error(`Mock API-key application not found: ${applicationId}`)
    }
    application.status = 'REJECTED'
    application.reviewReason = reason.trim() || 'rejected from mock preview'
    application.reviewedBy = 'mock-preview'
    application.reviewedAt = timestamp()
    return delay(cloneApplication(application))
}

export async function listApiKeyUsageMock(
    keyId: string,
): Promise<{ items: ApiUsageLedgerRecord[]; total: number }> {
    const items = mockUsage
        .filter((item) => item.keyId === keyId)
        .map(cloneUsage)
    return delay({
        items,
        total: items.length,
    })
}
