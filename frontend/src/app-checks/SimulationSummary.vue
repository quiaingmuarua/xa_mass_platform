<script setup lang="ts">
import { computed } from "vue";
import { simulationSection, type ConfigurationSource } from "./configuration";
import ConfigurationRows from "./ConfigurationRows.vue";
const props = defineProps<{
  value: unknown;
  source: ConfigurationSource;
  scope?: "任务" | "场景";
}>();
const section = computed(() =>
  simulationSection(props.value, props.source, props.scope)
);
</script>
<template>
  <div class="checks-simulation-summary">
    <ConfigurationRows :section="section" />
    <p class="checks-hint">
      区间左闭右开，占比描述请求中的模拟参数，不代表本次任务的实际结果比例或完成时间。
    </p>
  </div>
</template>
