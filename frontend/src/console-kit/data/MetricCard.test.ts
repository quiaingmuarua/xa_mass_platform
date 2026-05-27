import {mount} from '@vue/test-utils'
import MetricCard from '@/console-kit/data/MetricCard.vue'

describe('MetricCard', () => {
    it('renders metric label, value, hint, and tone class', () => {
        const wrapper = mount(MetricCard, {
            props: {
                label: 'Tasks',
                value: 42,
                hint: 'visible records',
                tone: 'primary',
            },
        })

        expect(wrapper.text()).toContain('Tasks')
        expect(wrapper.text()).toContain('42')
        expect(wrapper.text()).toContain('visible records')
        expect(wrapper.classes()).toContain('metric-card--primary')
    })
})
