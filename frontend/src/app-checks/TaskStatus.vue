<script setup lang="ts">
import type { CheckTask } from "./model";
import { taskLabel, taskStage, type PreviewState } from "./workbench";
defineProps<{ task: CheckTask; preview?: PreviewState }>();
</script>
<template>
  <span
    class="checks-status"
    :class="[
      'state-' + taskStage(task),
      preview?.endReason ? 'reason-' + preview.endReason : ''
    ]"
    >{{ taskLabel(task, preview) }}</span
  >
</template>
