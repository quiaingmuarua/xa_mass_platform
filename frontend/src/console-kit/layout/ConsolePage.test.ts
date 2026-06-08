import {mount} from '@vue/test-utils'
import ConsolePage from '@/console-kit/layout/ConsolePage.vue'

describe('ConsolePage', () => {
    it('renders admin page shell copy, badge, actions, and default content', () => {
        const wrapper = mount(ConsolePage, {
            props: {
                eyebrow: 'API-key access',
                title: 'API Key Viewer',
                subtitle: 'Inspect one API key.',
                tone: 'security',
            },
            slots: {
                badge: '<span>Key-scoped</span>',
                actions: '<button>Refresh</button>',
                default: '<main>Viewer content</main>',
            },
        })

        expect(wrapper.text()).toContain('API-key access')
        expect(wrapper.text()).toContain('API Key Viewer')
        expect(wrapper.text()).toContain('Inspect one API key.')
        expect(wrapper.text()).toContain('Key-scoped')
        expect(wrapper.text()).toContain('Refresh')
        expect(wrapper.text()).toContain('Viewer content')
        expect(wrapper.classes()).toContain('console-page--security')
        expect(wrapper.find('.console-page-hero').exists()).toBe(true)
    })
})
