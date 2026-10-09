<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { ElDrawer, ElTable, ElTableColumn } from "element-plus";
import MessageTaskActions from "./MessageTaskActions.vue";
import MessageTemplateContent from "./MessageTemplateContent.vue";
import {
  taskStateLabel,
  senderRange,
  type MessageTask,
  type MessageTaskDetail,
  type MessageTaskSource,
  type MessageDemoAction
} from "./task-source";
import {
  applicationLabel,
  quantity,
  timestamp,
  describe,
  taskStage,
  resultKind,
  resultLabels,
  type ResultFilter,
  type MessageAction,
  type ImportSummary
} from "./workbench";
const props = defineProps<{
  taskId?: string;
  source: MessageTaskSource;
  blocked: boolean;
  actionError: string;
  imported?: ImportSummary;
}>();
const emit = defineEmits<{
  close: [];
  closed: [];
  action: [task: MessageTask, action: MessageAction, trigger?: HTMLElement];
  loaded: [task: MessageTask | undefined, stale: boolean];
  demo: [action: MessageDemoAction];
}>();
const detail = shallowRef<MessageTaskDetail>(),
  busy = ref(false),
  error = ref(""),
  readAt = ref(0);
const query = ref(""),
  filter = ref<ResultFilter>("all"),
  heading = ref<HTMLElement>();
const technical = ref(false);
let generation = 0,
  disposed = false;
