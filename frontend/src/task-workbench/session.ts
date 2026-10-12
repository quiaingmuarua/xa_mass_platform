import { inject, reactive, type InjectionKey } from "vue";
import { createProjectWorkspace } from "@/task-management/workspace";
import type { ProjectSource } from "@/task-management/projects";

export function createTaskSession(source: ProjectSource) {
  // Only Runtime owns the shared directory reader. Each surface has its own
  // detail request slot, so navigating elsewhere cannot replace a saved snapshot.
  const runtime = createProjectWorkspace(source);
  const finite = createProjectWorkspace(source);
  const rpc = createProjectWorkspace(source);
  const runtimeFilters = reactive<
    Record<string, { search: string; group: string; kind: string; scroll: number }>
  >({});
  const finiteFilter = reactive({ search: "", group: "", status: "", scroll: 0 });
  const navigation = reactive({
    runtimeQuery: {} as Record<string, string>,
    group: ""
  });
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
    finiteFilter,
    navigation,
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
