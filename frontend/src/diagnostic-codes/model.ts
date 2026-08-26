import type { PlatformDiagnosticCodes } from "./schema";

export type DiagnosticCodeRow = {
  owner: string;
  module: string;
  definition: string;
  surface: string;
  code: number;
  name: string;
  meaning: string;
};

export function flattenDiagnosticCodes(
  dictionary: PlatformDiagnosticCodes
): DiagnosticCodeRow[] {
  return dictionary.owners.flatMap((owner) =>
    owner.codes.map((entry) => ({
      owner: owner.owner,
      module: owner.module,
      definition: owner.definition,
      surface: owner.surface,
      ...entry
    }))
  );
}

export function filterDiagnosticCodes(
  rows: readonly DiagnosticCodeRow[],
  owner: string,
  query: string
): DiagnosticCodeRow[] {
  const normalizedQuery = query.trim().toLocaleLowerCase();
  return rows.filter((row) => {
    if (owner !== "all" && row.owner !== owner) return false;
    if (normalizedQuery.length === 0) return true;
    return [row.code.toString(), row.name, row.meaning, row.owner]
      .map((value) => value.toLocaleLowerCase())
      .some((value) => value.includes(normalizedQuery));
  });
}
