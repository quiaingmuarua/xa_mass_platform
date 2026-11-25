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
  <div v-if="summary" class="tw-stats" :class="{ 'tw-stats--detailed': detailed }">
    <div class="tw-stats__headline">
      <span class="tw-stats__ratio">
        <strong>{{ summary.completed.toLocaleString() }}</strong>
        <span class="tw-stats__sep">/</span>
        <span>{{ summary.total.toLocaleString() }}</span>
      </span>
      <span v-if="summary.total > 0" class="tw-stats__percent">
        {{ summary.percent }}%
      </span>
    </div>
    <div
      v-if="summary.total > 0"
      class="tw-stats__bar"
      role="progressbar"
      :aria-valuenow="summary.percent"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-label="`已完成 ${summary.completed} / ${summary.total}`"
    >
      <span
        v-if="summary.succeededPercent > 0"
        class="tw-stats__seg tw-stats__seg--success"
        :style="{ width: summary.succeededPercent + '%' }"
      />
      <span
        v-if="summary.failedPercent > 0"
        class="tw-stats__seg tw-stats__seg--danger"
        :style="{ width: summary.failedPercent + '%' }"
      />
    </div>
    <div class="tw-stats__breakdown">
      <span v-if="summary.succeeded > 0 || summary.total === 0">
        成功 {{ summary.succeeded.toLocaleString() }}
      </span>
      <span v-if="summary.failed > 0" class="tw-stats__metric--danger">
        失败 {{ summary.failed.toLocaleString() }}
      </span>
      <span v-if="summary.active > 0">
        待执行 {{ summary.active.toLocaleString() }}
      </span>
      <span v-if="summary.observedLater > 0" class="tw-stats__metric--muted">
        后续观测 {{ summary.observedLater.toLocaleString() }}
      </span>
      <span v-if="summary.other > 0" class="tw-stats__metric--muted">
        其他 {{ summary.other.toLocaleString() }}
      </span>
    </div>
  </div>
  <div v-else class="tw-stats tw-stats--empty">
    <span class="raw-muted">规模暂不可用</span>
  </div>
</template>
