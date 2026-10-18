import { checkLabel, countryLabel, type Catalog, type CheckTask } from "./model";

export interface TaskPolicySnapshot {
  priority: number;
  maxRetries: number;
  validityMillis: number;
}
export function scenarioConfiguration(
  catalog: Catalog,
  mode: "api" | "mock"
): ConfigurationSection {
  return {
    source: mode === "mock" ? "mock" : "catalog",
    scope: "场景",
    rows: [
      row("Project", catalog.projectId),
      row("支持国家", catalog.countries.map(countryLabel).join("、")),
      row("单次种子上限", `${number(catalog.limits.seedsPerImport)} 个`),
      row("单次文件上限", `${number(catalog.limits.importFileBytes / 1024 / 1024)} MiB`)
    ]
  };
}
export type ConfigurationSource =
  | "catalog"
  | "task"
  | "draft"
  | "mock"
  | "saved"
  | "effective";
export interface ConfigurationSection {
  source: ConfigurationSource;
  scope: "检查配置" | "场景" | "任务" | "Pool / Group";
  rows: { label: string; value: string }[];
  unavailable?: string;
}
export interface ConfigurationReadState {
  readAt: number;
  busy: boolean;
  error: string;
}
export const configurationSources: Record<ConfigurationSource, string> = {
  catalog: "Catalog",
  task: "任务保存值",
  draft: "当前草稿保存值",
  mock: "Mock 演示数据",
  saved: "已保存配置",
  effective: "本次进程生效配置"
};
const absent = "当前接口未提供";
const number = (value: number) => value.toLocaleString("zh-CN");
const row = (label: string, value?: string | null) => ({
  label,
  value: value || absent
});

export function policySection(
  policy: TaskPolicySnapshot | undefined,
  source: ConfigurationSource
): ConfigurationSection {
  return {
    source,
    scope: "任务",
    rows: policy
      ? [
          row("优先级", number(policy.priority)),
          row("最大重试", `${number(policy.maxRetries)} 次`),
          row("有效期", `${number(policy.validityMillis / 86400000)} 天`)
        ]
      : [],
    unavailable: policy ? undefined : `${absent}：优先级、最大重试、有效期。`
  };
}

export function savedTaskConfiguration(task: CheckTask, mode: "api" | "mock") {
  return {
    binding: {
      source: mode === "mock" ? "mock" : "task",
      scope: "任务",
      rows: [
        row(
          "检查项",
          task.checkId
            ? `${task.checkLabel ?? checkLabel(task.checkId)} / ${task.checkId}`
            : undefined
        ),
        row("检查类型", task.checkTypeLabel),
        row("种子格式", task.seedType),
        row("实际 WorkerGroup", task.workerGroupId),
        ...(task.seedType === "phonenumber"
          ? [row("号码地区", task.country ? countryLabel(task.country) : undefined)]
          : []),
        row("业务契约", task.checkType),
        row(
          "固定单价",
          task.unitPrice === undefined ? undefined : `${task.unitPrice} 积分／种子`
        ),
        row("执行事件", task.eventCode),
        row(
          "每个执行项种子上限",
          task.seedsPerItem === undefined ? undefined : String(task.seedsPerItem)
        ),
        row(
          "Worker 查询",
          task.workerSelector ? JSON.stringify(task.workerSelector) : undefined
        ),
        row("供给声明", task.refill ? JSON.stringify(task.refill) : undefined)
      ]
    } satisfies ConfigurationSection,
    policy: policySection(
      mode === "mock" ? task.mockPolicy : undefined,
      mode === "mock" ? "mock" : "task"
    )
  };
}
