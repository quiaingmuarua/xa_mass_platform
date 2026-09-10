<script setup lang="ts">
import { useMessageAvailability } from "./availability";
import MessageWorkspace from "./MessageWorkspace.vue";
const availability = useMessageAvailability();
const state = availability.state;
</script>

<template>
  <MessageWorkspace v-if="state.status === 'enabled'" :catalog="state.catalog" />
  <section v-else class="message-unavailable" aria-live="polite">
    <h1>Messages</h1>
    <p v-if="state.status === 'loading'">正在确认 Messages 可用性…</p>
    <p v-else-if="state.status === 'disabled'">当前实例未启用消息触达业务。</p>
    <p v-else-if="state.status === 'demo'">公开 Demo 不支持此业务。</p>
    <template v-else-if="state.status === 'unavailable'">
      <p>{{ state.message }}</p>
      <el-button type="primary" @click="availability.load(true)"
        >重新确认 Messages 可用性</el-button
      >
    </template>
  </section>
</template>
<style scoped>
.message-unavailable {
  padding: 32px;
  background: var(--rv-surface);
  border: 1px solid var(--rv-border);
  border-radius: 12px;
}
</style>
