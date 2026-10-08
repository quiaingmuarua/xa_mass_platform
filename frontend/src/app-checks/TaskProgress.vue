<script setup lang="ts">
import { computed } from "vue";
import { taskProgress, type CheckTask } from "./model";
import { quantity } from "./workbench";
const props = defineProps<{ task: CheckTask }>();
const processed = computed(() =>
  props.task.succeededCount === undefined || props.task.failedCount === undefined
    ? undefined
    : props.task.succeededCount + props.task.failedCount
);
</script>
<template>
  <div class="checks-progress-mini">
    <div class="checks-progress-caption">
      <span>{{ quantity(processed) }} / {{ quantity(task.totalCount) }}</span>
      <strong>{{ processed === undefined ? "—" : taskProgress(task) + "%" }}</strong>
    </div>
    <div
      class="checks-progress-track"
      role="progressbar"
      aria-label="任务处理进度"
      :aria-valuenow="processed === undefined ? undefined : taskProgress(task)"
      :aria-valuemin="0"
      :aria-valuemax="100"
    >
      <span
        :style="{ width: (processed === undefined ? 0 : taskProgress(task)) + '%' }"
      ></span>
    </div>
  </div>
</template>
