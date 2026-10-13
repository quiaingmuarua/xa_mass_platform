import { inject, reactive, type InjectionKey } from "vue";
import { createProjectWorkspace } from "@/task-management/workspace";
import type { ProjectSource } from "@/task-management/projects";
import type { RpcTaskPreparationClient, RpcTaskPrepareRequest } from "./rpc-task";
import { TaskCallDebugError } from "@/task-call-debug/errors";

export function createTaskSession(
  source: ProjectSource,
  preparations: RpcTaskPreparationClient,
  mode: "api" | "mock"
) {
  // Only Runtime owns the shared directory reader. Each surface has its own
  // detail request slot, so navigating elsewhere cannot replace a saved snapshot.
  const runtime = createProjectWorkspace(source);
  const finite = createProjectWorkspace(source);
  const rpc = createProjectWorkspace(source);
  const runtimeFilters = reactive<
    Record<string, { search: string; group: string; kind: string; scroll: number }>
  >({});
  const projects = reactive<
    Record<
      string,
      {
        finiteFilter: { search: string; group: string; status: string; scroll: number };
        rpcQuery: Record<string, string>;
      }
    >
  >(Object.create(null));
  function project(projectId: string) {
    projects[projectId] ??= {
      finiteFilter: { search: "", group: "", status: "", scroll: 0 },
      rpcQuery: {}
    };
    return projects[projectId]!;
  }
  const navigation = reactive({
    runtimeQuery: {} as Record<string, string>,
    projectId: ""
  });
  const rpcPreparations = reactive<
    Record<
      string,
      {
        request: RpcTaskPrepareRequest;
        busy: boolean;
        outcome?: "ready" | "unconfirmed" | "rejected";
        error?: string;
      }
    >
  >(Object.create(null));
  async function prepareRpc(taskId: string, request: RpcTaskPrepareRequest) {
    if (mode !== "api") return;
    const attempt = (rpcPreparations[taskId] ??= {
      request: { ...request, metadata: { ...request.metadata } },
      busy: false
    });
    if (attempt.busy) return;
    attempt.busy = true;
    attempt.error = undefined;
    attempt.outcome = undefined;
    try {
      await preparations.prepareTask(taskId, {
        ...attempt.request,
        metadata: { ...attempt.request.metadata }
      });
      attempt.outcome = "ready";
    } catch (error) {
      attempt.outcome =
        error instanceof TaskCallDebugError && error.status === 400
          ? "rejected"
          : "unconfirmed";
      attempt.error = error instanceof Error ? error.message : "RPC Task 准备未确认。";
    } finally {
      attempt.busy = false;
    }
    return attempt.outcome;
  }
  let initialDirectory: Promise<void> | undefined;
  function ensureDirectory() {
    if (runtime.directory.value) return Promise.resolve();
    return (initialDirectory ??= runtime.loadDirectory().finally(() => {
      initialDirectory = undefined;
    }));
  }
  return {
    directory: runtime.directory,
    loadDirectory: runtime.loadDirectory,
    ensureDirectory,
    runtime,
    finite,
    rpc,
    runtimeFilters,
    project,
    navigation,
    rpcPreparations,
    prepareRpc,
    dispose() {
      runtime.dispose();
      finite.dispose();
      rpc.dispose();
    }
  };
}
export type TaskSession = ReturnType<typeof createTaskSession>;
export const taskSessionKey: InjectionKey<TaskSession> = Symbol("taskSession");
export function useTaskSession() {
  const session = inject(taskSessionKey);
  if (!session) throw new Error("Task session was not provided");
  return session;
}
