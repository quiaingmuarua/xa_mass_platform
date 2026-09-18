import { decodeUtf8, splitTextLines } from "@/files/text";

export const MAX_RECIPIENT_FILE_BYTES = 1024 * 1024;
const prefixes: Record<string, string> = { CN: "+86", US: "+1", GB: "+44" };

export async function readRecipientFile(file: File): Promise<string> {
  if (file.size > MAX_RECIPIENT_FILE_BYTES) throw new Error("号码文件不能超过 1 MiB");
  return decodeUtf8(await file.arrayBuffer());
}

export function inspectRecipients(text: string, country: string, limit: number) {
  const recipients: string[] = [];
  const issues: { line: number; message: string }[] = [];
  const seen = new Set<string>();
  const prefix = prefixes[country];
  let validCount = 0;
  splitTextLines(text).forEach((raw, index) => {
    const number = raw.trim();
    if (!number) return;
    recipients.push(number);
    let message = "";
    if (!/^\+[1-9][0-9]{1,14}$/.test(number)) message = "需要 +国家码与 2～15 位数字";
    else if (!prefix || !number.startsWith(prefix) || number.length <= prefix.length)
      message = "号码与收件国家的拨号前缀不符，或缺少本地号码";
    else if (seen.has(number)) message = "号码重复";
    else validCount++;
    seen.add(number);
    if (message) issues.push({ line: index + 1, message });
    if (recipients.length === limit + 1)
      issues.push({ line: index + 1, message: `每批最多 ${limit} 个号码` });
  });
  return { recipients, issues, validCount };
}

export function sendResultLabel(status: string): string {
  if (["SENT", "DELIVERED", "READ", "REPLIED"].includes(status)) return "成功";
  return status === "EXECUTION_FAILED" ? "失败" : "未观察";
}

export function receiptLabel(status: string): string {
  return (
    (
      { DELIVERED: "已送达", READ: "已读", REPLIED: "已回复" } as Record<string, string>
    )[status] ?? "尚未观察"
  );
}
