import { ref, reactive } from "vue";
import type {
  Project,
  ProjectGroup,
  ProjectSource,
  ProjectTask,
  ProjectTasks
} from "./projects";

export interface Snapshot<T> {
  value?: T;
  readAt?: string;
  loading: boolean;
  stale: boolean;
  error?: string;
}
function snapshot<T>(): Snapshot<T> {
  return { loading: false, stale: false };
}
export function createProjectWorkspace(source: ProjectSource) {
  const directory = reactive(snapshot<Project[]>());
  const windows = reactive<Record<string, Snapshot<ProjectTasks>>>({});
  const groups = reactive<Record<string, Snapshot<ProjectGroup[]>>>({});
  const detail = ref<Snapshot<ProjectTask>>(snapshot());
  const detailKey = ref("");
  const requests = new Map<string, AbortController>();
  const sequences = new Map<string, number>();
  async function read<T>(
    key: string,
    state: Snapshot<T>,
    loader: (signal: AbortSignal) => Promise<T>
  ) {
    requests.get(key)?.abort();
    const controller = new AbortController();
    requests.set(key, controller);
    const sequence = (sequences.get(key) ?? 0) + 1;
    sequences.set(key, sequence);
    state.loading = true;
    state.error = undefined;
    try {
      const value = await loader(controller.signal);
      if (sequences.get(key) !== sequence || controller.signal.aborted) return;
      state.value = value;
      state.readAt = new Date().toISOString();
      state.stale = false;
    } catch (error) {
      if (sequences.get(key) !== sequence || controller.signal.aborted) return;
      state.error = error instanceof Error ? error.message : "读取失败";
      state.stale = true;
    } finally {
      if (sequences.get(key) === sequence) {
        state.loading = false;
        requests.delete(key);
      }
    }
  }
  function loadDirectory() {
    return read("directory", directory, (signal) => source.list(signal));
  }
  function loadTasks(project: string) {
    if (!windows[project]) windows[project] = snapshot();
    const state = windows[project];
    return read("tasks:" + project, state, (signal) => source.tasks(project, signal));
  }
  function loadGroups(project: string) {
    if (!groups[project]) groups[project] = snapshot();
    const state = groups[project];
    return read("groups:" + project, state, (signal) => source.groups(project, signal));
  }
  function loadDetail(project: string, task: string) {
    const key = project + "/" + task;
    if (detailKey.value !== key) {
      detail.value = snapshot();
      detailKey.value = key;
    }
    return read("detail", detail.value, (signal) => source.task(project, task, signal));
  }
  function clearDetail() {
    requests.get("detail")?.abort();
    detailKey.value = "";
    detail.value = snapshot();
  }
  function dispose() {
    requests.forEach((c) => c.abort());
    requests.clear();
  }
  return {
    directory,
    windows,
    groups,
    detail,
    detailKey,
    loadDirectory,
    loadTasks,
    loadGroups,
    loadDetail,
    clearDetail,
    dispose
  };
}
