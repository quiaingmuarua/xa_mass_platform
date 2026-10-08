import { afterEach, describe, expect, it, vi } from "vitest";
import {
  inspectImport,
  parseCsv,
  type ImportOptions
} from "../src/app-checks/import-model";
import { createImportReader } from "../src/app-checks/import-reader";

const options: ImportOptions = {
  format: "txt",
  country: "CN",
  limit: 100000,
  header: true,
  column: 0
};
afterEach(() => vi.unstubAllGlobals());
describe("App Checks imports", () => {
  it.each([
    ["CN", "8613800000001"],
    ["US", "12025550123"],
    ["GB", "447700900123"]
  ])(
    "accepts an optional plus and deduplicates equivalent %s numbers",
    (country, digits) => {
      const text = " " + digits + " \n+" + digits;
      const result = inspectImport(text, { ...options, country, limit: 1 });
      expect(result).toMatchObject({
        blocked: false,
        overLimit: false,
        duplicateCount: 1
      });
      expect(result.numbers).toEqual(["+" + digits]);
      expect(result.rows[0].raw).toBe(" " + digits + " ");
      expect(
        inspectImport("号码\n" + digits + "\n+" + digits, {
          ...options,
          format: "csv",
          country,
          limit: 1
        }).numbers
      ).toEqual(["+" + digits]);
    }
  );
  it.each([
    "13800000001",
    "12025550123",
    "008613800000001",
    "++8613800000001",
    "86+13800000001",
    "8.6138e12"
  ])(
    "still rejects missing country codes, country mismatches and malformed input: %s",
    (value) => {
      const result = inspectImport("8613800000001\n" + value, options);
      expect(result.blocked).toBe(true);
      expect(result.numbers).toEqual([]);
    }
  );
  it("retains physical lines through CSV headers, escaped quotes and quoted newlines", () => {
    const csv =
      '\uFEFF备注,号码\r\n"first,\r\n""quoted""",+8613800000001\r\nsecond,+8613800000001\r\ninvalid,bad\r\n,,\r\n';
    const report = inspectImport(csv, { ...options, format: "csv", column: 1 });
    expect(report.columns).toEqual(["备注", "号码", "第 3 列"]);
    expect(report.rows.map((row) => [row.line, row.kind])).toEqual([
      [2, "valid"],
      [4, "duplicate"],
      [5, "invalid"],
      [6, "empty"]
    ]);
    expect(report).toMatchObject({
      inputCount: 4,
      validCount: 1,
      duplicateCount: 1,
      invalidCount: 1,
      emptyCount: 1,
      blocked: true
    });
    expect(report.numbers).toEqual([]);
    const cleaned = inspectImport(csv.replace("invalid,bad\r\n", ""), {
      ...options,
      format: "csv",
      column: 1
    });
    expect(cleaned.numbers).toEqual(["+8613800000001"]);
    expect(cleaned.rows[1]).toMatchObject({
      line: 4,
      excluded: true,
      raw: "+8613800000001"
    });
    expect(cleaned).toMatchObject({
      blocked: false,
      duplicateCount: 1,
      invalidCount: 0
    });
  });
  it("requires an explicit column choice and preserves input strings", () => {
    const csv = 'note,phone\nx,"+8613800000001"\ny,"+12025550123"';
    expect(inspectImport(csv, { ...options, format: "csv" }).invalidCount).toBe(2);
    const selected = inspectImport(csv, { ...options, format: "csv", column: 1 });
    expect(selected.rows[1].issue).toContain("国家");
    expect(
      inspectImport("+8613800000001\n+8613800000002", { ...options, limit: 1 })
        .overLimit
    ).toBe(true);
    expect(
      inspectImport("+8613800000001\n+8613800000001", {
        ...options,
        limit: 1
      }).overLimit
    ).toBe(false);
    expect(
      inspectImport("+8613800000001\n+8613800000002", {
        ...options,
        format: "csv",
        header: false
      }).validCount
    ).toBe(2);
  });
  it.each(['"unclosed', '"closed"suffix', 'un"quoted'])(
    "rejects malformed CSV %s",
    (input) => {
      expect(() => parseCsv(input)).toThrow("CSV");
    }
  );
  it("handles 100000 numbers without producing an unbounded rendered preview", () => {
    const input = Array.from(
      { length: 100000 },
      (_, i) => "+86138" + String(i).padStart(8, "0")
    ).join("\n");
    const report = inspectImport(input, options);
    expect(report).toMatchObject({
      validCount: 100000,
      blocked: false,
      overLimit: false
    });
    expect(report.numbers).toHaveLength(100000);
    expect(inspectImport(input + "\n+8613999999999", options).overLimit).toBe(true);
  });
  it("terminates replaced jobs and rejects their old responses", async () => {
    const workers: WorkerStub[] = [];
    class WorkerStub {
      onmessage?: (event: MessageEvent) => void;
      onerror?: () => void;
      terminated = false;
      constructor() {
        workers.push(this);
      }
      postMessage() {}
      terminate() {
        this.terminated = true;
      }
    }
    vi.stubGlobal("Worker", WorkerStub);
    const reader = createImportReader();
    const first = reader.read("first", options).catch((error) => error);
    const second = reader.read("second", options);
    expect(workers[0].terminated).toBe(true);
    expect(await first).toMatchObject({ name: "AbortError" });
    workers[0].onmessage?.({ data: { text: "stale" } } as MessageEvent);
    const expected = {
      text: "second",
      report: inspectImport("+8613800000001", options)
    };
    workers[1].onmessage?.({ data: expected } as MessageEvent);
    expect(await second).toEqual(expected);
    expect(workers[1].terminated).toBe(true);
    const third = reader.read("third", options).catch((error) => error);
    reader.cancel();
    expect(await third).toMatchObject({ name: "AbortError" });
  });
});
