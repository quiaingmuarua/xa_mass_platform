import ElementPlus from 'element-plus'
import {mount} from '@vue/test-utils'
import StatusBadge from '@/console-kit/data/StatusBadge.vue'

describe('StatusBadge', () => {
    it('renders status through an Element Plus tag', () => {
        const wrapper = mount(StatusBadge, {
            props: {
                status: 'RUNNING',
                type: 'primary',
            },
            global: {
                plugins: [ElementPlus],
            },
        })

        expect(wrapper.text()).toContain('RUNNING')
    })
})
