import {mount} from '@vue/test-utils'
import {setRuntimeConfigOverrides} from '@/app/config'
import ApiReferencePage from '@/pages/system/api-reference/ApiReferencePage.vue'

describe('ApiReferencePage', () => {
  it('embeds the backend docs URL in backend mode', () => {
    setRuntimeConfigOverrides({
      useMockApi: false,
      apiDocsUrl: '/doc.html#/home',
    })

    const wrapper = mount(ApiReferencePage, {
      global: {
        stubs: {
          ConsolePage: {
            template: '<section><slot name="badge" /><slot name="actions" /><slot /></section>',
          },
          ElCard: {
            template: '<section><slot name="header" /><slot /></section>',
          },
          ElTag: true,
          ElButton: true,
          PageEmptyState: true,
        },
      },
    })

    expect(wrapper.find('iframe').attributes('src')).toBe('/doc.html#/home')
    expect(wrapper.text()).toContain('/doc.html#/home')
  })

  it('does not embed the default local docs URL in mock preview mode', () => {
    setRuntimeConfigOverrides({
      useMockApi: true,
      apiDocsUrl: '/doc.html#/home',
    })

    const wrapper = mount(ApiReferencePage, {
      global: {
        stubs: {
          ConsolePage: {
            template: '<section><slot name="badge" /><slot name="actions" /><slot /></section>',
          },
          ElCard: {
            template: '<section><slot name="header" /><slot /></section>',
          },
          ElTag: true,
          ElButton: true,
          PageEmptyState: {
            props: ['description'],
            template: '<p>{{ description }}</p>',
          },
        },
      },
    })

    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(wrapper.text()).toContain('VITE_API_DOCS_URL')
  })
})
