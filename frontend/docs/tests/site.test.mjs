import assert from "node:assert/strict";
import test from "node:test";
import { execFileSync, spawnSync } from "node:child_process";
import { cp, mkdir, mkdtemp, rename, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import {
  buildIdentity,
  documents,
  frontendRoot,
  githubSlug,
  isDocsBuildInput,
  isPublishedDocument,
  pagePath,
  repositoryPath,
  rewriteLink,
  tokenize
} from "../site.mjs";
import { shouldSkipBuild } from "../ignored-build.mjs";

test("current Owner pages keep repository paths; historical material stays out", () => {
  for (const name of [
    "README.md",
    "doc/kernel/scheduling-overview.md",
    "server_jvm/README.md",
    "distribution/server/src/main/dist/examples/batch-checks/README.md"
  ]) {
    assert.equal(isPublishedDocument(name), true);
  }
  for (const name of [
    "doc/archive/trace/legacy.md",
    "doc/skills/test/SKILL.md",
    "integrations/x/baselines/a.md",
    "distribution/server/RELEASE-3.1.md",
    "frontend/node_modules/a/README.md",
    "frontend/dist/README.md",
    "frontend/.vitepress-temp/a.md"
  ]) {
    assert.equal(isPublishedDocument(name), false, name);
  }
  assert.equal(pagePath("doc/guide/index.md"), "index.html");
  assert.equal(pagePath("server_jvm/README.md"), "server_jvm/README.html");
});

test("rendered links use source coordinates and pin code to the build commit", () => {
  const commit = buildIdentity().commit;
  const published = new Set(documents());
  assert.equal(
    rewriteLink(
      "doc/README.md",
      "../server_jvm/README.md#assembly-boundaries",
      commit,
      published
    ),
    "/server_jvm/README.html#assembly-boundaries"
  );
  assert.match(
    rewriteLink("README.md", "server_jvm/build.gradle", commit, published),
    new RegExp(`/blob/${commit}/server_jvm/build.gradle$`)
  );
  const historical =
    "https://github.com/quiaingmuarua/xa_mass_platform/blob/old/doc.md";
  assert.equal(rewriteLink("README.md", historical, commit, published), historical);
  assert.throws(
    () => rewriteLink("README.md", "missing-document.md", commit, published),
    /Missing documentation link/
  );
  assert.throws(
    () => repositoryPath("README.md", "../outside.md"),
    /escapes repository/
  );
});

test("Chinese and technical search use the same standalone browser tokenizer", () => {
  const browserTokenizer = new Function(`return (${tokenize.toString()})`)();
  for (const word of ["积分", "待审核", "窗口", "部署", "Worker", "Result", "lease"]) {
    assert.deepEqual(browserTokenizer(word), tokenize(word));
    assert.ok(tokenize(word).length > 0);
  }
  assert.equal(
    githubSlug("API Reference and Response Contract"),
    "api-reference-and-response-contract"
  );
  assert.equal(githubSlug("API 和 Task 数据"), "api-和-task-数据");
});

test("root Markdown changes build the site; missing deployment baseline never skips", () => {
  assert.equal(isDocsBuildInput("worker_matching_jvm/README.md"), true);
  assert.equal(isDocsBuildInput("doc/guide/first-task.md"), true);
  assert.equal(isDocsBuildInput("frontend/docs/.vitepress/config.mts"), true);
  assert.equal(isDocsBuildInput("doc/archive/old.md"), false);
  assert.equal(isDocsBuildInput("kernel_jvm/src/main/java/Example.java"), false);
  assert.equal(shouldSkipBuild(undefined), false);
  assert.equal(shouldSkipBuild("not-a-commit"), false);
});

test("Vercel ignore command observes a committed Markdown-only change outside frontend", async () => {
  const temporaryRoot = path.join(frontendRoot, ".vitepress-temp");
  await mkdir(temporaryRoot, { recursive: true });
  const fixture = await mkdtemp(path.join(temporaryRoot, "ignore-test-"));
  const runGit = (...args) =>
    execFileSync("git", args, { cwd: fixture, encoding: "utf8", stdio: "pipe" }).trim();
  const commit = (message) => {
    runGit("add", ".");
    runGit(
      "-c",
      "user.name=Documentation Test",
      "-c",
      "user.email=docs-test@example.invalid",
      "-c",
      "commit.gpgsign=false",
      "commit",
      "-qm",
      message
    );
    return runGit("rev-parse", "HEAD");
  };
  const skip = (previous) =>
    spawnSync(process.execPath, ["docs/ignored-build.mjs"], {
      cwd: path.join(fixture, "frontend"),
      env: { ...process.env, VERCEL_GIT_PREVIOUS_SHA: previous },
      encoding: "utf8"
    });
  try {
    await mkdir(path.join(fixture, "frontend/docs"), { recursive: true });
    for (const name of ["site.mjs", "ignored-build.mjs"]) {
      await cp(
        path.join(frontendRoot, "docs", name),
        path.join(fixture, "frontend/docs", name)
      );
    }
    await writeFile(path.join(fixture, "README.md"), "# Current guide\n");
    runGit("init", "-q");
    const baseline = commit("Initial fixture");
    assert.equal(skip(baseline).status, 0);
    await writeFile(path.join(fixture, "README.md"), "# Updated current guide\n");
    const documentationCommit = commit("Change only repository Markdown");
    const changed = skip(baseline);
    assert.equal(changed.status, 1, changed.stderr);
    await writeFile(path.join(fixture, "Example.java"), "class Example {}\n");
    const implementationCommit = commit("Change only unrelated implementation");
    assert.equal(skip(documentationCommit).status, 0);
    await mkdir(path.join(fixture, "doc/archive"), { recursive: true });
    await rename(
      path.join(fixture, "README.md"),
      path.join(fixture, "doc/archive/README.md")
    );
    const archivedCommit = commit("Move a current page into the archive");
    assert.equal(skip(implementationCommit).status, 1);
    await writeFile(path.join(fixture, "doc/入门.md"), "# 中文文件名\n");
    commit("Add a page with a Chinese filename");
    assert.equal(skip(archivedCommit).status, 1);
    assert.equal(skip("0".repeat(40)).status, 1);
  } finally {
    assert.equal(path.dirname(fixture), temporaryRoot);
    await rm(fixture, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});
