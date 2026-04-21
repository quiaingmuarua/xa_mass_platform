<template>
  <el-card class="page-card">
    <el-result icon="error" title="Load failed" :sub-title="message">
      <template v-if="showRetry" #extra>
        <el-button type="primary" @click="$emit('retry')">Retry</el-button>
      </template>
    </el-result>
  </el-card>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
  message: string
  showRetry?: boolean
}>(),
  {
    showRetry: true,
  },
)

defineEmits<{
  retry: []
}>()
</script>
