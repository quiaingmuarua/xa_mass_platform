<script setup lang="ts">
import { retentionLabel, type TaskRetention } from "@/task-management/retention";
import { computed } from "vue";
import { useRoute } from "vue-router";
import { consoleIdentity } from "@/console-identity";
import { historyLocation } from "@/task-history/navigation";
const props = defineProps<{
  retention?: TaskRetention | null;
  projectId?: string | null;
  mode?: "finite" | "rpc";
}>();
const mode = computed(() => props.mode ?? "finite");
const label = computed(() => retentionLabel(props.retention, mode.value));
const route = useRoute();
const history = computed(() => {
  const path = route.path.startsWith("/app-checks")
    ? "/app-checks"
    : route.path.startsWith("/messages")
      ? "/messages"
      : "/runtime/tasks";
  const project =
    props.projectId ??
    (path === "/app-checks"
      ? "app-checks"
      : path === "/messages"
        ? "messages"
        : typeof route.query.project === "string"
          ? route.query.project
          : undefined);
  return historyLocation(
    consoleIdentity.current.value,
    project,
    props.retention?.historyId
  );
});
</script>
<template>
  <p v-if="label" class="task-retention" role="status">
    <strong>{{ label }}</strong
    >。
    <template v-if="mode === 'rpc'">
      <template v-if="retention?.phase === 'PURGED'"
        >该已停用 RPC 通道的 Redis 运行数据已清理（不写入历史归档）。</template
      >
      <template v-else-if="retention?.phase === 'PURGING'"
        >正在清理该已停用 RPC 通道的 Redis 运行数据（不写入历史归档）。</template
      >
      <template v-else
        >已停用 RPC 通道不会写入历史归档；Redis
        数据将在保留宽限期到期后自动清理（管理员可立即清理）。</template
      >
      <template v-if="retention?.expiresAtMillis && retention?.phase !== 'PURGED'"
        >Redis 记录预计保留至
        {{ new Date(retention.expiresAtMillis).toLocaleString() }}。</template
      >
    </template>
    <template v-else>
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
    </template>
  </p>
</template>
<style scoped>
.task-retention {
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
</style>
