import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { JSDOM } from "jsdom";
import { buildIdentity, documents, frontendRoot, pagePath } from "./site.mjs";

async function filesBelow(root) {
  const result = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const absolute = path.join(root, entry.name);
    result.push(...(entry.isDirectory() ? await filesBelow(absolute) : [absolute]));
  }
  return result;
}

export async function checkDocs(directory) {
  const root = path.resolve(directory);
  const files = await filesBelow(root);
  const relativeFiles = new Set(
    files.map((file) => path.relative(root, file).replaceAll("\\", "/"))
  );
  const errors = [];
  const pages = new Map();
  const references = [];
  const commit = buildIdentity().commit;
  const sources = documents();
  const expectedPages = new Set([...sources.map(pagePath), "404.html"]);
  for (const source of sources) {
    if (!relativeFiles.has(pagePath(source))) errors.push(`Missing page for ${source}`);
  }
  for (const file of files.filter((name) => name.endsWith(".html"))) {
    const name = path.relative(root, file).replaceAll("\\", "/");
    if (!expectedPages.has(name)) errors.push(`Unexpected published page: ${name}`);
    const dom = new JSDOM(await readFile(file, "utf8"));
    const document = dom.window.document;
    if (
      document
        .querySelector('meta[name="xa-mass-docs-commit"]')
        ?.getAttribute("content") !== commit
    ) {
      errors.push(`Wrong documentation commit in ${name}`);
    }
    if (
      name !== "404.html" &&
      !document.body.textContent.includes(`当前构建 ${commit.slice(0, 9)}`)
    ) {
      errors.push(`Missing visible build identity in ${name}`);
    }
    pages.set(
      name,
      new Set(Array.from(document.querySelectorAll("[id]"), (node) => node.id))
    );
    for (const element of document.querySelectorAll(
      "a[href],link[href],script[src],img[src],source[src]"
    )) {
      const raw = element.getAttribute("href") ?? element.getAttribute("src");
      if (!raw || /^(?:https?:|mailto:|data:|tel:|javascript:|\/\/)/i.test(raw))
        continue;
      const url = new URL(raw, `https://docs.local/docs/${name}`);
      if (!url.pathname.startsWith("/docs/")) continue; // Console and live API links.
      let target = decodeURIComponent(url.pathname.slice("/docs/".length));
      if (!target || target.endsWith("/")) target += "index.html";
      references.push({
        page: name,
        target,
        anchor: decodeURIComponent(url.hash.slice(1)),
        raw
      });
    }
    dom.window.close();
  }
  for (const { page, target, anchor, raw } of references) {
    if (!relativeFiles.has(target)) errors.push(`${page}: missing target ${raw}`);
    else if (anchor && pages.has(target) && !pages.get(target).has(anchor)) {
      errors.push(`${page}: missing anchor ${raw}`);
    }
  }
  for (const file of relativeFiles) {
    if (
      /(?:^|\/)(archive|baselines|skills)\//.test(file) ||
      /RELEASE.*\.html$/.test(file)
    ) {
      errors.push(`Historical/tool content was published: ${file}`);
    }
  }
  if (errors.length)
    throw new Error(`Documentation output failed:\n${[...new Set(errors)].join("\n")}`);
  console.log(
    `Documentation output passed: ${pages.size} pages, ${references.length} local references.`
  );
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
) {
  await checkDocs(process.argv[2] ?? path.join(frontendRoot, ".vitepress-dist"));
}
