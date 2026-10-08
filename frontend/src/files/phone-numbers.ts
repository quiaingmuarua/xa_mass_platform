import { decodeUtf8, splitTextLines } from "@/files/text";

export const MAX_RECIPIENT_FILE_BYTES = 1024 * 1024;
const prefixes: Record<string, string> = { CN: "+86", US: "+1", GB: "+44" };

export function recipientIssue(number: string, country: string): string {
  if (!/^\+[1-9][0-9]{1,14}$/.test(number)) return "需要 +国家码与 2～15 位数字";
  const prefix = prefixes[country];
  if (!prefix || !number.startsWith(prefix) || number.length <= prefix.length)
    return "号码与所选国家的拨号前缀不符，或缺少本地号码";
  return "";
}

export async function readRecipientFile(file: File): Promise<string> {
  if (file.size > MAX_RECIPIENT_FILE_BYTES) throw new Error("号码文件不能超过 1 MiB");
  return decodeUtf8(await file.arrayBuffer());
}

export function inspectRecipients(text: string, country: string, limit: number) {
  const recipients: string[] = [];
  const issues: { line: number; message: string }[] = [];
  const seen = new Set<string>();
  let validCount = 0;
  splitTextLines(text).forEach((raw, index) => {
    const number = raw.trim();
    if (!number) return;
    recipients.push(number);
    let message = recipientIssue(number, country);
    if (!message && seen.has(number)) message = "号码重复";
    if (!message) validCount++;
    seen.add(number);
    if (message) issues.push({ line: index + 1, message });
    if (recipients.length === limit + 1)
      issues.push({ line: index + 1, message: `每批最多 ${limit} 个号码` });
  });
  return { recipients, issues, validCount };
}
