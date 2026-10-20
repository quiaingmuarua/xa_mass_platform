import { pathToFileURL } from "node:url";
import path from "node:path";
import { git, isDocsBuildInput } from "./site.mjs";

export function shouldSkipBuild(previous) {
  if (!previous || !/^[a-f0-9]{40}$/i.test(previous)) return false;
  try {
    git("cat-file", "-e", `${previous}^{commit}`);
    // A move into the archive must still remove the formerly published page.
    return !git("diff", "--name-only", "-z", "--no-renames", previous, "HEAD")
      .split("\0")
      .filter(Boolean)
      .some(isDocsBuildInput);
  } catch {
    return false;
  }
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
) {
  // Vercel's ignore command returns 0 to skip, 1 to build.
  process.exitCode = shouldSkipBuild(process.env.VERCEL_GIT_PREVIOUS_SHA) ? 0 : 1;
}
