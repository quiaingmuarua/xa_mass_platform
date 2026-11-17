<script setup lang="ts">
import { retentionLabel, type TaskRetention } from "@/task-management/retention";
import { computed } from "vue";
import { useRoute } from "vue-router";
const props = defineProps<{ retention?: TaskRetention | null }>();
const route = useRoute();
const history = computed(() => {
  const path = route.path.startsWith("/app-checks")
    ? "/app-checks"
    : route.path.startsWith("/messages")
      ? "/messages"
      : "/task-workbench";
  return {
    path,
    query: {
      ...(path === "/task-workbench"
        ? { project: route.query.project ?? "default", tab: "finite" }
        : {}),
      view: "history",
      historyId: props.retention?.historyId ?? undefined
    }
  };
});
</script>
<template>
  <p v-if="retentionLabel(retention)" class="task-retention" role="status">
    <strong>{{ retentionLabel(retention) }}</strong
    >。
    <template v-if="retention?.phase === 'OPEN'"
      >收尾窗口内，已有执行和业务回执仍可更新。</template
    >
    <template v-else-if="retention?.phase === 'FROZEN'"
      >结果停止更新，历史完整保存后会释放 Runtime 输入和结果。</template
    >
    <template v-else-if="retention?.phase === 'RELEASED'"
      >输入与结果已转入独立历史入口，不再通过 Runtime 读取。
      <router-link v-if="retention.historyId" :to="history"
        >查看历史任务</router-link
      ></template
    >
    <template v-if="retention?.expiresAtMillis"
      >Runtime 记录保留至
      {{ new Date(retention.expiresAtMillis).toLocaleString() }}。</template
    >
    <template v-if="retention?.historyExpiresAtMillis"
      >历史保留至
      {{ new Date(retention.historyExpiresAtMillis).toLocaleString() }}。</template
    >
  </p>
</template>
<style scoped>
.task-retention {
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
</style>
