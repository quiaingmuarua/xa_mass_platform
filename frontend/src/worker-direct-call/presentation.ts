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
    const succeeded =
      target.messageType === "platform.worker.command.succeeded" ||
      target.messageType === "platform.adapter.command.succeeded";
    const failed =
      target.messageType === "platform.worker.command.failed" ||
      target.messageType === "platform.adapter.command.failed";
    const diagnostic = target.diagnosticCode ? ` · ${target.diagnosticCode}` : "";
    return succeeded
      ? {
          label: `Observed · Succeeded${diagnostic}`,
          tone: "success",
          description: "已观察到命令处理成功。"
        }
      : {
          label: `Observed · ${failed ? "Failed" : target.messageType}${diagnostic}`,
          tone: "warning",
          description: failed
            ? "已观察到命令处理失败；诊断码不决定结果语义。"
            : "已观察到未识别的结果事件，不能推断执行成功。"
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
