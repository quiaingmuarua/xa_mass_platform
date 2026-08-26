import { readFile } from "node:fs/promises";
import { fileURLToPath, URL } from "node:url";

import vue from "@vitejs/plugin-vue";
import { defineConfig, loadEnv, type Connect, type Plugin } from "vite";

const diagnosticCodesPath = "/reference/platform-diagnostic-codes.json";
const diagnosticCodesFile = fileURLToPath(
  new URL(
    "../distribution/server/build/generated/reference/" +
      "platform-diagnostic-codes.json",
    import.meta.url
  )
);

function diagnosticCodesMiddleware(): Connect.NextHandleFunction {
  return async (request, response, next) => {
    const requestPath = (request.url ?? "").split("?", 1)[0];
    if (requestPath !== diagnosticCodesPath) {
      next();
      return;
    }
    if (request.method !== "GET" && request.method !== "HEAD") {
      response.statusCode = 405;
      response.setHeader("Allow", "GET, HEAD");
      response.end();
      return;
    }
    try {
      const payload = await readFile(diagnosticCodesFile);
      response.statusCode = 200;
      response.setHeader("Content-Type", "application/json; charset=utf-8");
      response.setHeader("Cache-Control", "no-store");
      response.end(request.method === "HEAD" ? undefined : payload);
    } catch (error) {
      if (error instanceof Error && "code" in error && error.code === "ENOENT") {
        response.statusCode = 404;
        response.setHeader("Content-Type", "text/plain; charset=utf-8");
        response.end(
          "Generate the dictionary with " +
            ".\\gradlew.bat " +
            ":distribution:server:generatePlatformDiagnosticCodes"
        );
        return;
      }
      next(error);
    }
  };
}

function platformDiagnosticCodesPlugin(): Plugin {
  const install = (middlewares: Connect.Server) => {
    middlewares.use(diagnosticCodesMiddleware());
  };
  return {
    name: "xa-mass-platform-diagnostic-codes",
    configureServer(server) {
      install(server.middlewares);
    },
    configurePreviewServer(server) {
      install(server.middlewares);
    }
  };
}

function proxyFor(target: string) {
  return {
    "/api": {
      target,
      changeOrigin: false
    }
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const proxyTarget = env.VITE_RUNTIME_PROXY_TARGET || "http://127.0.0.1:18082";

  return {
    plugins: [vue(), platformDiagnosticCodesPlugin()],
    resolve: {
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url))
      }
    },
    server: {
      host: "127.0.0.1",
      port: 5173,
      strictPort: true,
      proxy: proxyFor(proxyTarget)
    },
    preview: {
      host: "127.0.0.1",
      port: 4173,
      strictPort: true,
      proxy: proxyFor(proxyTarget)
    },
    build: {
      target: "es2022",
      sourcemap: false,
      rollupOptions: {
        output: {
          chunkFileNames: "static/js/[name]-[hash].js",
          entryFileNames: "static/js/[name]-[hash].js",
          assetFileNames: "static/[ext]/[name]-[hash][extname]"
        }
      }
    },
    test: {
      environment: "jsdom",
      include: ["tests/**/*.test.ts"],
      setupFiles: ["./tests/setup.ts"],
      coverage: {
        provider: "v8",
        reporter: ["text", "html"]
      }
    }
  };
});
