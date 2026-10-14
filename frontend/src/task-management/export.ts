import { captureTaskCaller } from "@/task-caller";
import { ref, onScopeDispose } from "vue";
import type { ProjectTask } from "./projects";
import type { FiniteTaskClient } from "./types";
import { presentFiniteTaskError } from "./errors";

export function createTaskExport(
  mode: "api" | "mock",
  client: Pick<FiniteTaskClient, "exportResults">
) {
  const busy = ref(false);
  const error = ref("");
  let disposed = false;
  onScopeDispose(() => {
    disposed = true;
  }, true);
  async function download(task: ProjectTask, fresh: boolean) {
    if (disposed || busy.value) return;
    error.value = "";
    if (
      mode !== "api" ||
      !fresh ||
      task.task?.idleDisposition !== "CLOSE_WHEN_IDLE" ||
      task.scoreBand !== "terminal"
    ) {
      error.value =
        mode !== "api"
          ? "Mock 不执行原始结果导出。"
          : "只能导出最新读取为终态的有限任务。";
      return;
    }
    busy.value = true;
    try {
      const result = await client.exportResults(captureTaskCaller(), task.taskId);
      if (disposed) return;
      if (!result.ready) throw new Error("结果尚未就绪，请刷新后重试。");
      const url = URL.createObjectURL(result.blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = result.fileName;
      anchor.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (cause) {
      error.value = presentFiniteTaskError(cause).message;
    } finally {
      busy.value = false;
    }
  }
  return { busy, error, download };
}
