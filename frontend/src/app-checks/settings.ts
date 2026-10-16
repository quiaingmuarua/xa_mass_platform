import { z } from "zod";
import { simulationSchema, checkTypeSchema, executionSchema } from "./model";

export const SIMULATED_CONTRACT = "simulated-registration-v1";
export const REGISTRATION_CONTRACT = "registration-v1";
export const MD5_CONTRACT = "md5-prefix-demo-v1";
export const PRIME_CONTRACT = "uint32-prime-v1";
export const CARRIER_CONTRACT = "original-carrier-v1";
export const CARRIER_BATCH_CONTRACT = "original-carrier-batch-v1";
export const MD5_BATCH_CONTRACT = "md5-prefix-demo-batch-v1";
export const isCarrierContract = (id?: string) =>
  id === CARRIER_CONTRACT || id === CARRIER_BATCH_CONTRACT;
export const isMd5Contract = (id?: string) =>
  id === MD5_CONTRACT || id === MD5_BATCH_CONTRACT;
const name = z
  .string()
  .min(1)
  .max(128)
  .refine((value) => !!value.trim());
const object = z.record(z.string(), z.unknown());
// Response shape only. The JSON editor deliberately leaves semantic validation to the server.
export const settingsSchema = z.strictObject({
  common: z.strictObject({ typeDefaults: z.record(name, object) }),
  checks: z
    .record(
      name,
      z.strictObject({
        label: name,
        workerGroupId: name,
        execution: executionSchema,
        checkType: name,
        parameters: object.optional()
      })
    )
    .refine((value) => Object.keys(value).length > 0, "检查配置不能为空"),
  poolGroups: z.record(
    name,
    z.strictObject({
      windowMillis: z.number().int().positive().max(Number.MAX_SAFE_INTEGER),
      maxCount: z.number().int().positive().max(Number.MAX_SAFE_INTEGER)
    })
  )
});
const snapshotSchema = z.strictObject({
  fingerprint: z.string().regex(/^[0-9a-f]{64}$/),
  configuration: settingsSchema
});
export const configurationViewSchema = z.strictObject({
  saved: snapshotSchema,
  effective: snapshotSchema,
  restartRequired: z.boolean(),
  newScopeRequired: z.boolean(),
  checkTypes: z.array(checkTypeSchema)
});
export type AppCheckSettings = z.infer<typeof settingsSchema>;
export type AppCheckConfigurationView = z.infer<typeof configurationViewSchema>;
export interface ConfigurationSession {
  snapshot?: AppCheckConfigurationView;
  draft?: string;
  baseFingerprint?: string;
  submittedText?: string;
  readAt: number;
  error: string;
  notice: string;
  uncertain: boolean;
  stale: boolean;
  saving: boolean;
}
export function formatConfigurationDocument(value: unknown): string {
  // Keep numeric bounds on one line so the protocol and execution bindings stay visible together.
  return JSON.stringify(value, null, 2).replace(
    /("(?:\\.|[^"\\])*")|\[\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\]/g,
    (_original, quoted, first, last) => quoted ?? `[${first}, ${last}]`
  );
}
export function parseConfigurationDocument(text: string): Record<string, unknown> {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch (error) {
    throw new Error(
      "JSON 语法错误：" + (error instanceof Error ? error.message : "无法解析")
    );
  }
  if (value === null || typeof value !== "object" || Array.isArray(value))
    throw new Error("配置根节点必须是 JSON 对象");
  return value as Record<string, unknown>;
}
export function settingsIdentity(value: unknown): string {
  const normalize = (input: unknown): unknown => {
    if (Array.isArray(input)) return input.map(normalize);
    if (input && typeof input === "object")
      return Object.fromEntries(
        Object.entries(input)
          .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
          .map(([key, item]) => [key, normalize(item)])
      );
    return input;
  };
  return JSON.stringify(normalize(value));
}
// Mock computes its content token locally; API clients carry the server token unchanged.
export async function mockConfigurationFingerprint(
  settings: AppCheckSettings
): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(settingsIdentity(settings))
  );
  return [...new Uint8Array(digest)]
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
}
export function requireSettingsUpdate(
  previous: AppCheckSettings,
  next: AppCheckSettings
) {
  for (const [checkId, app] of Object.entries(previous.checks)) {
    const updated = next.checks[checkId];
    if (
      !updated ||
      updated.workerGroupId !== app.workerGroupId ||
      updated.checkType !== app.checkType
    )
      throw new Error("已有检查项不可删除、换类型或换绑 WorkerGroup：" + checkId);
  }
}
/** Mock owns the installed protocol validators; the API editor never uses this validator. */
export function validateMockSettings(input: unknown): AppCheckSettings {
  const settings = settingsSchema.parse(input);
  const normalize = (id: string, parameters: unknown): Record<string, unknown> => {
    if (id === SIMULATED_CONTRACT) return simulationSchema.parse(parameters);
    if (
      id === REGISTRATION_CONTRACT ||
      isCarrierContract(id) ||
      isMd5Contract(id) ||
      id === PRIME_CONTRACT
    )
      return z.strictObject({}).parse(parameters);
    throw new Error("未知契约：" + id);
  };
  for (const [id, parameters] of Object.entries(settings.common.typeDefaults))
    settings.common.typeDefaults[id] = normalize(id, parameters);
  const groups = new Set<string>();
  for (const app of Object.values(settings.checks)) {
    const maximum = [CARRIER_BATCH_CONTRACT, MD5_BATCH_CONTRACT].includes(app.checkType)
      ? 100
      : 1;
    if (app.execution.seedsPerItem > maximum)
      throw new Error(`execution.seedsPerItem：此契约最多支持 ${maximum} 个种子`);
    groups.add(app.workerGroupId);
    const parameters = normalize(
      app.checkType,
      app.parameters ?? settings.common.typeDefaults[app.checkType]
    );
    if (app.parameters !== undefined) app.parameters = parameters;
    if (
      (app.execution.refill.some((target) => target.poolName === "assignment-window") ||
        app.execution.workerSelector.executorName === "worker.assignment.available") &&
      !settings.poolGroups[app.workerGroupId]
    )
      throw new Error("poolGroups." + app.workerGroupId + "：缺少窗口参数");
  }
  for (const group of Object.keys(settings.poolGroups))
    if (!groups.has(group)) throw new Error("poolGroups." + group + "：未绑定应用");
  return settings;
}
