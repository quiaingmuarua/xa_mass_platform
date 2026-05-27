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
  padding: 20px;
  border: 1px solid rgba(18, 32, 51, 0.08);
  border-radius: 18px;
  background: linear-gradient(180deg, #ffffff, #f8fafc);
}

.metric-card--primary {
  background:
    radial-gradient(
      circle at top right,
      rgba(47, 124, 255, 0.18),
      transparent 40%
    ),
    linear-gradient(180deg, #ffffff, #f5f8ff);
}

.metric-card--success {
  background:
    radial-gradient(
      circle at top right,
      rgba(17, 102, 58, 0.16),
      transparent 40%
    ),
    linear-gradient(180deg, #ffffff, #f4fbf7);
}

.metric-card--warning {
  background:
    radial-gradient(
      circle at top right,
      rgba(217, 119, 6, 0.16),
      transparent 40%
    ),
    linear-gradient(180deg, #ffffff, #fff9ed);
}

.metric-card--danger {
  background:
    radial-gradient(
      circle at top right,
      rgba(190, 18, 60, 0.14),
      transparent 40%
    ),
    linear-gradient(180deg, #ffffff, #fff5f6);
}

.metric-card-label {
  color: #6b7a90;
  font-size: 13px;
}

.metric-card-value {
  min-width: 0;
  margin-top: 10px;
  color: #122033;
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
  color: #667085;
  font-size: 12px;
}
</style>