const preview = computed(() => detail.value?.results.slice(0, 100) ?? []);
const rows = computed(() =>
  preview.value.filter(
    (row) =>
      (!query.value.trim() || row.recipientId?.includes(query.value.trim())) &&
      (filter.value === "all" || resultKind(row) === filter.value)
  )
);
const stale = computed(() => busy.value || !!error.value || !detail.value);
const counts = computed(() =>
  detail.value
    ? ([
        ["收件人", detail.value.task.sendTotal],
        ["已发送", detail.value.task.sentCount],
        ["失败", detail.value.task.failedCount],
        ["已送达", detail.value.task.deliveredCount],
        ["已读", detail.value.task.readCount],
        ["已回复", detail.value.task.repliedCount]
      ] as const)
    : []
);
function accept(value: MessageTaskDetail) {
  if (value.task.taskId !== props.taskId) return;
  detail.value = value;
  error.value = "";
  readAt.value = Date.now();
  emit("loaded", value.task, false);
}
async function refresh() {
  const id = props.taskId;
  if (!id) return;
  const token = ++generation;
  busy.value = true;
  emit("loaded", detail.value?.task, true);
  try {
    const value = await props.source.loadTask(id);
    if (!disposed && token === generation && id === props.taskId) accept(value);
  } catch (failure) {
    if (!disposed && token === generation) {
      error.value = describe(failure);
      emit("loaded", detail.value?.task, true);
    }
  } finally {
    if (token === generation) busy.value = false;
  }
}
watch(
  () => props.taskId,
  async () => {
    generation++;
    detail.value = undefined;
    error.value = "";
    readAt.value = 0;
    query.value = "";
    filter.value = "all";
    technical.value = false;
    emit("loaded", undefined, true);
    await refresh();
    await nextTick();
  },
  { immediate: true }
);
onBeforeUnmount(() => {
  disposed = true;
  generation++;
});
function invalidate(message: string) {
  generation++;
  busy.value = false;
  error.value = message;
  emit("loaded", detail.value?.task, true);
}
defineExpose({
  refresh,
  invalidate,
  accept: (value: MessageTaskDetail) => {
    if (value.task.taskId !== props.taskId) return;
    generation++;
    busy.value = false;
    accept(value);
  }
});
</script>
<template>
  <ElDrawer
    :model-value="!!taskId"
    size="760px"
    class="message-preview"
    title="任务预览"
    @update:model-value="(open: boolean) => !open && emit('close')"
    @opened="heading?.focus({ preventScroll: true })"
    @closed="emit('closed')"
  >
    <template #header
      ><div class="message-preview-header">
        <h2 ref="heading" tabindex="-1">{{ detail?.task.name || taskId }}</h2>
        <template v-if="detail"
          ><p>
            {{ applicationLabel(detail.task, source.applications) }} ·
            {{ senderRange(detail.task.senderCountry) }} →
            {{ detail.task.recipientCountry ?? "不可用" }} ·
            <span class="message-state" :data-state="taskStage(detail.task)">{{
              taskStateLabel(detail.task.state)
            }}</span>
          </p></template
        ><small class="message-hint"
          >{{ error && readAt ? "上次成功读取" : "最近读取" }} {{ timestamp(readAt)
          }}{{ error && readAt ? " · 快照已过期" : "" }}</small
        >
      </div></template
    >
    <div class="message-controls message-preview-controls">
      <el-button :loading="busy" @click="refresh">刷新预览</el-button
      ><MessageTaskActions
        v-if="detail"
        :task="detail.task"
        :blocked="blocked || stale"
        hide-preview
        @action="(action, trigger) => emit('action', detail!.task, action, trigger)"
      />
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <el-alert
      v-if="actionError"
      :title="actionError"
      type="warning"
      :closable="false"
      role="alert"
    />
    <p v-if="!detail">{{ busy ? "正在读取任务…" : "没有可用快照，请重试读取。" }}</p>
    <template v-else>
      <p v-if="detail.task.state === 'terminal'" class="message-hint">
        调度已结束，后续回执仍可更新。已发送、已送达、已读与已回复是累计阶段，不能相加。
      </p>
      <dl v-if="detail.task.state !== 'pre_review'" class="message-counts">
        <div v-for="[label, count] in counts" :key="label">
          <dt>{{ label }}</dt>
          <dd>{{ quantity(count) }}</dd>
        </div>
      </dl>
      <template v-if="detail.task.state === 'pre_review'">
        <h3>核对信息</h3>
        <p>实际收件人数：{{ quantity(detail.task.sendTotal) }}。导入后不会自动启动。</p>
        <MessageTemplateContent :content="detail.task.body" />
        <p v-if="detail.task.senderPhone" class="message-warning">
          旧任务指定发送号码：{{ detail.task.senderPhone }}，启动后仍按这个限制发送。
        </p>
        <p v-if="detail.task.inputVersion !== '2'" class="message-warning">
          旧输入版本仅支持读取和取消，不支持继续导入或启动。
        </p>
      </template>
      <template v-else>
        <h3>结果预览</h3>
        <p class="message-range">
          当前预览 {{ preview.length }} 条／匹配 {{ rows.length }} 条{{
            detail.resultsTruncated ? " · 返回结果已截断，未完整展示" : ""
          }}
        </p>
        <div class="message-result-filters">
          <el-input
            v-model="query"
            aria-label="搜索收件号码"
            placeholder="搜索当前预览内的号码"
            clearable
          /><el-select v-model="filter" aria-label="筛选结果"
            ><el-option
              v-for="(label, key) in resultLabels"
              :key="key"
              :label="label"
              :value="key"
          /></el-select>
        </div>
        <ElTable :data="rows" row-key="messageId" max-height="410"
          ><ElTableColumn label="收件号码" min-width="155"
            ><template #default="{ row }">{{
              row.recipientId ?? "不可用"
            }}</template></ElTableColumn
          ><ElTableColumn label="发送／回执" width="135"
            ><template #default="{ row }"
              ><span>{{
                resultKind(row) === "invalid"
                  ? "内容异常"
                  : resultKind(row) === "EXECUTION_FAILED"
                    ? "发送失败"
                    : resultKind(row) === "unknown"
                      ? "尚无业务结果"
                      : "已发送"
              }}</span
              ><small
                v-if="
                  ['SENT', 'DELIVERED', 'READ', 'REPLIED'].includes(resultKind(row))
                "
                class="message-id"
                >{{ resultLabels[resultKind(row)] }}</small
              ></template
            ></ElTableColumn
          ><ElTableColumn label="最新回复" min-width="175"
            ><template #default="{ row }"
              ><details v-if="row.reply" class="message-reply">
                <summary>{{ row.reply }}</summary>
                <p>{{ row.reply }}</p>
              </details>
              <span v-else>{{
                row.contentError ? "业务内容不可用" : "—"
              }}</span></template
            ></ElTableColumn
          ><ElTableColumn label="观察时间" width="147"
            ><template #default="{ row }">{{
              row.observedAtMillis ? timestamp(row.observedAtMillis) : "—"
            }}</template></ElTableColumn
          ><template #empty>{{
            preview.length ? "当前预览中没有匹配结果" : "尚未观察到结果"
          }}</template></ElTable
        >
      </template>
      <details class="message-fold" :open="detail.task.state === 'pre_review'">
        <summary>本会话导入摘要</summary>
        <p v-if="imported">
          最近一次来源：{{ imported.fileName }} · 读取
          {{ quantity(imported.receipt.inputCount) }} 行 · 文件内重复
          {{ quantity(imported.receipt.duplicateCount) }} · 去重后
          {{ quantity(imported.receipt.uniqueCount) }} · 确认新增
          {{ quantity(imported.receipt.confirmedAddedCount) }} · 已存在
          {{ quantity(imported.receipt.existingCount) }}
        </p>
        <p v-else class="message-hint">
          本会话未保留导入回执；服务端尚未提供导入历史。
        </p>
      </details>
      <details
        class="message-fold"
        :open="technical"
        @toggle="technical = ($event.target as HTMLDetailsElement).open"
      >
        <summary>技术信息{{ source.mode === "mock" ? "与演示控制" : "" }}</summary>
        <template v-if="technical"
          ><p>
            Task {{ detail.task.taskId }} · Group
            {{ detail.task.workerGroupId ?? "不可用" }}
          </p>
          <p v-if="detail.task.senderPhone">
            历史指定发送号码：{{ detail.task.senderPhone }}
          </p>
          <pre v-if="detail.task.state !== 'pre_review'">{{
            detail.task.body ?? "配置不可用"
          }}</pre>
          <details>
            <summary>预览内执行关联</summary>
            <ul class="message-technical-rows">
              <li v-for="row in preview" :key="row.messageId">
                {{ row.messageId }} · {{ row.phone ?? "号码不可用" }} ·
                {{ row.workerId ?? "Worker 不可用"
                }}<span v-if="row.contentError"> · {{ row.contentError }}</span>
              </li>
            </ul>
          </details>
          <div v-if="source.demonstrate" class="message-demo">
            <p class="message-hint">Mock 演示，仅改变本地数据。</p>
            <div class="message-controls">
              <el-button
                :disabled="blocked || stale || taskStage(detail.task) !== 'running'"
                @click="emit('demo', 'advance')"
                >推进 50 条发送</el-button
              ><el-button
                :disabled="blocked || stale || taskStage(detail.task) !== 'running'"
                @click="emit('demo', 'complete')"
                >完成发送</el-button
              ><el-button
                :disabled="blocked || stale || !detail.task.sentCount"
                @click="emit('demo', 'delivered')"
                >演示送达</el-button
              ><el-button
                :disabled="blocked || stale || !detail.task.sentCount"
                @click="emit('demo', 'read')"
                >演示已读</el-button
              ><el-button
                :disabled="blocked || stale || !detail.task.sentCount"
                @click="emit('demo', 'reply')"
                >新增回复</el-button
              ><el-button
                :disabled="blocked || stale"
                @click="emit('demo', 'fail-read')"
                >演示读取失败</el-button
              >
            </div>
          </div></template
        >
      </details>
    </template>
  </ElDrawer>
</template>
