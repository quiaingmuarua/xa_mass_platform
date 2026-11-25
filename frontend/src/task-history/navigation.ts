import { canUseConsole, type ProjectAccessSubject } from "@/access-policy";
import type { ProjectTask } from "@/task-management/projects";
import type { HistoryEntry, HistoryKind } from "./source";

export function historyLocation(
  subject: ProjectAccessSubject | null,
  projectId?: string | null,
  historyId?: string | null
) {
  if (canUseConsole(subject, "runtime.view"))
    return {
      path: "/storage/tasks",
      query: { project: projectId || undefined, historyId: historyId || undefined }
    };
  return {
    path: projectId === "messages" ? "/messages" : "/app-checks",
    query: { view: "history", historyId: historyId || undefined }
  };
}

export function historyKind(entry: HistoryEntry): HistoryKind {
  const owner = entry.task.metadata["server.inputOwner"];
  return (owner === "app-checks" || owner === "messages") &&
    entry.task.projectId === owner
    ? owner
    : "raw";
}

export function historySourceLabel(entry: HistoryEntry) {
  const owner = entry.task.metadata["server.inputOwner"];
  return (
    (
      { raw: "原始任务", "app-checks": "批量检查", messages: "Messages" } as Record<
        string,
        string
      >
    )[owner] ??
    owner ??
    "未标注"
  );
}

export function matchesRuntime(entry: HistoryEntry, current: ProjectTask) {
  const creationKey = entry.task.metadata["server.creationKey"];
  return (
    entry.createdAtMillis !== null &&
    entry.task.projectId !== null &&
    current.taskId === entry.taskId &&
    current.task?.projectId === entry.task.projectId &&
    current.createdAtMillis === entry.createdAtMillis &&
    (!creationKey || current.task.metadata["server.creationKey"] === creationKey)
  );
}
