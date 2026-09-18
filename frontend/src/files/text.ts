export function decodeUtf8(content: ArrayBuffer): string {
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(content);
  } catch {
    throw new Error("The input file must be valid UTF-8 text.");
  }
}

export function splitTextLines(text: string): string[] {
  if (text.length === 0) return [];
  const lines = text.split(/\r\n|\n|\r/);
  if (/\r\n$|\n$|\r$/.test(text)) lines.pop();
  return lines;
}
