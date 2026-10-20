import { cp, mkdir, mkdtemp, rename, rm } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { setTimeout } from "node:timers/promises";
import { documents, frontendRoot, repositoryRoot } from "./site.mjs";

const removeOptions = { recursive: true, force: true, maxRetries: 5, retryDelay: 150 };

async function publish(incoming, target) {
  await rm(target, removeOptions);
  for (let attempt = 0; ; attempt++) {
    try {
      await rename(incoming, target);
      return;
    } catch (error) {
      if (
        process.platform !== "win32" ||
        !["EPERM", "EBUSY", "EACCES"].includes(error.code) ||
        attempt === 5
      )
        throw error;
      // Windows watchers/scanners can briefly retain a handle after a copy.
      await setTimeout(150 * (attempt + 1));
    }
  }
}

async function cleanTemporary(temporaryRoot, temporary) {
  if (path.dirname(temporary) !== temporaryRoot) throw new Error("Unsafe docs cleanup");
  await rm(temporary, removeOptions);
}

export async function buildDocs(
  destination = path.join(frontendRoot, ".vitepress-dist")
) {
  const target = path.resolve(destination);
  const relativeTarget = path.relative(repositoryRoot, target);
  if (
    !relativeTarget ||
    relativeTarget.startsWith("..") ||
    path.isAbsolute(relativeTarget)
  ) {
    throw new Error(`Documentation output must stay inside the workspace: ${target}`);
  }
  if (
    path.basename(target) !== "docs" &&
    target !== path.join(frontendRoot, ".vitepress-dist")
  ) {
    throw new Error(`Unexpected documentation output: ${target}`);
  }
  const temporaryRoot = path.join(frontendRoot, ".vitepress-temp");
  await mkdir(temporaryRoot, { recursive: true });
  const temporary = await mkdtemp(path.join(temporaryRoot, "build-"));
  const output = path.join(temporary, "output");
  const incoming = `${target}.${path.basename(temporary)}.incoming`;
  try {
    const { build } = await import("vitepress");
    await build(path.join(frontendRoot, "docs"), {
      outDir: output,
      onAfterConfigResolve(config) {
        config.tempDir = path.join(temporary, "ssr");
        config.cacheDir = path.join(temporary, "cache");
        config.pages = documents();
      }
    });
    const { checkDocs } = await import("./check.mjs");
    await checkDocs(output);
    await mkdir(path.dirname(target), { recursive: true });
    // Only replace this invocation's explicitly selected documentation directory.
    await cp(output, incoming, { recursive: true });
    await publish(incoming, target);
    return target;
  } finally {
    await rm(incoming, removeOptions);
    await cleanTemporary(temporaryRoot, temporary);
  }
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
) {
  await buildDocs();
}
