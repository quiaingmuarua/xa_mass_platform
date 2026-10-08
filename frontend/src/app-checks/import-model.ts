import { recipientIssue } from "@/files/phone-numbers";
import { splitTextLines } from "@/files/text";

export interface ImportOptions {
  format: "txt" | "csv";
  country: string;
  limit: number;
  header: boolean;
  column: number;
}
export interface ImportRow {
  line: number;
  raw: string;
  number: string;
  kind: "valid" | "duplicate" | "invalid" | "empty";
  issue: string;
  excluded: boolean;
}
export interface ImportReport {
  columns: string[];
  rows: ImportRow[];
  numbers: string[];
  inputCount: number;
  emptyCount: number;
  validCount: number;
  duplicateCount: number;
  invalidCount: number;
  blocked: boolean;
  overLimit: boolean;
}
export interface ImportSnapshot {
  sourceFile: string;
  summary: Omit<ImportReport, "rows" | "numbers" | "columns">;
  receipt?: ImportReceipt;
}
export interface ImportReceipt {
  taskId: string;
  inputCount: number;
  emptyCount: number;
  duplicateCount: number;
  uniqueCount: number;
  addedCount: number;
  existingCount: number;
}

// Physical starting lines identify the first invalid record without a review table.
export function parseCsv(text: string): { line: number; cells: string[] }[] {
  const rows: { line: number; cells: string[] }[] = [];
  let cells: string[] = [],
    cell = "",
    quoted = false,
    closed = false;
  let line = 1,
    start = 1;
  const source = text.replace(/^\uFEFF/, "");
  for (let i = 0; i < source.length; i++) {
    const char = source[i];
    if (quoted) {
      if (char === '"') {
        if (source[i + 1] === '"') {
          cell += '"';
          i++;
        } else {
          quoted = false;
          closed = true;
        }
      } else if (char === "\r" || char === "\n") {
        if (char === "\r" && source[i + 1] === "\n") i++;
        cell += "\n";
        line++;
      } else cell += char;
      continue;
    }
    if (char === '"' && cell === "" && !closed) {
      quoted = true;
      continue;
    }
    if (char === "," || char === "\r" || char === "\n") {
      cells.push(cell);
      cell = "";
      closed = false;
      if (char !== ",") {
        rows.push({ line: start, cells });
        cells = [];
        if (char === "\r" && source[i + 1] === "\n") i++;
        line++;
        start = line;
      }
    } else {
      if (closed || char === '"') throw new Error("CSV 第 " + line + " 行引号格式无效");
      cell += char;
    }
  }
  if (quoted) throw new Error("CSV 第 " + start + " 行引号未闭合");
  if (cell || closed || cells.length)
    rows.push({ line: start, cells: [...cells, cell] });
  return rows;
}

export function inspectImport(text: string, options: ImportOptions): ImportReport {
  let parsed =
    options.format === "csv"
      ? parseCsv(text)
      : splitTextLines(text.replace(/^\uFEFF/, "")).map((value, index) => ({
          line: index + 1,
          cells: [value]
        }));
  const width = parsed.reduce((max, row) => Math.max(max, row.cells.length), 1);
  const columns = Array.from({ length: width }, (_, i) =>
    options.format === "csv" && options.header
      ? parsed[0]?.cells[i]?.trim() || "第 " + (i + 1) + " 列"
      : "第 " + (i + 1) + " 列"
  );
  if (options.format === "csv" && options.header) parsed = parsed.slice(1);
  if (options.column < 0 || options.column >= width)
    throw new Error("请选择有效的号码列");
  const seen = new Set<string>(),
    numbers: string[] = [];
  const rows: ImportRow[] = parsed.map((row) => {
    const raw = row.cells[options.format === "csv" ? options.column : 0] ?? "";
    const trimmed = raw.trim();
    // App Checks input may omit '+'. Keep the existing canonical API/Worker value.
    const number = /^[0-9]+$/.test(trimmed) ? "+" + trimmed : trimmed;
    const empty = row.cells.every((cell) => !cell.trim());
    const error = empty
      ? ""
      : recipientIssue(number, options.country).replace("+国家码", "国家码");
    const kind = empty
      ? "empty"
      : error
        ? "invalid"
        : seen.has(number)
          ? "duplicate"
          : "valid";
    if (!error && !empty) seen.add(number);
    const excluded = kind === "empty" || kind === "duplicate";
    if (kind === "valid") numbers.push(number);
    return {
      line: row.line,
      raw,
      number,
      kind,
      issue: error || (kind === "duplicate" ? "号码重复" : ""),
      excluded
    };
  });
  const count = (kind: ImportRow["kind"]) =>
    rows.filter((row) => row.kind === kind).length;
  const duplicateCount = count("duplicate"),
    invalidCount = count("invalid");
  return {
    columns,
    rows,
    numbers: invalidCount ? [] : numbers,
    inputCount: rows.length,
    emptyCount: count("empty"),
    validCount: count("valid"),
    duplicateCount,
    invalidCount,
    blocked: invalidCount > 0,
    overLimit: numbers.length > options.limit
  };
}
export function importSnapshot(
  report: ImportReport,
  sourceFile: string
): ImportSnapshot {
  const { rows: _rows, numbers: _numbers, columns: _columns, ...summary } = report;
  return { sourceFile, summary };
}
export const csvCell = (value: string) =>
  '"' + (/^[=+\-@\t\r]/.test(value) ? "'" + value : value).replaceAll('"', '""') + '"';
