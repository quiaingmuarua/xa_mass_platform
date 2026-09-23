<script setup lang="ts">
import { useMessageAvailability } from "./availability";
import MessageTaskWorkspace from "./MessageTaskWorkspace.vue";
const availability = useMessageAvailability();
const state = availability.state;
</script>

<template>
  <MessageTaskWorkspace v-if="state.status === 'demo' || state.status === 'enabled'" />
  <section v-else class="message-unavailable" aria-live="polite">
    <h1>Messages</h1>
    <p v-if="state.status === 'loading'">正在确认 Messages 可用性…</p>
    <p v-else-if="state.status === 'disabled'">当前实例未启用消息触达场景。</p>
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
