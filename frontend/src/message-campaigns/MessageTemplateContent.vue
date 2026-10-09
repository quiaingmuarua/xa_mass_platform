<script setup lang="ts">
defineProps<{ content?: string }>();
</script>
<template>
  <section class="message-template-content">
    <h3>模板内容（Template content）</h3>
    <pre
      v-if="content"
      class="message-template-text"
      tabindex="0"
      role="region"
      aria-label="模板内容预览"
      >{{ content }}</pre
    >
    <p v-else class="message-hint">未提供模板内容</p>
  </section>
</template>
<style scoped>
.message-template-content {
  margin: 18px 0;
}
h3 {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
}
.message-template-text {
  max-height: 180px;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--rv-border);
  border-radius: 6px;
  background: var(--rv-surface-raised);
  color: var(--rv-text);
  font-size: 13px;
  line-height: 1.7;
}
.message-template-text:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
</style>
