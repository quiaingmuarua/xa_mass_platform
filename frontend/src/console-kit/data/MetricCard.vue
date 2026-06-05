<template>
  <div class="metric-card" :class="[`metric-card--${tone}`]">
    <div class="metric-card-label">{{ label }}</div>
    <div class="metric-card-value" :class="{ 'is-compact': compact }">
      {{ value }}
    </div>
    <div v-if="hint" class="metric-card-hint">{{ hint }}</div>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    value: string | number
    hint?: string
    tone?: 'default' | 'primary' | 'success' | 'warning' | 'danger'
    compact?: boolean
  }>(),
  {
    hint: '',
    tone: 'default',
    compact: false,
  },
)
</script>

<style scoped>
.metric-card {
  min-width: 0;
  padding: 16px 18px;
  border: 1px solid var(--color-border);
  border-left: 3px solid var(--metric-accent, var(--color-border-strong));
  border-radius: var(--radius-card);
  background: var(--color-surface-strong);
  box-shadow: var(--shadow-card);
}

.metric-card--primary {
  --metric-accent: var(--color-primary);
  background: var(--color-primary-soft);
}

.metric-card--success {
  --metric-accent: var(--color-success-text);
  background: var(--color-success-bg);
}

.metric-card--warning {
  --metric-accent: var(--color-warning-text);
  background: var(--color-warning-bg);
}

.metric-card--danger {
  --metric-accent: var(--color-danger-text);
  background: var(--color-danger-bg);
}

.metric-card-label {
  color: var(--color-text-subtle);
  font-size: 13px;
}

.metric-card-value {
  min-width: 0;
  margin-top: 10px;
  color: var(--color-text-strong);
  font-size: 30px;
  font-weight: 760;
  line-height: 1.12;
}

.metric-card-value.is-compact {
  overflow: hidden;
  font-size: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-card-hint {
  margin-top: 8px;
  color: var(--color-text-muted);
  font-size: 12px;
}
</style>
