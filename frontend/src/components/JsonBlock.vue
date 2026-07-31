<script setup lang="ts">
import { computed } from "vue";

import type { JsonValue } from "@/runtime-viewer/types";

const props = defineProps<{
  value: Record<string, JsonValue>;
  emptyLabel?: string;
}>();

const formatted = computed(() =>
  Object.keys(props.value).length === 0
    ? (props.emptyLabel ?? "无")
    : JSON.stringify(props.value, null, 2)
);
</script>

<template>
  <pre class="json-block">{{ formatted }}</pre>
</template>
