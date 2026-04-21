import path from 'node:path'
import vue from '@vitejs/plugin-vue'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, __dirname, '')
    const proxyTarget = env.VITE_DEV_PROXY_TARGET

    return {
        plugins: [vue()],
        resolve: {
            alias: {
                '@': path.resolve(__dirname, './src'),
            },
        },
        server: proxyTarget
            ? {
                  proxy: {
                      '/api': {
                          target: proxyTarget,
                          changeOrigin: true,
                      },
                      '/status/api': {
                          target: proxyTarget,
                          changeOrigin: true,
                      },
                  },
              }
            : undefined,
        test: {
            environment: 'jsdom',
            globals: true,
            setupFiles: ['./src/app/test/setup.ts'],
            css: true,
        },
    }
})
