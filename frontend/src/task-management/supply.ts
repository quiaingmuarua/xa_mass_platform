import { refillSchema } from "@/runtime-viewer/schemas";

export interface SupplyRow {
  poolName: string;
  targetText: string;
  count: number;
}
export interface SupplyDraft {
  refillMode: "rows" | "json";
  refillText: string;
  supplyRows: SupplyRow[];
}
export function newSupplyDraft(): SupplyDraft {
  return { refillMode: "rows", refillText: "[]", supplyRows: [] };
}
export function readSupply(draft: SupplyDraft, pools: readonly string[]) {
  const value = refillSchema.parse(
    draft.refillMode === "json"
      ? JSON.parse(draft.refillText)
      : draft.supplyRows.map((row) => ({
          poolName: row.poolName,
          target: JSON.parse(row.targetText),
          count: row.count
        }))
  );
  if (value.some((row) => !pools.includes(row.poolName)))
    throw new Error("供给 Pool 未由当前 Group 启用");
  return value;
}
