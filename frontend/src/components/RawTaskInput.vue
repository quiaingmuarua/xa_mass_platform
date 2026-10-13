<script setup lang="ts">
import {
  ElForm,
  ElFormItem,
  ElInputNumber,
  ElRadioButton,
  ElRadioGroup
} from "element-plus";
import { computed, onBeforeUnmount, ref, toRefs, watch } from "vue";
import "element-plus/es/components/form/style/css";
import "element-plus/es/components/form-item/style/css";
import "element-plus/es/components/input-number/style/css";
import "element-plus/es/components/radio-group/style/css";
import "element-plus/es/components/radio-button/style/css";
import { useTaskManagementStore } from "@/runtime-context";
import { refillSchema } from "@/runtime-viewer/schemas";
import { parseWorkerSelector } from "@/task-call-debug/model";
import {
  buildSeedItems,
  materializeTaskItems,
  parseSeedLines,
  parseJsonlItems,
  requireItemCapability,
  defaultSupply,
  MAX_TASK_SEED_BYTES
} from "@/task-management/model";
import type { ProjectGroup, ProjectTask } from "@/task-management/projects";
import type { TaskItemApiRequest } from "@/task-management/types";
const props = defineProps<{
  projectId: string;
  groups: ProjectGroup[];
  task?: ProjectTask;
  disabled: boolean;
}>();
const emit = defineEmits<{ submitted: [taskId: string]; inspect: [taskId: string] }>();
const operations = useTaskManagementStore();
const projectId = props.projectId;
const state = operations.project(projectId);
const draftKey = props.task?.taskId ?? "create";
const {
  groupId,
  format,
  eventCode,
  payloadKey,
  selectorText,
  refillText,
  priority,
  retries,
  file,
  content,
  inputError
} = toRefs(operations.draft(projectId, draftKey));
const ownReceipt = computed(() =>
  state.submissionKey === draftKey ? state.submission : undefined
);
const parsing = ref(false);
const inputKey = ref(0);
let parseGeneration = 0;
let disposed = false;
const group = computed(() =>
  props.groups.find((g) => g.workerGroupId === groupId.value)
);
const frozen = computed(() => !!state.submission || operations.busy);
const restoredDraft = !!groupId.value;
watch(
  () => [props.task?.taskId, props.groups] as const,
  () => {
    if (frozen.value || groupId.value) return;
    groupId.value =
      props.task?.task?.workerGroupId ??
      props.groups.find((g) => g.workerGroup)?.workerGroupId ??
      "";
  },
  { immediate: true }
);
watch(
  groupId,
  () => {
    if (!group.value || frozen.value) return;
    const template = defaultSupply(group.value);
    refillText.value = JSON.stringify(template.refill, null, 2);
    selectorText.value = template.selector
      ? JSON.stringify(template.selector, null, 2)
      : "";
    eventCode.value = group.value.workerGroup?.eventCodes.includes(
      "extension.worker.string.md5"
    )
      ? "extension.worker.string.md5"
      : "";
    payloadKey.value = eventCode.value ? "value" : "";
  },
  { immediate: !restoredDraft }
);
async function chooseFile(event: Event) {
  const generation = ++parseGeneration;
  file.value = (event.target as HTMLInputElement).files?.[0];
  content.value = undefined;
  inputError.value = "";
  parsing.value = false;
  const selected = file.value;
  if (!selected) return;
  if (selected.size > MAX_TASK_SEED_BYTES) {
    inputError.value = "单次文件最多 1 MiB";
    return;
  }
  parsing.value = true;
  try {
    const buffer = await selected.arrayBuffer();
    if (generation !== parseGeneration) return;
    if (!parseSeedLines(buffer).length) throw new Error("文件至少包含一行");
    content.value = buffer;
  } catch (failure) {
    if (generation === parseGeneration)
      inputError.value = failure instanceof Error ? failure.message : "文件读取失败";
  } finally {
    if (generation === parseGeneration) parsing.value = false;
  }
}
onBeforeUnmount(() => {
  disposed = true;
  parseGeneration++;
});
async function submit() {
  inputError.value = "";
  if (props.disabled || frozen.value || !content.value || !file.value || !group.value)
    return;
  try {
    const currentGroup = group.value;
    let raw: TaskItemApiRequest[] | undefined;
    let items: (id: string) => TaskItemApiRequest[];
    let total: number;
    if (format.value === "jsonl") {
      raw = parseJsonlItems(content.value, currentGroup);
      total = raw.length;
      items = () => raw!;
    } else {
      if (!payloadKey.value.trim()) throw new Error("请填写 payload 字段");
      const selector = parseWorkerSelector(selectorText.value);
      requireItemCapability(currentGroup, eventCode.value, selector.executorName);
      const seeds = buildSeedItems(
        parseSeedLines(content.value),
        payloadKey.value.trim()
      );
      const event = eventCode.value;
      total = seeds.length;
      // Extra TXT imports get their own fixed prefix; row numbers cannot overwrite earlier Items.
      const importId = props.task ? crypto.randomUUID() : "";
      items = (id) =>
        materializeTaskItems(
          importId ? id + "-" + importId : id,
          event,
          seeds,
          selector
        );
    }
    const refill = refillSchema.parse(JSON.parse(refillText.value));
    if (
      !props.task &&
      refill.some((r) => !currentGroup.matching.pools.includes(r.poolName))
    )
      throw new Error("供给 Pool 未启用");
    if (
      !Number.isInteger(priority.value) ||
      priority.value < 0 ||
      priority.value > 99 ||
      !Number.isInteger(retries.value) ||
      retries.value < 0 ||
      retries.value > 98
    )
      throw new Error("Task 调度参数超出范围");
    const taskId = await operations.submit({
      projectId,
      create: props.task
        ? undefined
        : {
            projectId,
            workerGroupId: groupId.value,
            priority: priority.value,
            maxRetryTimes: retries.value,
            refill
          },
      existing: props.task,
      fresh: !props.disabled,
      fileName: file.value.name,
      total,
      items
    });
    if (taskId && !disposed) emit("submitted", taskId);
  } catch (failure) {
    if (!disposed)
      inputError.value = failure instanceof Error ? failure.message : "输入无效";
  }
}
function reset() {
  if (operations.busy) return;
  operations.finishSubmission(projectId);
  file.value = undefined;
  content.value = undefined;
  inputError.value = "";
  inputKey.value++;
  parseGeneration++;
}
</script>
<template>
  <el-form label-position="top" class="raw-task-form" @submit.prevent="submit">
    <el-alert
      v-if="disabled"
      title="当前模式、读取状态或任务状态不允许写入。"
      type="warning"
      :closable="false"
    />
    <el-form-item label="WorkerGroup">
      <el-select
        v-model="groupId"
        :disabled="!!task || frozen || disabled"
        aria-label="WorkerGroup"
      >
        <el-option
          v-for="g in groups"
          :key="g.workerGroupId"
          :label="g.workerGroupId + (g.workerGroup ? '' : '（不可用）')"
          :value="g.workerGroupId"
          :disabled="!g.workerGroup"
        />
      </el-select>
    </el-form-item>
    <el-alert
      v-if="groups.some((g) => !g.workerGroup)"
      :title="
        '尚未注册的 Group：' +
        groups
          .filter((g) => !g.workerGroup)
          .map((g) => g.workerGroupId)
          .join('、') +
        '。请完成资源注册后刷新 Group 能力。'
      "
      type="warning"
      :closable="false"
    />
    <p v-if="group" class="raw-task-help">
      已启用 Pool：{{ group.matching.pools.join("、") || "无" }}；函数：{{
        group.matching.functions.join("、")
      }}。不代表当前有可执行 Worker。
    </p>
    <template v-if="!task">
      <div class="raw-task-pair">
        <el-form-item label="Task 优先级"
          ><el-input-number v-model="priority" :min="0" :max="99" :disabled="frozen"
        /></el-form-item>
        <el-form-item label="最大重试"
          ><el-input-number v-model="retries" :min="0" :max="98" :disabled="frozen"
        /></el-form-item>
      </div>
      <el-form-item label="Task 供给声明（refill）"
        ><el-input
          v-model="refillText"
          type="textarea"
          :rows="4"
          :disabled="frozen"
          aria-label="Task 供给声明"
      /></el-form-item>
    </template>
    <el-form-item label="输入格式">
      <el-radio-group v-model="format" :disabled="frozen"
        ><el-radio-button value="txt">TXT 单字段</el-radio-button
        ><el-radio-button value="jsonl"
          >原始 Item JSONL</el-radio-button
        ></el-radio-group
      >
    </el-form-item>
    <template v-if="format === 'txt'">
      <el-form-item label="事件">
        <el-select
          v-model="eventCode"
          :disabled="frozen"
          aria-label="事件"
          placeholder="选择 Group 声明的事件"
        >
          <el-option
            v-for="event in group?.workerGroup?.eventCodes ?? []"
            :key="event"
            :label="event"
            :value="event"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="payload 字段"
        ><el-input v-model="payloadKey" :disabled="frozen" aria-label="payload 字段"
      /></el-form-item>
      <el-form-item label="Item 查询（workerSelector）"
        ><el-input
          v-model="selectorText"
          type="textarea"
          :rows="3"
          :disabled="frozen"
          aria-label="Item 查询"
      /></el-form-item>
    </template>
    <el-alert
      v-else
      title="每行填写完整 Item：messageId、eventCode、对象 payload、workerSelector；可选 priority、ttlMillis。重复 messageId 整批拒绝。"
      type="info"
      :closable="false"
    />
    <el-form-item label="UTF-8 文件 · 最多 1 MiB / 10,000 行">
      <input
        :key="inputKey"
        type="file"
        :accept="format === 'txt' ? '.txt' : '.jsonl,.ndjson'"
        :disabled="frozen || disabled"
        aria-label="原始任务输入文件"
        @change="chooseFile"
      />
    </el-form-item>
    <p v-if="content">
      {{ file?.name }} ·
      {{ parseSeedLines(content).length }} 行已读取，提交前校验完整文件。
    </p>
    <el-alert
      v-if="inputError"
      :title="inputError"
      type="error"
      :closable="false"
      role="alert"
    />
    <section v-if="ownReceipt" class="raw-task-receipt" aria-label="本会话提交回执">
      <strong>本会话提交回执</strong>
      <p>
        已确认 {{ ownReceipt.applied }} · 拒绝 {{ ownReceipt.rejected }} · 未确认
        {{ ownReceipt.unconfirmed }} · 未提交
        {{ ownReceipt.notSubmitted }}
      </p>
      <p v-if="ownReceipt.error" role="alert">
        {{ ownReceipt.error }} 不会自动重建、重传或批准。
      </p>
      <el-button v-if="ownReceipt.taskId" @click="emit('inspect', ownReceipt.taskId)"
        >查看已知任务</el-button
      >
      <el-button :disabled="operations.busy" @click="reset"
        >结束本次提交并清空输入</el-button
      >
      <p class="raw-task-help">
        结束提交只清除本地输入，不关闭服务端任务。requestId
        只用于诊断，不是创建幂等身份。
      </p>
    </section>
    <el-button
      v-else-if="!state.submission"
      type="primary"
      native-type="submit"
      :loading="operations.busy || parsing"
      :disabled="disabled || !content || !group?.workerGroup"
      >{{ task ? "追加 Items" : "创建并追加" }}</el-button
    >
  </el-form>
</template>
<style scoped>
.raw-task-form {
  display: grid;
  gap: 12px;
}
.raw-task-pair {
  display: flex;
  gap: 24px;
}
.raw-task-help {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
  margin: 0;
}
.raw-task-receipt {
  padding: 16px;
  background: var(--el-fill-color-light);
}
.raw-task-form :deep(.el-select) {
  width: 100%;
}
</style>
