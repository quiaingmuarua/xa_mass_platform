import { fileURLToPath, URL } from "node:url";
import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vitest/config";

export default defineConfig({
    base: "/sms/",
    plugins: [vue()],
    resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
    server: {
        host: "127.0.0.1",
        port: 5174,
        strictPort: true,
        proxy: { "/api/v1/sms": "http://127.0.0.1:18390" }
    },
    build: { outDir: "dist", target: "es2022", sourcemap: false },
    test: {
        server: { deps: { inline: ["element-plus"] } },
        environment: "jsdom",
        include: ["tests/**/*.test.ts"],
        setupFiles: ["./tests/setup.ts"]
    }
});
