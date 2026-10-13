/** Stable attribution identifier, independent of login, role and storage. */
export function normalizeTaskUserId(value: string): string {
  const id = value.trim();
  if (!/^[1-9a-z][0-9a-z]{3,8}$/.test(id))
    throw new Error("userId 须为 4～9 位小写字母或数字，且不能以 0 开头。");
  return id;
}
export function taskUserScore(userId?: string): number {
  return userId === undefined
    ? 0
    : Number.parseInt(normalizeTaskUserId(userId), 36) * 64;
}
/** Redis equal-score member ordering is byte ordering, not locale ordering. */
export function compareTaskIds(a: string, b: string): number {
  const encoder = new TextEncoder();
  const left = encoder.encode(a),
    right = encoder.encode(b);
  for (let i = 0; i < Math.min(left.length, right.length); i++) {
    const difference = left[i]! - right[i]!;
    if (difference) return difference;
  }
  return left.length - right.length;
}
