<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">{{ title }}</h2>
        <p class="page-subtitle">{{ description }}</p>
      </div>
      <span v-if="permission" class="permission-chip">{{ permission }}</span>
    </header>

    <el-card class="page-card">
      <template #header>
        <strong>{{ heading }}</strong>
      </template>
      <p class="placeholder-copy">
        {{ body }}
      </p>
    </el-card>
  </section>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  description: string
  heading: string
  body: string
  permission?: string
}>()
</script>

<style scoped>
.placeholder-copy {
  margin: 0;
  color: #556277;
}
</style>
