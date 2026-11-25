<script setup lang="ts">
import { computed } from "vue";
import type { ProjectTask } from "@/task-management/projects";
import TaskRetentionNotice from "@/components/TaskRetentionNotice.vue";
import { copyText } from "../helpers";
import {
  formatDateTime,
  formatDuration,
  presentTaskSource,
  splitTaskMetadata
} from "../presentation";

const props = defineProps<{
  task: ProjectTask;
  mode: "finite" | "rpc";
}>();

const source = computed(() => presentTaskSource(props.task));
const metadata = computed(() => splitTaskMetadata(props.task.task?.metadata));
const terminatedAt = computed(() => props.task.retention?.terminatedAtMillis ?? null);
const duration = computed(() =>
  formatDuration(props.task.createdAtMillis, terminatedAt.value)
);
const configEntries = computed(() =>
  Object.entries(props.task.task?.config ?? {}).sort(([a], [b]) => a.localeCompare(b))
);

function copyId(value: string) {
  return copyText(value, "已复制 Task ID");
}
</script>
<template>
  <div class="tw-descriptor">
    <section class="tw-card">
      <header class="tw-card__header">
        <h3>基础与调度配置</h3>
        <el-button size="small" @click="copyId(task.taskId)">复制 Task ID</el-button>
      </header>
      <dl class="tw-kv-grid">
        <div>
          <dt>Task ID</dt>
          <dd>
            <code class="tw-code-inline">{{ task.taskId }}</code>
          </dd>
        </div>
        <div>
          <dt>名称</dt>
          <dd>{{ task.task?.name || "未命名" }}</dd>
        </div>
        <div>
          <dt>Project</dt>
          <dd>
            <code>{{ task.task?.projectId ?? "未提供" }}</code>
          </dd>
        </div>
        <div>
          <dt>WorkerGroup</dt>
          <dd>
            <code>{{ task.task?.workerGroupId ?? "未提供" }}</code>
          </dd>
        </div>
        <div>
          <dt>输入归属</dt>
          <dd>
            <el-tag :type="source.raw ? 'primary' : 'info'" effect="light" size="small">
              {{ source.label }}
            </el-tag>
          </dd>
        </div>
        <div>
          <dt>创建者</dt>
          <dd>{{ source.creator ?? "未提供" }}</dd>
        </div>
        <div>
          <dt>空闲策略</dt>
          <dd>
            <code>{{ task.task?.idleDisposition ?? "未提供" }}</code>
          </dd>
        </div>
        <div>
          <dt>底层 Score Band</dt>
          <dd>
            <code>{{ task.scoreBand ?? "不可用" }}</code>
          </dd>
        </div>
        <div v-for="[key, value] in configEntries" :key="key">
          <dt>{{ key }}</dt>
          <dd>
            <code>{{ value }}</code>
          </dd>
        </div>
      </dl>
    </section>

    <section class="tw-card">
      <header class="tw-card__header">
        <h3>时间线{{ mode === "finite" ? "与数据保留" : "" }}</h3>
      </header>
      <dl class="tw-kv-grid">
        <div>
          <dt>创建时间</dt>
          <dd>{{ formatDateTime(task.createdAtMillis, "未提供") }}</dd>
        </div>
        <template v-if="mode === 'finite'">
          <div>
            <dt>结束时间</dt>
            <dd>
              {{
                terminatedAt != null
                  ? formatDateTime(terminatedAt)
                  : task.scoreBand === "terminal"
                    ? "未记录"
                    : "尚未结束"
              }}
            </dd>
          </div>
          <div>
            <dt>总跨度（创建→结束）</dt>
            <dd :title="duration ? '包含待审核等待与实际调度时长' : undefined">
              {{ duration ?? "—" }}
            </dd>
          </div>
          <div>
            <dt>保留阶段</dt>
            <dd>
              <code>{{ task.retention?.phase ?? "未提供" }}</code>
            </dd>
          </div>
          <div v-if="task.retention?.frozenAtMillis">
            <dt>冻结归档时间</dt>
            <dd>{{ formatDateTime(task.retention.frozenAtMillis) }}</dd>
          </div>
          <div v-if="task.retention?.expiresAtMillis">
            <dt>Runtime 保留至</dt>
            <dd>{{ formatDateTime(task.retention.expiresAtMillis) }}</dd>
          </div>
          <div v-if="task.retention?.historyExpiresAtMillis">
            <dt>历史快照保留至</dt>
            <dd>{{ formatDateTime(task.retention.historyExpiresAtMillis) }}</dd>
          </div>
        </template>
      </dl>
      <TaskRetentionNotice
        v-if="mode === 'finite'"
        :retention="task.retention"
        :project-id="task.task?.projectId"
      />
    </section>

    <section class="tw-card">
      <header class="tw-card__header">
        <h3>Pool 供给声明</h3>
        <span class="raw-muted">{{
          task.task?.refill.length
            ? `${task.task.refill.length} 条声明`
            : "空供给（不主动补给 Pool）"
        }}</span>
      </header>
      <table v-if="task.task?.refill.length" class="tw-mini-table">
        <thead>
          <tr>
            <th>Pool</th>
            <th>Target 条件</th>
            <th>目标水位</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(item, index) in task.task.refill" :key="index">
            <td>
              <code>{{ item.poolName }}</code>
            </td>
            <td>
              <code class="tw-code-inline">{{ JSON.stringify(item.target) }}</code>
            </td>
            <td>
              <strong>{{ item.count }}</strong>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="raw-muted tw-card__empty">
        未声明 Pool 补给；Item 仍可通过直查函数（如 workerId）或复用同 Group 既有 Pool
        库存执行。
      </p>
    </section>

    <section v-if="metadata.custom.length || metadata.system.length" class="tw-card">
      <header class="tw-card__header">
        <h3>Metadata</h3>
        <span class="raw-muted">
          自定义 {{ metadata.custom.length }} · 系统 {{ metadata.system.length }}
        </span>
      </header>
      <dl v-if="metadata.custom.length" class="tw-kv-grid">
        <div v-for="[key, value] in metadata.custom" :key="key">
          <dt>{{ key }}</dt>
          <dd>
            <code class="tw-code-inline">{{ value }}</code>
          </dd>
        </div>
      </dl>
      <p v-else class="raw-muted tw-card__empty">无自定义 Metadata。</p>
      <details v-if="metadata.system.length" class="tw-fold">
        <summary>系统 Metadata（{{ metadata.system.length }}）</summary>
        <dl class="tw-kv-grid tw-kv-grid--compact">
          <div v-for="[key, value] in metadata.system" :key="key">
            <dt>{{ key }}</dt>
            <dd>
              <code class="tw-code-inline">{{ value }}</code>
            </dd>
          </div>
        </dl>
      </details>
    </section>
  </div>
</template>
