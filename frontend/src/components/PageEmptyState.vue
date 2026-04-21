<template>
  <el-card class="page-card">
    <el-empty :description="description">
      <template v-if="$slots.default" #default>
        <slot />
      </template>
    </el-empty>
  </el-card>
</template>

<script setup lang="ts">
defineProps<{
  description: string
}>()
</script>
