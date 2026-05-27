<template>
  <el-tag :type="type" :effect="effect">
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
