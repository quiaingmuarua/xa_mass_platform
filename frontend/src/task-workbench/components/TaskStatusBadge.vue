<script setup lang="ts">
import { computed } from "vue";
import type { TaskRow } from "@/components/RawTaskTable.vue";
import { presentFiniteStatus, presentRpcStatus } from "../presentation";

const props = defineProps<{
  row: TaskRow;
  mode: "finite" | "rpc";
  showRawBand?: boolean;
}>();

const status = computed(() =>
  props.mode === "rpc" ? presentRpcStatus(props.row) : presentFiniteStatus(props.row)
);
</script>
<template>
  <span class="tw-status" :title="'底层 Score band: ' + status.rawBand">
    <el-tag :type="status.tone" effect="light" size="small">
      {{ status.label }}
    </el-tag>
    <span v-if="status.archived" class="tw-status__archived">已归档</span>
    <code v-if="showRawBand" class="tw-status__raw">{{ status.rawBand }}</code>
  </span>
</template>
