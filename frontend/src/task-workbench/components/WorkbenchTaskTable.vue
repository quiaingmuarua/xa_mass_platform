<script setup lang="ts">
import { nextTick, ref } from "vue";
import { ElLoading, type TableInstance } from "element-plus";
import type { TaskRow } from "@/components/RawTaskTable.vue";
import TaskStatusBadge from "./TaskStatusBadge.vue";
import {
  formatCompactTime,
  formatDateTime,
  formatDuration,
  formatOutcomeItems,
  formatRefillList,
  presentTaskSource
} from "../presentation";
import "element-plus/es/components/loading/style/css";

defineProps<{
  rows: TaskRow[];
  mode: "finite" | "rpc";
  loading?: boolean;
  error?: string;
}>();

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

function endedDuration(row: TaskRow) {
  return formatDuration(row.createdAtMillis, row.retention?.terminatedAtMillis);
}
</script>
<template>
  <el-table
    ref="table"
    v-loading="!!loading"
    :data="rows"
    row-key="taskId"
    height="520"
    class="raw-tasks__table tw-table"
    @scroll="emit('scroll', $event)"
  >
    <el-table-column label="任务" min-width="220">
      <template #default="{ row }">
        <div class="tw-cell-identity">
          <div class="tw-cell-identity__title">
            <el-button v-if="row.task" link type="primary" @click="emit('open', row)">
              {{ row.task.name || row.taskId }}
            </el-button>
            <code v-else>{{ row.taskId }}</code>
          </div>
          <div
            v-if="row.task?.name || !row.task || !presentTaskSource(row).raw"
            class="tw-cell-identity__sub"
          >
            <code v-if="row.task?.name" class="tw-cell-id">{{ row.taskId }}</code>
            <span v-if="!row.task" class="tw-cell-missing">Task 描述符缺失</span>
            <span v-else-if="!presentTaskSource(row).raw" class="tw-cell-source">
              {{ presentTaskSource(row).label }}
            </span>
          </div>
        </div>
      </template>
    </el-table-column>

    <el-table-column label="状态" width="116">
      <template #default="{ row }">
        <TaskStatusBadge :row="row" :mode="mode" />
      </template>
    </el-table-column>

    <template v-if="mode === 'finite'">
      <el-table-column label="规模" width="84" align="right">
        <template #default="{ row }">
          <span class="tw-num">
            {{ row.stats ? row.stats.total.toLocaleString() : "—" }}
          </span>
        </template>
      </el-table-column>

      <el-table-column label="执行结果" min-width="156">
        <template #default="{ row }">
          <div v-if="formatOutcomeItems(row.stats).length" class="tw-outcome-inline">
            <template
              v-for="(item, index) in formatOutcomeItems(row.stats)"
              :key="item.text"
            >
              <span v-if="index > 0" class="tw-outcome-sep">·</span>
              <span :class="'tw-outcome--' + item.tone">{{ item.text }}</span>
            </template>
          </div>
          <span v-else class="raw-muted">—</span>
        </template>
      </el-table-column>

      <el-table-column label="Group" min-width="165" show-overflow-tooltip>
        <template #default="{ row }">
          <code class="tw-cell-plain">{{ row.task?.workerGroupId ?? "—" }}</code>
        </template>
      </el-table-column>

      <el-table-column label="创建时间" width="146">
        <template #default="{ row }">
          <span class="tw-num" :title="formatDateTime(row.createdAtMillis)">
            {{ formatCompactTime(row.createdAtMillis) }}
          </span>
        </template>
      </el-table-column>

      <el-table-column label="结束时间" width="146">
        <template #default="{ row }">
          <span
            class="tw-num"
            :class="{ 'raw-muted': row.retention?.terminatedAtMillis == null }"
            :title="formatDateTime(row.retention?.terminatedAtMillis)"
          >
            {{ formatCompactTime(row.retention?.terminatedAtMillis) }}
          </span>
        </template>
      </el-table-column>

      <el-table-column label="耗时" width="82" align="right">
        <template #default="{ row }">
          <span
            class="tw-num"
            :class="{ 'raw-muted': !endedDuration(row) }"
            :title="endedDuration(row) ? '创建至结束总跨度' : undefined"
          >
            {{ endedDuration(row) ?? "—" }}
          </span>
        </template>
      </el-table-column>
    </template>

    <template v-else>
      <el-table-column label="Group" min-width="175" show-overflow-tooltip>
        <template #default="{ row }">
          <code class="tw-cell-plain">{{ row.task?.workerGroupId ?? "—" }}</code>
        </template>
      </el-table-column>

      <el-table-column label="Pool 供给" min-width="135" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="tw-cell-plain">
            {{ row.task ? formatRefillList(row.task.refill).join("、") || "无" : "—" }}
          </span>
        </template>
      </el-table-column>

      <el-table-column label="创建者" width="96" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ presentTaskSource(row).creator ?? "—" }}</span>
        </template>
      </el-table-column>

      <el-table-column label="驻留项" width="84" align="right">
        <template #default="{ row }">
          <span class="tw-num" title="Redis 中当前尚未回收的 Item 数">
            {{ row.stats ? row.stats.total.toLocaleString() : "—" }}
          </span>
        </template>
      </el-table-column>

      <el-table-column label="创建时间" width="146">
        <template #default="{ row }">
          <span class="tw-num" :title="formatDateTime(row.createdAtMillis)">
            {{ formatCompactTime(row.createdAtMillis) }}
          </span>
        </template>
      </el-table-column>
    </template>

    <el-table-column label="操作" fixed="right" :width="mode === 'rpc' ? 136 : 84">
      <template #default="{ row }">
        <slot name="actions" :row="row">
          <el-button link :disabled="!row.task" @click="emit('open', row)">
            查看
          </el-button>
        </slot>
      </template>
    </el-table-column>

    <template #empty>
      {{ error ? "读取失败，请刷新重试" : "当前载入集合没有匹配任务" }}
    </template>
  </el-table>
</template>
