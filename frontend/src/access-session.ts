/**
 * The current tab's access key. `console-identity` owns login, logout and sessionStorage; this module only
 * hands the key to requests. The key is never logged, rendered or put in URLs.
 */
const accessKeyPattern = /^xak_[A-Za-z0-9_-]{8,128}$/;
let current: string | null = null;

export function isAccessKey(value: unknown): value is string {
  return typeof value === "string" && accessKeyPattern.test(value);
}

export function bearerHeaders(accessKey: string): Record<string, string> {
  return { Authorization: `Bearer ${accessKey}` };
}

export function setSessionAccessKey(accessKey: string | null) {
  current = accessKey;
}

/** For requests that are not bound to a captured TaskCaller, such as read-only views and Direct Call. */
export function sessionAuthHeaders(): Record<string, string> {
  return current ? bearerHeaders(current) : {};
}
