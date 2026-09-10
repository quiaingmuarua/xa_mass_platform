<script setup lang="ts">
import { useSmsAvailability } from "./availability";
import SmsWorkspace from "./SmsWorkspace.vue";

const availability = useSmsAvailability();
const state = availability.state;
</script>

<template>
  <SmsWorkspace v-if="state.status === 'enabled'" />
  <section v-else class="sms-unavailable" aria-live="polite">
    <h1>SMS</h1>
    <p v-if="state.status === 'loading'">正在确认 SMS 可用性…</p>
    <p v-else-if="state.status === 'disabled'">当前实例未启用 SMS 接码业务。</p>
    <p v-else-if="state.status === 'demo'">公开 Demo 不支持此业务。</p>
    <template v-else-if="state.status === 'unavailable'">
      <p>{{ state.message }}</p>
      <el-button type="primary" @click="availability.load(true)"
        >重新确认 SMS 可用性</el-button
      >
    </template>
  </section>
</template>

<style scoped>
.sms-unavailable {
  padding: 32px;
  background: var(--rv-surface);
  border: 1px solid var(--rv-border);
  border-radius: 12px;
}
.sms-unavailable p {
  color: var(--rv-text-secondary);
}
</style>
