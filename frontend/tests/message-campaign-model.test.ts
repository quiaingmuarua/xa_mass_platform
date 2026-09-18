import { describe, expect, it, vi } from "vitest";
import {
  inspectRecipients,
  MAX_RECIPIENT_FILE_BYTES,
  readRecipientFile,
  receiptLabel,
  sendResultLabel
} from "../src/message-campaigns/model";
import { parseSeedLines } from "../src/task-management/model";

const encoded = (text: string) => new TextEncoder().encode(text).buffer;
const file = (bytes: ArrayBuffer) =>
  ({ size: bytes.byteLength, arrayBuffer: async () => bytes }) as File;
const numbers = (count: number) =>
  Array.from(
    { length: count },
    (_, i) => `+861380000${String(i).padStart(4, "0")}`
  ).join("\n");

describe("Messages recipient files", () => {
  it("decodes UTF-8 BOM and all line endings while retaining physical error line numbers", async () => {
    const text = await readRecipientFile(
      file(encoded("\uFEFF +8613800000001 \r\n\n+8613800000002\r+8613800000001\n"))
    );
    const result = inspectRecipients(text, "CN", 1000);
    expect(result.recipients).toEqual([
      "+8613800000001",
      "+8613800000002",
      "+8613800000001"
    ]);
    expect(result.validCount).toBe(2);
    expect(result.issues).toEqual([{ line: 4, message: "号码重复" }]);
  });
  it("rejects invalid encoding and oversize before attempting a read", async () => {
    await expect(
      readRecipientFile(file(new Uint8Array([0xc3, 0x28]).buffer))
    ).rejects.toThrow("UTF-8");
    const arrayBuffer = vi.fn();
    await expect(
      readRecipientFile({
        size: MAX_RECIPIENT_FILE_BYTES + 1,
        arrayBuffer
      } as unknown as File)
    ).rejects.toThrow("1 MiB");
    expect(arrayBuffer).not.toHaveBeenCalled();
    await expect(
      readRecipientFile(file(new Uint8Array(MAX_RECIPIENT_FILE_BYTES).fill(32).buffer))
    ).resolves.toHaveLength(MAX_RECIPIENT_FILE_BYTES);
  });
  it("keeps Messages at 1000 while the existing finite Task parser still accepts 10000 lines", () => {
    expect(inspectRecipients(numbers(1000), "CN", 1000).issues).toEqual([]);
    expect(inspectRecipients(numbers(1001), "CN", 1000).issues).toEqual([
      { line: 1001, message: "每批最多 1000 个号码" }
    ]);
    expect(parseSeedLines(encoded(numbers(10000)))).toHaveLength(10000);
    expect(() => parseSeedLines(encoded(numbers(10001)))).toThrow("10000");
    expect(inspectRecipients("\n \r\n", "CN", 1000).recipients).toEqual([]);
  });
  it.each([
    "+86",
    "+44123",
    "86123",
    "+086123",
    "+861 23",
    "+8612345678901234",
    "recipient-1"
  ])("rejects %s without silently replacing it", (number) => {
    const result = inspectRecipients(`\n${number}`, "CN", 1000);
    expect(result.recipients).toEqual([number]);
    expect(result.validCount).toBe(0);
    expect(result.issues[0].line).toBe(2);
  });
  it.each([
    ["CN", "+861"],
    ["US", "+12"],
    ["GB", "+441"],
    ["US", "+123456789012345"]
  ])("checks only the declared %s dial prefix for %s", (country, number) => {
    expect(inspectRecipients(number, country, 1000).issues).toEqual([]);
  });
  it("keeps send results distinct from receipts and never invents a receipt for failure or absence", () => {
    expect(sendResultLabel("SENT")).toBe("成功");
    expect(receiptLabel("SENT")).toBe("尚未观察");
    for (const status of ["DELIVERED", "READ", "REPLIED"])
      expect(sendResultLabel(status)).toBe("成功");
    expect(sendResultLabel("EXECUTION_FAILED")).toBe("失败");
    expect(receiptLabel("EXECUTION_FAILED")).toBe("尚未观察");
    expect(sendResultLabel("NOT_OBSERVED")).toBe("未观察");
    expect(receiptLabel("NOT_OBSERVED")).toBe("尚未观察");
  });
});
