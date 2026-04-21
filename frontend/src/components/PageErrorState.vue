<template>
  <el-card class="page-card">
    <el-result icon="error" title="Load failed" :sub-title="message">
      <template #extra>
        <el-button type="primary" @click="$emit('retry')">Retry</el-button>
      </template>
    </el-result>
  </el-card>
</template>

<script setup lang="ts">
defineProps<{
  message: string
}>()

defineEmits<{
  retry: []
}>()
</script>
