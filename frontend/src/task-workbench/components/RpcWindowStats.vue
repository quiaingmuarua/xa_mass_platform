<script setup lang="ts">
import { computed } from "vue";
import type { TaskStats } from "@/task-management/projects";
import { summarizeTaskStats } from "../presentation";

const props = defineProps<{
  stats?: TaskStats | null;
  detailed?: boolean;
}>();

const summary = computed(() => summarizeTaskStats(props.stats));
</script>
<template>
  <div
    v-if="summary"
    class="tw-rpc-stats"
    :class="{ 'tw-rpc-stats--detailed': detailed }"
    title="来自 Redis 当前保留窗口统计，终态项随保留策略回收，非永久累计用量"
  >
    <div class="tw-rpc-stats__grid">
      <div class="tw-rpc-stats__cell">
        <span class="tw-rpc-stats__label">在途</span>
        <strong
          class="tw-rpc-stats__value"
          :class="{ 'is-active': summary.active > 0 }"
        >
          {{ summary.active.toLocaleString() }}
        </strong>
      </div>
      <div class="tw-rpc-stats__cell">
        <span class="tw-rpc-stats__label">窗口成功</span>
        <strong class="tw-rpc-stats__value">
          {{ summary.succeeded.toLocaleString() }}
        </strong>
      </div>
      <div class="tw-rpc-stats__cell">
        <span class="tw-rpc-stats__label">窗口失败</span>
        <strong
          class="tw-rpc-stats__value"
          :class="{ 'is-danger': summary.failed > 0 }"
        >
          {{ summary.failed.toLocaleString() }}
        </strong>
      </div>
    </div>
    <p v-if="detailed" class="tw-rpc-stats__note raw-muted">
      当前 Redis 保留窗口统计（非永久累计用量；终态项随保留回收后回落）。
    </p>
  </div>
  <div v-else class="tw-rpc-stats tw-rpc-stats--empty">
    <span class="raw-muted">窗口统计暂不可用</span>
  </div>
</template>
