import type { WorkerDirectCallTargetResult } from "./types";

export type WorkerDirectCallTone = "success" | "warning" | "danger";

export interface WorkerDirectCallPresentation {
  label: string;
  tone: WorkerDirectCallTone;
  description: string;
}

export function presentWorkerDirectCallTarget(
  target: WorkerDirectCallTargetResult
): WorkerDirectCallPresentation {
  if (target.status === "observed") {
    return target.outcomeCode === "200"
      ? {
          label: "Observed · 200",
          tone: "success",
          description: "已观察到 Worker 成功 Result。"
        }
      : {
          label: `Observed · ${target.outcomeCode}`,
          tone: "warning",
          description:
            "已观察到 Worker Result，但 outcomeCode 不是 200，不能描述为执行成功。"
        };
  }
  if (target.status === "unobserved") {
    return {
      label: `Unobserved · ${target.reason}`,
      tone: "warning",
      description: "等待窗口内没有观察到 Result；这不证明 Worker 没有执行该 Event。"
    };
  }
  return {
    label: `Rejected · ${target.reason}`,
    tone: "danger",
    description: "Direct Call 在投递前被拒绝，没有形成 Task 或调度结论。"
  };
}
