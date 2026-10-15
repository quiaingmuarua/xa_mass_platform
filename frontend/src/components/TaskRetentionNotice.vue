<script setup lang="ts">
import { retentionLabel, type TaskRetention } from "@/task-management/retention";
defineProps<{ retention?: TaskRetention | null }>();
</script>
<template>
  <p v-if="retentionLabel(retention)" class="task-retention" role="status">
    <strong>{{ retentionLabel(retention) }}</strong
    >。
    <template v-if="retention?.phase === 'OPEN'"
      >收尾窗口内，已有执行和业务回执仍可更新。</template
    >
    <template v-else-if="retention?.phase === 'FROZEN'"
      >结果停止更新，归档完成后继续支持预览和导出。</template
    >
    <template v-else-if="retention?.phase === 'RELEASED'"
      >可预览和导出，结果不再更新，也不支持按消息 ID 点查。</template
    >
    <template v-if="retention?.expiresAtMillis"
      >保留至
      {{
        new Date(retention.expiresAtMillis).toLocaleString()
      }}，随后清理任务及归档。</template
    >
  </p>
</template>
<style scoped>
.task-retention {
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
</style>
