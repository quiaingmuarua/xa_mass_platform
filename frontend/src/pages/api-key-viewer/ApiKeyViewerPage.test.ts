import ElementPlus from 'element-plus'
import {flushPromises, mount} from '@vue/test-utils'
import ApiKeyViewerPage from '@/pages/api-key-viewer/ApiKeyViewerPage.vue'
import {
    createApiKeyViewerSession,
    getCurrentApiKeyViewerSession,
    getApiKeyProfileWithCredential,
    getApiKeyUsageWithCredential,
    logoutApiKeyViewerSession,
} from '@/api/api-key-viewer-sessions'
import {ApiError} from '@/api/http'

vi.mock('@/api/api-key-viewer-sessions', () => ({
    createApiKeyViewerSession: vi.fn(),
    getCurrentApiKeyViewerSession: vi.fn(),
    getApiKeyProfileWithCredential: vi.fn(),
    getApiKeyUsageWithCredential: vi.fn(),
    logoutApiKeyViewerSession: vi.fn(),
}))

describe('ApiKeyViewerPage', () => {
    beforeEach(() => {
        window.sessionStorage.clear()
        vi.mocked(createApiKeyViewerSession).mockReset()
        vi.mocked(getCurrentApiKeyViewerSession).mockReset()
        vi.mocked(getApiKeyProfileWithCredential).mockReset()
        vi.mocked(getApiKeyUsageWithCredential).mockReset()
        vi.mocked(logoutApiKeyViewerSession).mockReset()
    })

    it('presents API-key viewer semantics without exposing session token concepts', async () => {
        const wrapper = mount(ApiKeyViewerPage, {
            global: {
                plugins: [ElementPlus],
                stubs: {
                    teleport: true,
                },
            },
        })

        await flushPromises()

        expect(wrapper.text()).toContain('API Key Viewer')
        expect(wrapper.text()).toContain('API Key Secret')
        expect(wrapper.text()).toContain('View API key usage')
        expect(wrapper.text()).not.toContain('session token')
        expect(wrapper.text()).not.toContain('Attach')
        expect(wrapper.text()).not.toContain('Current API-key viewer session')
        expect(wrapper.text()).not.toContain('mass_sess_')
    })

    it('exchanges API-key secret for an internal viewer credential without storing the source secret', async () => {
        vi.mocked(createApiKeyViewerSession).mockResolvedValue({
            rawSecret: 'mass_sess_internal_secret',
            session: viewerSession(),
        })
        vi.mocked(getCurrentApiKeyViewerSession).mockResolvedValue(
            viewerSession(),
        )
        vi.mocked(getApiKeyProfileWithCredential).mockResolvedValue({
            principalId: 'crawler-api-key',
            userId: 'ops-admin',
            projectScope: null,
            projectScopes: ['crawlerApp'],
            eventScopes: ['crawler.fetch-page'],
            permissions: ['task:view'],
            attributes: {},
        })
        vi.mocked(getApiKeyUsageWithCredential).mockResolvedValue({
            keyId: 'ak-1',
            principalId: 'crawler-api-key',
            total: 0,
            items: [],
        })

        const wrapper = mount(ApiKeyViewerPage, {
            global: {
                plugins: [ElementPlus],
                stubs: {
                    teleport: true,
                },
            },
        })

        const setupState = (
            wrapper.vm.$ as unknown as {
                setupState: {
                    apiKeySecret: string
                    openViewer: () => Promise<void>
                }
            }
        ).setupState

        setupState.apiKeySecret = 'mass_sk_source_secret'
        await setupState.openViewer()
        await flushPromises()

        expect(createApiKeyViewerSession).toHaveBeenCalledWith(
            'mass_sk_source_secret',
        )
        expect(window.sessionStorage.getItem('xa.mass.apiKeyViewerCredential'))
            .toBe('mass_sess_internal_secret')
        expect([...storageValues()]).not.toContain('mass_sk_source_secret')
        expect(wrapper.text()).toContain('ak-1')
        expect(wrapper.text()).not.toContain('mass_sess_internal_secret')
        expect(wrapper.text()).not.toContain('mass_sess_abc...')
        expect(wrapper.text()).not.toContain('mass_sk_source_secret')
    })

    it('clears the internal viewer credential when refresh is unauthorized', async () => {
        window.sessionStorage.setItem(
            'xa.mass.apiKeyViewerCredential',
            'mass_sess_expired_secret',
        )
        vi.mocked(getCurrentApiKeyViewerSession).mockRejectedValue(
            new ApiError('Invalid or missing API-key credential', 401, null),
        )

        const wrapper = mount(ApiKeyViewerPage, {
            global: {
                plugins: [ElementPlus],
                stubs: {
                    teleport: true,
                },
            },
        })

        await flushPromises()

        expect(getCurrentApiKeyViewerSession).toHaveBeenCalledWith(
            'mass_sess_expired_secret',
        )
        expect(window.sessionStorage.getItem('xa.mass.apiKeyViewerCredential'))
            .toBeNull()
        expect(wrapper.text()).not.toContain('mass_sess_expired_secret')
        expect(wrapper.text()).toContain('Invalid or missing API-key credential')
    })

    it('clears the internal viewer credential on explicit exit', async () => {
        const wrapper = mount(ApiKeyViewerPage, {
            global: {
                plugins: [ElementPlus],
                stubs: {
                    teleport: true,
                },
            },
        })

        const setupState = (
            wrapper.vm.$ as unknown as {
                setupState: {
                    viewerCredential: string
                    exitViewer: () => Promise<void>
                }
            }
        ).setupState

        setupState.viewerCredential = 'mass_sess_internal_secret'
        window.sessionStorage.setItem(
            'xa.mass.apiKeyViewerCredential',
            'mass_sess_internal_secret',
        )

        await setupState.exitViewer()
        await flushPromises()

        expect(logoutApiKeyViewerSession).toHaveBeenCalledWith(
            'mass_sess_internal_secret',
        )
        expect(window.sessionStorage.getItem('xa.mass.apiKeyViewerCredential'))
            .toBeNull()
        expect(wrapper.text()).not.toContain('mass_sess_internal_secret')
    })
})

function viewerSession() {
    return {
        sessionId: 'svs-1',
        keyId: 'ak-1',
        principalId: 'crawler-api-key',
        createdForUserId: 'ops-admin',
        keyPrefix: 'mass_sess_abc...',
        permissions: ['task:view'],
        projectScopes: ['crawlerApp'],
        eventScopes: ['crawler.fetch-page'],
        attributes: {},
        createdAt: '2026-05-26T00:00:00Z',
        expiresAt: '2026-05-26T08:00:00Z',
        revokedAt: null,
    }
}

function* storageValues(): Generator<string> {
    for (let index = 0; index < window.sessionStorage.length; index += 1) {
        const key = window.sessionStorage.key(index)
        if (key) {
            yield window.sessionStorage.getItem(key) ?? ''
        }
    }
}
