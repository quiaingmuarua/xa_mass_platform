import {mount} from '@vue/test-utils'
import FilterToolbar from '@/console-kit/data/FilterToolbar.vue'

describe('FilterToolbar', () => {
    it('renders filter controls through the default slot', () => {
        const wrapper = mount(FilterToolbar, {
            slots: {
                default: '<button>Search</button>',
            },
        })

        expect(wrapper.text()).toContain('Search')
        expect(wrapper.classes()).toContain('filter-toolbar')
    })
})
