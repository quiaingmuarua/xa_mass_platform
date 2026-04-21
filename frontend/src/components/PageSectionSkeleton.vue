<template>
  <el-card class="page-card">
    <el-skeleton animated :rows="rows">
      <template #template>
        <el-skeleton-item variant="rect" :style="{ height }" />
      </template>
    </el-skeleton>
  </el-card>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    height?: string
    rows?: number
  }>(),
  {
    height: '320px',
    rows: 6,
  },
)
</script>
