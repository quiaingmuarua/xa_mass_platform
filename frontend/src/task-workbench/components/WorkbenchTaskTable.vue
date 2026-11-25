<script setup lang="ts">
import { nextTick, ref } from "vue";
import { ElLoading, type TableInstance } from "element-plus";
import type { TaskRow } from "@/task-management/projects";
import { retentionLabel } from "@/task-management/retention";
import TaskStatusBadge from "./TaskStatusBadge.vue";
import {
  formatCompactTime,
  formatDateTime,
  formatDuration,
  formatOutcomeItems,
  formatRefillList,
  formatRpcWindowOutcomes,
  presentTaskSource,
  summarizeTaskStats
} from "../presentation";
import "element-plus/es/components/loading/style/css";

defineProps<{
  rows: TaskRow[];
  mode: "finite" | "rpc" | "global";
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
    :height="mode === 'global' ? 560 : 520"
    class="raw-tasks__table tw-table"
    @scroll="emit('scroll', $event)"
  >
    <el-table-column
      :label="mode === 'global' ? '任务 / 编号' : '任务'"
      min-width="220"
    >
      <template #default="{ row }">
        <div class="tw-cell-identity">
          <div class="tw-cell-identity__title">
            <el-button v-if="row.task" link type="primary" @click="emit('open', row)">
              {{ row.task.name || row.taskId }}
            </el-button>
            <code v-else>{{ row.taskId }}</code>
          </div>
          <div
            v-if="
              row.task?.name ||
              !row.task ||
              (mode !== 'global' && !presentTaskSource(row).raw)
            "
            class="tw-cell-identity__sub"
          >
            <code v-if="row.task?.name" class="tw-cell-id">{{ row.taskId }}</code>
            <span v-if="!row.task" class="tw-cell-missing">Task 描述符缺失</span>
            <span
              v-else-if="mode !== 'global' && !presentTaskSource(row).raw"
              class="tw-cell-source"
            >
              {{ presentTaskSource(row).label }}
            </span>
          </div>
        </div>
      </template>
    </el-table-column>

    <template v-if="mode === 'global'">
      <el-table-column label="Project / Group" min-width="155">
        <template #default="{ row }">
          {{ row.task?.projectId ?? "未提供" }}
          <small>{{ row.task?.workerGroupId ?? "未提供" }}</small>
        </template>
      </el-table-column>
      <el-table-column label="生命周期" min-width="165">
        <template #default="{ row }">
          <code class="tw-cell-plain">{{ row.task?.idleDisposition ?? "未提供" }}</code>
        </template>
      </el-table-column>
      <el-table-column label="Score band" min-width="140">
        <template #default="{ row }">
          <el-tag effect="plain" size="small">{{
            row.scoreBand ?? "状态不可用"
          }}</el-tag>
          <small>{{ retentionLabel(row.retention) }}</small>
        </template>
      </el-table-column>
      <el-table-column label="Pool 供给" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="tw-cell-plain">
            {{
              row.task
                ? formatRefillList(row.task.refill).join("、") || "无供给"
                : "未提供"
            }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="150">
        <template #default="{ row }">
          <span class="tw-num" :title="formatDateTime(row.createdAtMillis, '未提供')">
            {{ formatCompactTime(row.createdAtMillis, "未提供") }}
          </span>
        </template>
      </el-table-column>
    </template>

    <template v-else>
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
              {{
                row.task ? formatRefillList(row.task.refill).join("、") || "无" : "—"
              }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="创建者" width="96" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ presentTaskSource(row).creator ?? "—" }}</span>
          </template>
        </el-table-column>

        <el-table-column label="在途" width="74" align="right">
          <template #default="{ row }">
            <span
              class="tw-num"
              :class="{
                'tw-outcome--active': (summarizeTaskStats(row.stats)?.active ?? 0) > 0,
                'raw-muted': !row.stats
              }"
              title="Redis 中当前在途 / 待调度的 Item 数"
            >
              {{
                row.stats
                  ? (summarizeTaskStats(row.stats)?.active ?? 0).toLocaleString()
                  : "—"
              }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="窗口结果" min-width="145">
          <template #default="{ row }">
            <div
              v-if="formatRpcWindowOutcomes(row.stats).length"
              class="tw-outcome-inline"
              title="当前保留窗口内尚未回收的终态结果"
            >
              <template
                v-for="(item, index) in formatRpcWindowOutcomes(row.stats)"
                :key="item.text"
              >
                <span v-if="index > 0" class="tw-outcome-sep">·</span>
                <span :class="'tw-outcome--' + item.tone">{{ item.text }}</span>
              </template>
            </div>
            <span v-else class="raw-muted">—</span>
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
    </template>

    <el-table-column label="操作" fixed="right" :width="mode === 'rpc' ? 136 : 90">
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
