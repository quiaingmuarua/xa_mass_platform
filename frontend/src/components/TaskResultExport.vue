<script setup lang="ts">
import { watch } from "vue";
import { useRuntimeViewerConfig } from "@/runtime-context";
import { createTaskExport } from "@/task-management/export";
import { HttpFiniteTaskClient } from "@/task-management/http-client";
import type { ProjectTask } from "@/task-management/projects";
const props = defineProps<{ task: ProjectTask; fresh: boolean }>();
const config = useRuntimeViewerConfig();
const { busy, error, download } = createTaskExport(
  config.mode,
  new HttpFiniteTaskClient(config.apiBaseUrl)
);
watch(
  () => props.task.taskId,
  () => {
    error.value = "";
  }
);
</script>
<template>
  <section
    v-if="
      task.task?.idleDisposition === 'CLOSE_WHEN_IDLE' && task.scoreBand === 'terminal'
    "
    class="task-result-export"
  >
    <el-button
      :disabled="config.mode !== 'api' || !fresh"
      :loading="busy"
      @click="download(task, fresh)"
      >导出原始成功结果 JSONL</el-button
    >
    <p class="raw-muted">
      读取完整成功执行结果，不受当前预览和筛选影响；不包含 Scenario 业务筛选。
    </p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </section>
</template>
