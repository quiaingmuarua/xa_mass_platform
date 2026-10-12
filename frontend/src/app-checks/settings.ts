import { z } from "zod";
import { simulationSchema } from "./model";

const name = z
  .string()
  .min(1)
  .max(128)
  .refine((value) => !!value.trim());
export const settingsSchema = z
  .strictObject({
    common: z.strictObject({ simulationDefault: simulationSchema }),
    applications: z
      .array(z.strictObject({ appId: name, label: name, workerGroupId: name }))
      .min(1),
    poolGroups: z.record(
      name,
      z.strictObject({
        windowMillis: z.number().int().positive().max(Number.MAX_SAFE_INTEGER),
        maxCount: z.number().int().positive().max(Number.MAX_SAFE_INTEGER)
      })
    )
  })
  .superRefine((value, context) => {
    const ids = new Set(value.applications.map((app) => app.appId));
    const groups = new Set(value.applications.map((app) => app.workerGroupId));
    if (
      ids.size !== value.applications.length ||
      groups.size !== value.applications.length
    )
      context.addIssue({
        code: "custom",
        message: "应用 ID 和 WorkerGroup 必须分别唯一"
      });
    if (
      Object.keys(value.poolGroups).length !== groups.size ||
      [...groups].some((group) => !value.poolGroups[group])
    )
      context.addIssue({
        code: "custom",
        message: "窗口参数必须完整对应应用绑定的 WorkerGroup"
      });
  });
const snapshotSchema = z.strictObject({
  revision: z.number().int().positive(),
  configuration: settingsSchema
});
export const configurationViewSchema = z.strictObject({
  saved: snapshotSchema,
  effective: snapshotSchema,
  restartRequired: z.boolean(),
  newScopeRequired: z.boolean()
});
export type AppCheckSettings = z.infer<typeof settingsSchema>;
export type AppCheckConfigurationView = z.infer<typeof configurationViewSchema>;
export interface ConfigurationSession {
  snapshot?: AppCheckConfigurationView;
  draft?: AppCheckSettings;
  baseRevision?: number;
  readAt: number;
  error: string;
  notice: string;
  uncertain: boolean;
  stale: boolean;
  saving: boolean;
}
export function settingsIdentity(value: AppCheckSettings) {
  const parsed = settingsSchema.parse(value);
  return JSON.stringify({
    ...parsed,
    poolGroups: Object.fromEntries(
      Object.entries(parsed.poolGroups).sort(([a], [b]) => a.localeCompare(b))
    )
  });
}
export function requireSettingsUpdate(
  previous: AppCheckSettings,
  next: AppCheckSettings
) {
  for (const app of previous.applications) {
    const updated = next.applications.find(
      (candidate) => candidate.appId === app.appId
    );
    if (!updated || updated.workerGroupId !== app.workerGroupId)
      throw new Error("已有应用不可删除或换绑 WorkerGroup：" + app.appId);
  }
}
