import { execFileSync } from "node:child_process";
import { existsSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const frontendRoot = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
export const repositoryRoot = path.dirname(frontendRoot);
export const homeSource = "doc/guide/index.md";
const excludedDirectories = [
  "node_modules",
  "build",
  "target",
  "dist-demo",
  "coverage",
  "archive",
  "archive_readonly",
  "baselines",
  "skills"
];
export const sourceExcludes = [
  ...excludedDirectories.map((name) => `**/${name}/**`),
  "frontend/dist/**",
  "products/**/dist/**",
  "**/.*/**",
  "**/.*",
  "**/RELEASE*.md",
  "**/THIRD_PARTY_NOTICES.md"
];

export function isPublishedDocument(name) {
  const normalized = name.replaceAll("\\", "/");
  const parts = normalized.split("/");
  return (
    name.endsWith(".md") &&
    !normalized.startsWith("frontend/dist/") &&
    !/^products\/(?:.*\/)?dist\//.test(normalized) &&
    !parts.some((part) => part.startsWith(".") || excludedDirectories.includes(part)) &&
    !/^RELEASE.*\.md$/.test(parts.at(-1)) &&
    parts.at(-1) !== "THIRD_PARTY_NOTICES.md"
  );
}

export function git(...args) {
  return execFileSync("git", args, {
    cwd: repositoryRoot,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"]
  }).trim();
}

export function documents() {
  return git(
    "ls-files",
    "-z",
    "--cached",
    "--others",
    "--exclude-standard",
    "--",
    "*.md"
  )
    .split("\0")
    .filter(
      (name) =>
        name && isPublishedDocument(name) && existsSync(path.join(repositoryRoot, name))
    )
    .sort();
}

export function ignoredSourceExcludes() {
  return git(
    "ls-files",
    "-z",
    "--others",
    "--ignored",
    "--exclude-standard",
    "--directory"
  )
    .split("\0")
    .filter(Boolean)
    .map(
      (name) =>
        `${name.replace(/([*?[\]{}()!])/g, "\\$1")}${name.endsWith("/") ? "**" : ""}`
    );
}

export function developmentWatchExcludes() {
  return [
    ...excludedDirectories.map((name) => `**/${name}/**`),
    "**/frontend/dist/**",
    "**/products/**/dist/**",
    "**/.git/**",
    "**/.gradle/**",
    "**/.kotlin/**",
    "**/.vite/**",
    "**/.vitepress-*/**",
    "**/.vitepress/.temp/**",
    "**/RELEASE*.md",
    "**/THIRD_PARTY_NOTICES.md",
    ...ignoredSourceExcludes().map(
      (name) => `${repositoryRoot.replaceAll("\\", "/")}/${name}`
    )
  ];
}

export function buildIdentity() {
  return {
    commit: git("rev-parse", "HEAD"),
    dirty: git("status", "--porcelain", "--untracked-files=normal").length > 0
  };
}

export function pagePath(source) {
  return source === homeSource ? "index.html" : source.replace(/\.md$/, ".html");
}

export function repositoryPath(source, target) {
  const decoded = decodeURIComponent(target.replaceAll("\\", "/"));
  const resolved = path.resolve(repositoryRoot, path.dirname(source), decoded);
  const relative = path.relative(repositoryRoot, resolved).replaceAll("\\", "/");
  if (relative.startsWith("../") || path.isAbsolute(relative)) {
    throw new Error(`Documentation link escapes repository: ${source} -> ${target}`);
  }
  return relative;
}

export function rewriteLink(source, href, commit, published) {
  if (!href || /^(?:[a-z][a-z\d+.-]*:|\/\/|#)/i.test(href)) return href;
  // Explicit site/UI links are already URL coordinates, not repository paths.
  if (href.startsWith("/")) return href;
  const match = href.match(/^([^?#]*)([?#].*)?$/);
  const target = repositoryPath(source, match[1]);
  if (!existsSync(path.join(repositoryRoot, target))) {
    throw new Error(`Missing documentation link: ${source} -> ${href}`);
  }
  const suffix = match[2] ?? "";
  if (published.has(target)) return `/${pagePath(target)}${suffix}`;
  const kind = statSync(path.join(repositoryRoot, target)).isDirectory()
    ? "tree"
    : "blob";
  return `https://github.com/quiaingmuarua/xa_mass_platform/${kind}/${commit}/${target
    .split("/")
    .map(encodeURIComponent)
    .join("/")}${suffix}`;
}

// This function is serialized by VitePress into the browser search configuration.
// It must be self-contained so indexing and querying use identical segmentation.
export function tokenize(text) {
  return Array.from(new Intl.Segmenter("zh", { granularity: "word" }).segment(text))
    .filter((item) => item.isWordLike)
    .map((item) => item.segment.toLowerCase());
}

export function githubSlug(text) {
  return text
    .toLowerCase()
    .replace(/<[^>]*>/g, "")
    .replace(/[`*~]/g, "")
    .replace(/\s/g, "-")
    .replace(/[^\p{L}\p{M}\p{N}_-]/gu, "");
}

export function isDocsBuildInput(name) {
  const normalized = name.replaceAll("\\", "/");
  return (
    isPublishedDocument(normalized) ||
    normalized.startsWith("frontend/") ||
    normalized.startsWith("doc/guide/") ||
    normalized === ".gitignore"
  );
}
