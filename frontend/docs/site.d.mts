export const frontendRoot: string;
export const repositoryRoot: string;
export const homeSource: string;
export const sourceExcludes: string[];
export function isPublishedDocument(name: string): boolean;
export function git(...args: string[]): string;
export function documents(): string[];
export function ignoredSourceExcludes(): string[];
export function developmentWatchExcludes(): string[];
export function buildIdentity(): { commit: string; dirty: boolean };
export function pagePath(source: string): string;
export function repositoryPath(source: string, target: string): string;
export function rewriteLink(
  source: string,
  href: string,
  commit: string,
  published: ReadonlySet<string>
): string;
export function tokenize(text: string): string[];
export function githubSlug(text: string): string;
export function isDocsBuildInput(name: string): boolean;
