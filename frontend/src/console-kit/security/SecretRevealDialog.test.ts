import ElementPlus from 'element-plus'
import {mount} from '@vue/test-utils'
import SecretRevealDialog from '@/console-kit/security/SecretRevealDialog.vue'

describe('SecretRevealDialog', () => {
    it('shows the one-time secret with key identity metadata', () => {
        const wrapper = mount(SecretRevealDialog, {
            props: {
                modelValue: true,
                secret: 'mass_sk_secret',
                keyId: 'ak_123',
                secretPrefix: 'mass_sk_abc...',
                principalId: 'crawler-api-key',
            },
            global: {
                plugins: [ElementPlus],
                stubs: {
                    ElDialog: {
                        props: ['modelValue'],
                        template:
                            '<div v-if="modelValue"><slot /><slot name="footer" /></div>',
                    },
                },
            },
        })

        expect(wrapper.text()).toContain('ak_123')
        expect(wrapper.text()).toContain('mass_sk_abc...')
        expect(wrapper.text()).toContain('crawler-api-key')
        expect(wrapper.text()).toContain('mass_sk_secret')
        expect(wrapper.text()).toContain('will not return it again')
    })

    it('emits confirm and closes when copied', async () => {
        const wrapper = mount(SecretRevealDialog, {
            props: {
                modelValue: true,
                secret: 'mass_sk_secret',
                keyId: 'ak_123',
            },
            global: {
                plugins: [ElementPlus],
                stubs: {
                    ElDialog: {
                        props: ['modelValue'],
                        template:
                            '<div v-if="modelValue"><slot /><slot name="footer" /></div>',
                    },
                },
            },
        })

        await wrapper.find('button').trigger('click')

        expect(wrapper.emitted('confirm')).toHaveLength(1)
        expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([false])
    })
})
