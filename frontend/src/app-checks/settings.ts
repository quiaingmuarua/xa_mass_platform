import { z } from "zod";
import { simulationSchema } from "./model";

export const SIMULATED_CONTRACT = "simulated-registration-v1";
export const REGISTRATION_CONTRACT = "registration-v1";
const name = z
  .string()
  .min(1)
  .max(128)
  .refine((value) => !!value.trim());
const object = z.record(z.string(), z.unknown());
export const executionSchema = z.strictObject({
  eventCode: z.string().min(1),
  refill: z.array(
    z.strictObject({
      poolName: z.string().min(1),
      target: z.unknown(),
      count: z.number().int().positive()
    })
  ),
  workerSelector: z.strictObject({
    executorName: z.string().min(1),
    input: z.unknown()
  })
});
// Response shape only. The JSON editor deliberately leaves semantic validation to the server.
export const settingsSchema = z.strictObject({
  common: z.strictObject({ contractDefaults: z.record(name, object) }),
  applications: z
    .record(
      name,
      z.strictObject({
        label: name,
        workerGroupId: name,
        execution: executionSchema,
        contractId: name,
        parameters: object.optional()
      })
    )
    .refine((value) => Object.keys(value).length > 0, "应用配置不能为空"),
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
  contracts: z.array(
    z.strictObject({
      id: z.string(),
      description: z.string(),
      parameterExample: object,
      inputExample: object,
      resultExample: object
    })
  )
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
  for (const [appId, app] of Object.entries(previous.applications)) {
    const updated = next.applications[appId];
    if (!updated || updated.workerGroupId !== app.workerGroupId)
      throw new Error("已有应用不可删除或换绑 WorkerGroup：" + appId);
  }
}
/** Mock owns the same two protocol validators; the API editor never uses this validator. */
export function validateMockSettings(input: unknown): AppCheckSettings {
  const settings = settingsSchema.parse(input);
  const normalize = (id: string, parameters: unknown): Record<string, unknown> => {
    if (id === SIMULATED_CONTRACT) return simulationSchema.parse(parameters);
    if (id === REGISTRATION_CONTRACT) return z.strictObject({}).parse(parameters);
    throw new Error("未知契约：" + id);
  };
  for (const [id, parameters] of Object.entries(settings.common.contractDefaults))
    settings.common.contractDefaults[id] = normalize(id, parameters);
  const groups = new Set<string>();
  for (const app of Object.values(settings.applications)) {
    if (groups.has(app.workerGroupId)) throw new Error("applications：Group 必须唯一");
    groups.add(app.workerGroupId);
    const parameters = normalize(
      app.contractId,
      app.parameters ?? settings.common.contractDefaults[app.contractId]
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
