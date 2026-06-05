import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import type { RouteRecordRaw } from 'vue-router'
import { createMemoryHistory, createRouter } from 'vue-router'
import { setRuntimeConfigOverrides } from '@/app/config'
import LoginPage from '@/pages/app/LoginPage.vue'
import { appRoutes } from '@/router/routes'

function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

async function mountLoginPage(path = '/login') {
    const router = createRouter({
        history: createMemoryHistory(),
        routes: appRoutes as unknown as RouteRecordRaw[],
    })
    await router.push(path)
    await router.isReady()
    const wrapper = mount(LoginPage, {
        global: {
            plugins: [router, ElementPlus],
        },
    })
    return { wrapper, router }
}

describe('LoginPage', () => {
    it('logs in and redirects to the requested route', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
            useMockAuth: false,
        })
        const fetchMock = vi
            .fn()
            .mockResolvedValueOnce(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: {
                        authMode: 'session',
                        operatorHeaderSupported: false,
                        sessionCookieSupported: true,
                        csrfHeaderName: 'X-Mass-Csrf-Token',
                    },
                }),
            )
            .mockResolvedValueOnce(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: {
                        user: {
                            id: 'ops-admin',
                            name: 'Ops Admin',
                            email: 'ops-admin@example.internal',
                            roles: ['OPS_ADMIN'],
                            permissions: ['user:view'],
                        },
                        csrfToken: 'csrf-token-1',
                    },
                }),
            )
        vi.stubGlobal('fetch', fetchMock)
        const { wrapper, router } = await mountLoginPage(
            '/login?redirect=/system/users',
        )

        await wrapper
            .find('input[autocomplete="username"]')
            .setValue('ops-admin')
        await wrapper
            .find('input[autocomplete="current-password"]')
            .setValue('secret')
        await wrapper.find('.login-submit').trigger('click')
        await flushPromises()

        await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
        await vi.waitFor(() =>
            expect(router.currentRoute.value.fullPath).toBe('/system/users'),
        )
    })

    it('shows a bounded error for invalid credentials', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
            useMockAuth: false,
        })
        vi.stubGlobal(
            'fetch',
            vi
                .fn()
                .mockResolvedValueOnce(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: {
                            authMode: 'session',
                            operatorHeaderSupported: false,
                            sessionCookieSupported: true,
                            csrfHeaderName: 'X-Mass-Csrf-Token',
                        },
                    }),
                )
                .mockResolvedValueOnce(
                    jsonResponse(
                        {
                            code: 401,
                            msg: 'Invalid operator credentials',
                            data: null,
                        },
                        401,
                    ),
                ),
        )
        const { wrapper, router } = await mountLoginPage()

        await wrapper
            .find('input[autocomplete="username"]')
            .setValue('ops-admin')
        await wrapper
            .find('input[autocomplete="current-password"]')
            .setValue('wrong')
        await wrapper.find('.login-submit').trigger('click')
        await flushPromises()

        expect(router.currentRoute.value.path).toBe('/login')
        expect(wrapper.text()).toContain('Invalid user ID or password.')
    })
})
