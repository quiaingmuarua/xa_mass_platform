<script setup lang="ts">
import { computed } from "vue";

import type { JsonValue } from "@/runtime-viewer/types";

const props = defineProps<{
  value: Record<string, JsonValue> | null;
  emptyLabel?: string;
}>();

const formatted = computed(() => {
  if (props.value === null) {
    return "null";
  }
  return Object.keys(props.value).length === 0
    ? (props.emptyLabel ?? "无")
    : JSON.stringify(props.value, null, 2);
});
</script>

<template>
  <pre class="json-block">{{ formatted }}</pre>
</template>
