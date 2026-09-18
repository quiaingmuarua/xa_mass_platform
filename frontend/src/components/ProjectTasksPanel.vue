<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import {
  loadProject,
  loadProjectTasks,
  type Project,
  type ProjectTasks
} from "@/task-management/projects";

const props = defineProps<{
  projectId?: string;
  baseUrl: string;
  disabled?: boolean;
}>();
const emit = defineEmits<{ selected: [project: Project | undefined] }>();
const input = ref(props.projectId ?? "");
const result = ref<ProjectTasks>();
const busy = ref(false);
const error = ref("");
let generation = 0;
let active: AbortController | undefined;
onBeforeUnmount(() => {
  generation++;
  active?.abort();
});
watch(
  () => props.projectId,
  (id) => {
    input.value = id ?? "";
    reset();
  }
);
function reset() {
  generation++;
  result.value = undefined;
  emit("selected", undefined);
}
async function load() {
  if (props.disabled || busy.value || !input.value.trim()) return;
  const id = input.value.trim();
  const request = ++generation;
  const controller = new AbortController();
  active = controller;
  const deadline = setTimeout(() => controller.abort(), 5_000);
  busy.value = true;
  error.value = "";
  try {
    const project = await loadProject(props.baseUrl, id, controller.signal);
    const tasks = await loadProjectTasks(props.baseUrl, id, controller.signal);
    if (request !== generation) return;
    result.value = tasks;
    emit("selected", project);
  } catch (failure) {
    if (request === generation) {
      result.value = undefined;
      emit("selected", undefined);
      error.value = failure instanceof Error ? failure.message : "Project 加载失败";
    }
  } finally {
    clearTimeout(deadline);
    active = undefined;
    busy.value = false;
  }
}
</script>

<template>
  <section
    class="worker-panel project-tasks"
    aria-label="Project Tasks"
    data-testid="project-tasks"
  >
    <form @submit.prevent="load">
      <label
        >Project
        <input
          v-model="input"
          :readonly="!!projectId"
          :disabled="disabled || busy"
          @input="reset"
      /></label>
      <el-button
        native-type="submit"
        :loading="busy"
        :disabled="disabled || !input.trim()"
        >加载／刷新任务</el-button
      >
    </form>
    <p v-if="disabled">Mock Demo 不查询 Project。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="result?.truncated" role="status">仅显示最新 100 个 Task，列表已截断。</p>
    <p v-if="result && result.tasks.length === 0">此 Project 暂无 Task。</p>
    <table v-if="result && result.tasks.length" class="project-table">
      <thead>
        <tr>
          <th>Task</th>
          <th>创建时间</th>
          <th>WorkerGroup</th>
          <th>Score 状态</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in result.tasks" :key="row.taskId">
          <td>{{ row.taskId }}</td>
          <td>{{ new Date(row.createdAtMillis).toLocaleString() }}</td>
          <td>{{ row.task?.workerGroupId ?? "—" }}</td>
          <td>{{ row.scoreBand ?? "—" }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.project-tasks {
  padding: 16px;
  overflow-x: auto;
}
form {
  display: flex;
  align-items: center;
  gap: 16px;
}
input {
  margin-left: 8px;
  padding: 6px;
}
.project-table {
  width: 100%;
  margin-top: 16px;
  border-collapse: collapse;
  text-align: left;
}
th,
td {
  padding: 8px;
  border-bottom: 1px solid var(--rv-border);
}
</style>
