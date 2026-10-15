<script setup lang="ts">
import { nextTick, ref } from "vue";
import { ElLoading, type TableInstance } from "element-plus";
import type { ProjectTask } from "@/task-management/projects";
import "element-plus/es/components/loading/style/css";
export type TaskRow = Omit<ProjectTask, "createdAtMillis"> & {
  createdAtMillis?: number | null;
};
defineProps<{ rows: TaskRow[]; loading?: boolean; error?: string; rpc?: boolean }>();
const emit = defineEmits<{
  open: [row: TaskRow];
  scroll: [event: { scrollTop: number }];
}>();
const vLoading = ElLoading.directive;
const table = ref<TableInstance>();
async function restoreScrollTop(top: number) {
  await nextTick();
  await new Promise<void>((resolve) =>
    requestAnimationFrame(() => {
      table.value?.setScrollTop(top);
      requestAnimationFrame(() => resolve());
    })
  );
}
defineExpose({ restoreScrollTop });
function time(value?: string | number | null) {
  return value == null ? "未提供" : new Date(value).toLocaleString();
}
</script>
<template>
  <el-table
    ref="table"
    v-loading="!!loading"
    :data="rows"
    row-key="taskId"
    height="560"
    class="raw-tasks__table"
    @scroll="emit('scroll', $event)"
  >
    <el-table-column label="任务 / 编号" min-width="240"
      ><template #default="{ row }">
        <el-button v-if="row.task" link type="primary" @click="emit('open', row)">{{
          row.task.name || row.taskId
        }}</el-button>
        <code v-else>{{ row.taskId }}</code
        ><small v-if="row.task?.name">{{ row.taskId }}</small>
        <small v-if="!row.task">Task 描述符缺失</small>
      </template></el-table-column
    >
    <el-table-column label="Project / Group" min-width="145"
      ><template #default="{ row }"
        >{{ row.task?.projectId ?? "未提供"
        }}<small>{{ row.task?.workerGroupId ?? "未提供" }}</small></template
      ></el-table-column
    >
    <el-table-column v-if="rpc" label="创建者" min-width="115"
      ><template #default="{ row }">{{
        row.task?.metadata.userId ?? "未提供"
      }}</template></el-table-column
    >
    <el-table-column v-else label="生命周期" min-width="165"
      ><template #default="{ row }">{{
        row.task?.idleDisposition ?? "未提供"
      }}</template></el-table-column
    >
    <el-table-column label="Score band" min-width="140"
      ><template #default="{ row }"
        ><el-tag effect="plain">{{ row.scoreBand ?? "状态不可用" }}</el-tag></template
      ></el-table-column
    >
    <el-table-column label="Pool 供给" min-width="130" show-overflow-tooltip
      ><template #default="{ row }">{{
        row.task
          ? row.task.refill
              .map(
                (r: { poolName: string; count: number }) => r.poolName + " × " + r.count
              )
              .join("、") || "无供给"
          : "未提供"
      }}</template></el-table-column
    >
    <el-table-column label="创建时间" min-width="165"
      ><template #default="{ row }">{{
        time(row.createdAtMillis)
      }}</template></el-table-column
    >
    <el-table-column label="操作" fixed="right" width="100">
      <template #default="{ row }"
        ><slot name="actions" :row="row"
          ><el-button link :disabled="!row.task" @click="emit('open', row)"
            >查看</el-button
          ></slot
        ></template
      >
    </el-table-column>
    <template #empty>{{
      error ? "读取失败，请刷新重试" : "当前载入集合没有匹配任务"
    }}</template>
  </el-table>
</template>
