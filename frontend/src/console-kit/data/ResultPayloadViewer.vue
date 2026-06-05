<template>
  <pre
    class="result-payload-viewer"
    :class="{ 'is-truncated': truncated }"
  >{{ rendered }}</pre>
</template>

<script setup lang="ts">
import {computed} from 'vue'

const props = withDefaults(
  defineProps<{
    value: unknown
    maxLength?: number
  }>(),
  {
    maxLength: 4096,
  },
)

const formatted = computed(() => formatPayload(props.value))
const truncated = computed(() => formatted.value.length > props.maxLength)
const rendered = computed(() => {
  if (!truncated.value) {
    return formatted.value
  }

  const visible = formatted.value.slice(0, props.maxLength)
  const omitted = formatted.value.length - props.maxLength
  return `${visible}\n... truncated ${omitted} chars`
})

function formatPayload(value: unknown): string {
  if (value === null || value === undefined) {
    return ''
  }

  if (typeof value === 'string') {
    return value
  }

  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}
</script>

<style scoped>
.result-payload-viewer {
  max-height: 320px;
  min-width: 0;
  margin: 0;
  overflow: auto;
  padding: 12px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
  background: var(--color-surface-muted);
  color: var(--color-text-muted);
  font-family:
    'JetBrains Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo,
    monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.result-payload-viewer.is-truncated {
  border-color: var(--color-warning-bg);
}
</style>
