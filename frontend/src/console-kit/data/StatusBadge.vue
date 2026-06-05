<template>
  <el-tag
    class="status-badge"
    :class="`status-badge--${type}`"
    :type="type"
    :effect="effect"
  >
    {{ status }}
  </el-tag>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    status: string
    type?: 'success' | 'info' | 'warning' | 'primary' | 'danger'
    effect?: 'dark' | 'light' | 'plain'
  }>(),
  {
    type: 'info',
    effect: 'light',
  },
)
</script>

<style scoped>
.status-badge {
  border-radius: var(--radius-card);
  font-weight: 700;
}

.status-badge--primary {
  --el-tag-bg-color: var(--color-primary-soft);
  --el-tag-border-color: var(--color-primary-soft);
  --el-tag-text-color: var(--color-primary);
}

.status-badge--success {
  --el-tag-bg-color: var(--color-success-bg);
  --el-tag-border-color: var(--color-success-bg);
  --el-tag-text-color: var(--color-success-text);
}

.status-badge--warning {
  --el-tag-bg-color: var(--color-warning-bg);
  --el-tag-border-color: var(--color-warning-bg);
  --el-tag-text-color: var(--color-warning-text);
}

.status-badge--danger {
  --el-tag-bg-color: var(--color-danger-bg);
  --el-tag-border-color: var(--color-danger-bg);
  --el-tag-text-color: var(--color-danger-text);
}

.status-badge--info {
  --el-tag-bg-color: var(--color-info-bg);
  --el-tag-border-color: var(--color-info-bg);
  --el-tag-text-color: var(--color-info-text);
}
</style>
