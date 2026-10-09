import { decodeUtf8, splitTextLines } from "@/files/text";

export const MAX_RECIPIENT_FILE_BYTES = 10 * 1024 * 1024;
export const MESSAGE_IMPORT_LIMITS = {
  recipientsPerImport: 100_000,
  importFileBytes: MAX_RECIPIENT_FILE_BYTES
};
export type RecipientLimits = typeof MESSAGE_IMPORT_LIMITS;
const prefixes: Record<string, string> = { CN: "+86", US: "+1", GB: "+44" };

export function recipientIssue(number: string, country: string): string {
  if (!/^\+[1-9][0-9]{1,14}$/.test(number))
    return "需要国家码与 2～15 位数字，可省略 +";
  const prefix = prefixes[country];
  if (!prefix || !number.startsWith(prefix) || number.length <= prefix.length)
    return "号码与所选国家的拨号前缀不符，或缺少本地号码";
  return "";
}
export async function readRecipientFile(
  file: File,
  maxBytes = MAX_RECIPIENT_FILE_BYTES
): Promise<string> {
  if (file.size > maxBytes) throw new Error("号码文件超过大小限制");
  return decodeUtf8(await file.arrayBuffer());
}
export function inspectRecipients(text: string, country: string, limit: number) {
  const recipients: string[] = [];
  const issues: { line: number; message: string }[] = [];
  const seen = new Set<string>();
  let emptyCount = 0,
    duplicateCount = 0,
    invalidCount = 0;
  const lines = splitTextLines(text);
  lines.forEach((raw, index) => {
    let number = raw.trim();
    if (!number) {
      emptyCount++;
      return;
    }
    if (!number.startsWith("+")) number = `+${number}`;
    const message = recipientIssue(number, country);
    if (message) {
      invalidCount++;
      if (issues.length < 10) issues.push({ line: index + 1, message });
      return;
    }
    if (seen.has(number)) {
      duplicateCount++;
      return;
    }
    seen.add(number);
    recipients.push(number);
  });
  if (recipients.length > limit)
    issues.push({ line: 0, message: `每次最多 ${limit.toLocaleString()} 个去重号码` });
  return {
    recipients,
    issues,
    validCount: recipients.length,
    inputCount: lines.length,
    emptyCount,
    duplicateCount,
    invalidCount
  };
}
export async function inspectRecipientInput(
  source: string | File,
  country: string,
  limits: RecipientLimits
) {
  const text =
    typeof source === "string"
      ? source
      : await readRecipientFile(source, limits.importFileBytes);
  if (new TextEncoder().encode(text).length > limits.importFileBytes)
    throw new Error("号码输入超过大小限制");
  return { ...inspectRecipients(text, country, limits.recipientsPerImport), text };
}
export type RecipientInput = Awaited<ReturnType<typeof inspectRecipientInput>>;
