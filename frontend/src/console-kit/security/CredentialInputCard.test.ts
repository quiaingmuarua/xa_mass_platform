import ElementPlus from 'element-plus'
import {mount} from '@vue/test-utils'
import CredentialInputCard from '@/console-kit/security/CredentialInputCard.vue'

describe('CredentialInputCard', () => {
    it('renders credential copy without exposing implementation terms', () => {
        const wrapper = mount(CredentialInputCard, {
            props: {
                modelValue: '',
                title: 'Open viewer',
                label: 'API Key Secret',
                actionLabel: 'View API key usage',
                placeholder: 'mass_sk_...',
                hint: 'The secret works like a password.',
            },
            global: {
                plugins: [ElementPlus],
            },
        })

        expect(wrapper.text()).toContain('Open viewer')
        expect(wrapper.text()).toContain('API Key Secret')
        expect(wrapper.text()).toContain('The secret works like a password.')
        expect(wrapper.text()).not.toContain('session token')
    })

    it('submits only when a non-empty credential is present', async () => {
        const wrapper = mount(CredentialInputCard, {
            props: {
                modelValue: '',
                title: 'Open viewer',
                label: 'API Key Secret',
                actionLabel: 'View API key usage',
            },
            global: {
                plugins: [ElementPlus],
            },
        })

        await wrapper.find('button').trigger('click')
        expect(wrapper.emitted('submit')).toBeUndefined()

        await wrapper.setProps({ modelValue: 'mass_sk_secret' })
        await wrapper.find('button').trigger('click')
        expect(wrapper.emitted('submit')).toHaveLength(1)
    })
})
