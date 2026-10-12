import {
  appLabel,
  countryLabel,
  simulationSchema,
  type Catalog,
  type CheckTask
} from "./model";
import type { AppCheckSettings } from "./settings";

export interface TaskPolicySnapshot {
  priority: number;
  maxRetries: number;
  validityMillis: number;
  supply: { poolName: string; target: string; count: number };
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
      row("单次号码上限", `${number(catalog.limits.numbersPerImport)} 个`),
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
  scope: "应用" | "场景" | "任务" | "Pool / Group";
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
          row("有效期", `${number(policy.validityMillis / 86400000)} 天`),
          row(
            "供给目标",
            `${policy.supply.poolName} / ${policy.supply.target} / ${number(policy.supply.count)}`
          )
        ]
      : [],
    unavailable: policy ? undefined : `${absent}：优先级、最大重试、有效期、供给目标。`
  };
}

export function applicationConfiguration(
  catalog: Catalog,
  appId: string,
  mode: "api" | "mock",
  settings?: AppCheckSettings,
  origin: ConfigurationSource = "saved"
) {
  const app = (settings?.applications ?? catalog.apps).find(
    (candidate) => candidate.appId === appId
  );
  if (!app) return undefined;
  const source = mode === "mock" ? "mock" : settings ? origin : "catalog";
  const limit = settings?.poolGroups[app.workerGroupId];
  return {
    binding: {
      source,
      scope: "应用",
      rows: [
        row("应用名称", app.label),
        row("应用 ID", appId),
        row("WorkerGroup", app.workerGroupId)
      ]
    } satisfies ConfigurationSection,
    pool: {
      source,
      scope: "Pool / Group",
      rows: limit
        ? [
            row("Pool", "assignment-window"),
            row("WorkerGroup", app.workerGroupId),
            row("观察窗口", `${number(limit.windowMillis / 1000)} 秒`),
            row("观察阈值", `单个 Worker 每窗口 ${number(limit.maxCount)} 次`)
          ]
        : [],
      unavailable: limit
        ? undefined
        : `${absent}：Pool 身份、Group 范围、窗口长度、观察阈值。`
    } satisfies ConfigurationSection
  };
}

export function savedTaskConfiguration(task: CheckTask, mode: "api" | "mock") {
  return {
    binding: {
      source: mode === "mock" ? "mock" : "task",
      scope: "任务",
      rows: [
        row("应用", task.appId ? `${appLabel(task.appId)} / ${task.appId}` : undefined),
        row("实际 WorkerGroup", task.workerGroupId),
        row("号码地区", task.country ? countryLabel(task.country) : undefined)
      ]
    } satisfies ConfigurationSection,
    policy: policySection(
      mode === "mock" ? task.mockPolicy : undefined,
      mode === "mock" ? "mock" : "task"
    )
  };
}

export function simulationSection(
  value: unknown,
  source: ConfigurationSource,
  scope: "任务" | "场景" = "任务"
): ConfigurationSection {
  const parsed = simulationSchema.safeParse(value);
  if (!parsed.success)
    return {
      source,
      scope,
      rows: [],
      unavailable:
        value === undefined ? absent : "模拟参数无法解析，仍可预览结果及管理任务。"
    };
  const { ranges, delayMs } = parsed.data;
  return {
    source,
    scope,
    rows: [
      ...(
        [
          ["已注册", ranges.registered],
          ["未注册", ranges.unregistered],
          ["查询失败", ranges.failed]
        ] as const
      ).map(([label, [start, end]]) =>
        row(`${label}区间`, `${(end - start) / 10}% · [${start}, ${end})`)
      ),
      row("模拟延迟", `${number(delayMs[0])}–${number(delayMs[1])} ms`)
    ]
  };
}
