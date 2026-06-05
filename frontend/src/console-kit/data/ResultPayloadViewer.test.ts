import {mount} from '@vue/test-utils'
import ResultPayloadViewer from '@/console-kit/data/ResultPayloadViewer.vue'

describe('ResultPayloadViewer', () => {
    it('renders JSON payload as escaped plain text', () => {
        const wrapper = mount(ResultPayloadViewer, {
            props: {
                value: {
                    output: '<script>alert("x")</script>',
                    ok: true,
                },
            },
        })

        expect(wrapper.text()).toContain('<script>alert')
        expect(wrapper.text()).toContain('</script>')
        expect(wrapper.html()).toContain('&lt;script&gt;')
        expect(wrapper.html()).not.toContain('<script>alert')
    })

    it('bounds large payload output with an explicit truncation marker', () => {
        const wrapper = mount(ResultPayloadViewer, {
            props: {
                value: { value: 'x'.repeat(64) },
                maxLength: 24,
            },
        })

        expect(wrapper.text()).toContain('truncated')
        expect(wrapper.find('.result-payload-viewer').classes()).toContain(
            'is-truncated',
        )
    })
})
