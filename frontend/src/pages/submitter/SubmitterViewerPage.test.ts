import ElementPlus from 'element-plus'
import {flushPromises, mount} from '@vue/test-utils'
import SubmitterViewerPage from '@/pages/submitter/SubmitterViewerPage.vue'
import {
    createSubmitterViewerSession,
    getCurrentSubmitterViewerSession,
    getSubmitterProfileWithCredential,
    getSubmitterUsageWithCredential,
} from '@/api/submitter-sessions'

vi.mock('@/api/submitter-sessions', () => ({
    createSubmitterViewerSession: vi.fn(),
    getCurrentSubmitterViewerSession: vi.fn(),
    getSubmitterProfileWithCredential: vi.fn(),
    getSubmitterUsageWithCredential: vi.fn(),
    logoutSubmitterViewerSession: vi.fn(),
}))

describe('SubmitterViewerPage', () => {
    beforeEach(() => {
        window.sessionStorage.clear()
        vi.mocked(createSubmitterViewerSession).mockReset()
        vi.mocked(getCurrentSubmitterViewerSession).mockReset()
        vi.mocked(getSubmitterProfileWithCredential).mockReset()
        vi.mocked(getSubmitterUsageWithCredential).mockReset()
    })

    it('presents API-key viewer semantics without exposing session token concepts', async () => {
        const wrapper = mount(SubmitterViewerPage, {
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
        expect(wrapper.text()).not.toContain('Current submitter session')
        expect(wrapper.text()).not.toContain('mass_sess_')
    })

    it('exchanges API-key secret for an internal viewer credential without storing the source secret', async () => {
        vi.mocked(createSubmitterViewerSession).mockResolvedValue({
            rawSecret: 'mass_sess_internal_secret',
            session: viewerSession(),
        })
        vi.mocked(getCurrentSubmitterViewerSession).mockResolvedValue(
            viewerSession(),
        )
        vi.mocked(getSubmitterProfileWithCredential).mockResolvedValue({
            principalId: 'crawler-api-key',
            userId: 'ops-admin',
            projectScope: null,
            projectScopes: ['crawlerApp'],
            eventScopes: ['crawler.fetch-page'],
            permissions: ['task:view'],
            attributes: {},
        })
        vi.mocked(getSubmitterUsageWithCredential).mockResolvedValue({
            keyId: 'ak-1',
            principalId: 'crawler-api-key',
            total: 0,
            items: [],
        })

        const wrapper = mount(SubmitterViewerPage, {
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

        expect(createSubmitterViewerSession).toHaveBeenCalledWith(
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
