import path from "node:path";
import { rm, stat } from "node:fs/promises";
import type { Plugin } from "vite";
import { buildDocs } from "./build.mjs";

export function docsDeliveryPlugin(): Plugin {
  let output: string | undefined;
  let failed = false;
  let shouldBuild = false;
  return {
    name: "xa-mass-documentation-delivery",
    configResolved(config) {
      output = path.resolve(config.root, config.build.outDir, "docs");
      shouldBuild = config.command === "build";
    },
    buildEnd(error) {
      failed = Boolean(error);
    },
    async closeBundle() {
      if (output && shouldBuild && !failed) {
        try {
          await buildDocs(output);
        } catch (error) {
          // A failed site build must not leave a new runnable Console artifact.
          await rm(path.join(output, "..", "index.html"), { force: true });
          throw error;
        }
      }
    },
    configurePreviewServer(server) {
      server.middlewares.use(async (request, response, next) => {
        const url = new URL(request.url ?? "/", "http://localhost");
        if (url.pathname === "/docs" || url.pathname === "/docs/") {
          url.pathname = "/docs/index.html";
          request.url = `/docs/index.html${url.search}`;
        }
        if (!url.pathname.startsWith("/docs/")) return next();
        try {
          const target = path.resolve(
            output!,
            decodeURIComponent(url.pathname.slice(6))
          );
          const relative = path.relative(output!, target);
          if (relative.startsWith("..") || path.isAbsolute(relative))
            throw new Error("Invalid documentation path");
          if (!(await stat(target)).isFile()) throw new Error("Missing documentation");
          next();
        } catch {
          response.statusCode = 404;
          response.end("Documentation not found");
        }
      });
    }
  };
}
