<template>
  <PagePlaceholder
    title="Configs"
    description="Config inspection and safe control-plane overrides."
    heading="Config placeholder"
    body="The first real iteration should keep configuration editing tightly permission-guarded."
    permission="config:view"
  />
</template>

<script setup lang="ts">
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>
