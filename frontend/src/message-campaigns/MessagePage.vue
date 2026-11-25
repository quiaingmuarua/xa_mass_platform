<script setup lang="ts">
import { useMessageAvailability } from "./availability";
import MessageTaskWorkspace from "./MessageTaskWorkspace.vue";
import { useRoute } from "vue-router";
import TaskHistoryWorkspace from "@/task-history/TaskHistoryWorkspace.vue";
import { consoleIdentity } from "@/console-identity";
import { historyLocation } from "@/task-history/navigation";
const route = useRoute();
const availability = useMessageAvailability();
const state = availability.state;
</script>

<template>
  <TaskHistoryWorkspace
    v-if="route.query.view === 'history'"
    project-id="messages"
    runtime-path="/messages"
  />
  <router-link
    v-else
    :to="historyLocation(consoleIdentity.current.value, 'messages')"
    class="history-entry"
    >历史任务</router-link
  >
  <MessageTaskWorkspace
    v-if="
      route.query.view !== 'history' &&
      (state.status === 'demo' || state.status === 'enabled')
    "
  />
  <section
    v-else-if="route.query.view !== 'history'"
    class="message-unavailable"
    aria-live="polite"
  >
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
