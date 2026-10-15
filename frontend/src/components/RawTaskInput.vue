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
import TaskSupplyEditor from "@/components/TaskSupplyEditor.vue";
import { readSupply } from "@/task-management/supply";
import { refillSchema } from "@/runtime-viewer/schemas";
import { parseWorkerSelector } from "@/task-call-debug/model";
import {
  buildSeedItems,
  materializeTaskItems,
  parseSeedLines,
  parseJsonlItems,
  parsePayloadLines,
  requireItemCapability,
  suggestedSupply,
  MAX_TASK_SEED_BYTES
} from "@/task-management/model";
import type { ProjectGroup, ProjectTask } from "@/task-management/projects";
import type { TaskItemApiRequest } from "@/task-management/types";

const props = defineProps<{
  projectId: string;
  groups: ProjectGroup[];
  task?: ProjectTask;
  disabled: boolean;
  disabledReason?: string;
}>();
const emit = defineEmits<{ submitted: [taskId: string]; inspect: [taskId: string] }>();
const operations = useTaskManagementStore();
const projectId = props.projectId;
const state = operations.project(projectId);
const draftKey = props.task?.taskId ?? "create";
const {
  caller,
  requestId,
  groupId,
  format,
  inputMode,
  pasteText,
  eventCode,
  payloadKey,
  executorName,
  queryInput,
  refillText,
  refillMode,
  supplyRows,
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
const selectedFunction = computed(() =>
  group.value?.matching.functions.find((fn) => fn.name === executorName.value)
);
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
watch(groupId, () => {
  if (frozen.value || !group.value) return;
  if (!group.value.workerGroup?.eventCodes.includes(eventCode.value))
    eventCode.value = "";
  if (!group.value.matching.functions.some((fn) => fn.name === executorName.value)) {
    executorName.value = "";
    queryInput.value = "{}";
  }
  supplyRows.value = supplyRows.value.filter((row) =>
    group.value!.matching.pools.includes(row.poolName)
  );
  try {
    refillText.value = JSON.stringify(
      refillSchema
        .parse(JSON.parse(refillText.value))
        .filter((row) => group.value!.matching.pools.includes(row.poolName)),
      null,
      2
    );
  } catch {
    /* Keep incomplete JSON for correction. */
  }
});
function supply() {
  return readSupply(
    {
      refillMode: refillMode.value,
      refillText: refillText.value,
      supplyRows: supplyRows.value
    },
    group.value?.matching.pools ?? []
  );
}
const supplySummary = computed(() => {
  try {
    const rows = props.task?.task?.refill ?? supply();
    return rows.length
      ? rows.map((row) => row.poolName + " · 目标 " + row.count).join("；")
      : "空供给";
  } catch {
    return "供给配置尚未有效";
  }
});
function applySuggestion() {
  if (!group.value || frozen.value) return;
  const rows = suggestedSupply(group.value, executorName.value);
  supplyRows.value = rows.map((row) => ({
    poolName: row.poolName,
    targetText: "{}",
    count: row.count
  }));
  refillText.value = JSON.stringify(rows, null, 2);
}
const bytes = computed(() =>
  inputMode.value === "paste"
    ? new TextEncoder().encode(pasteText.value).buffer
    : content.value
);
const validation = computed(() => {
  if (!bytes.value?.byteLength) return { count: 0, examples: [], error: "" };
  try {
    if (!group.value?.workerGroup) throw new Error("请选择已注册的 Group");
    if (format.value === "jsonl") {
      const raw = parseJsonlItems(bytes.value, group.value);
      if (!raw.length) throw new Error("输入至少需要一条 Item");
      return { count: raw.length, examples: raw.slice(0, 3), raw, error: "" };
    }
    const selector = parseWorkerSelector(
      JSON.stringify({
        executorName: executorName.value,
        input: JSON.parse(queryInput.value)
      })
    );
    requireItemCapability(group.value, eventCode.value, selector.executorName);
    if (format.value === "txt" && !payloadKey.value.trim())
      throw new Error("请填写 TXT 的 payload 字段");
    const seeds =
      format.value === "payload"
        ? parsePayloadLines(bytes.value)
        : buildSeedItems(parseSeedLines(bytes.value), payloadKey.value.trim());
    if (!seeds.length) throw new Error("输入至少需要一条 Item");
    return {
      count: seeds.length,
      examples: seeds.slice(0, 3).map((seed) => ({
        line: seed.lineNumber,
        eventCode: eventCode.value,
        workerSelector: selector,
        payload: seed.payload
      })),
      seeds,
      selector,
      error: ""
    };
  } catch (error) {
    return {
      count: 0,
      examples: [],
      error: error instanceof Error ? error.message : "输入无效"
    };
  }
});
async function chooseFile(event: Event) {
  const generation = ++parseGeneration;
  file.value = (event.target as HTMLInputElement).files?.[0];
  content.value = undefined;
  inputError.value = "";
  parsing.value = false;
  const selected = file.value;
  if (!selected) return;
  if (selected.size > MAX_TASK_SEED_BYTES) {
    inputError.value = "单次输入最多 1 MiB";
    return;
  }
  parsing.value = true;
  try {
    const buffer = await selected.arrayBuffer();
    if (generation !== parseGeneration) return;
    parseSeedLines(buffer);
    content.value = buffer;
  } catch (error) {
    if (generation === parseGeneration)
      inputError.value = error instanceof Error ? error.message : "文件读取失败";
  } finally {
    if (generation === parseGeneration) parsing.value = false;
  }
}
watch(inputMode, () => {
  ++parseGeneration;
  parsing.value = false;
  inputError.value = "";
});
onBeforeUnmount(() => {
  disposed = true;
  ++parseGeneration;
});
async function submit() {
  inputError.value = "";
  if (props.disabled || frozen.value || parsing.value || !group.value?.workerGroup)
    return;
  try {
    const checked = validation.value;
    if (checked.error || !checked.count)
      throw new Error(checked.error || "请导入或粘贴输入数据");
    const refill = props.task ? [] : supply();
    if (
      !Number.isInteger(priority.value) ||
      priority.value < 0 ||
      priority.value > 99 ||
      !Number.isInteger(retries.value) ||
      retries.value < 0 ||
      retries.value > 98
    )
      throw new Error("Task 调度参数超出范围");
    // Capture before creation: navigation cannot change the admitted input.
    const event = eventCode.value;
    const importId = props.task ? crypto.randomUUID() : "";
    const items = checked.raw
      ? () => checked.raw!
      : (id: string): TaskItemApiRequest[] =>
          materializeTaskItems(
            importId ? id + "-" + importId : id,
            event,
            checked.seeds!,
            checked.selector!
          );
    const taskId = await operations.submit({
      caller: caller.value,
      projectId,
      create: props.task
        ? undefined
        : {
            projectId,
            workerGroupId: groupId.value,
            requestId: requestId.value,
            priority: priority.value,
            maxRetryTimes: retries.value,
            refill
          },
      existing: props.task,
      fresh: !props.disabled,
      fileName: inputMode.value === "file" ? file.value!.name : "粘贴输入",
      total: checked.count,
      items
    });
    if (taskId && !disposed) emit("submitted", taskId);
  } catch (error) {
    if (!disposed)
      inputError.value = error instanceof Error ? error.message : "输入无效";
  }
}
function reset() {
  if (operations.busy) return;
  operations.finishSubmission(projectId);
  file.value = undefined;
  content.value = undefined;
  pasteText.value = "";
  inputError.value = "";
  inputKey.value++;
  parseGeneration++;
}
</script>
<template>
  <el-form label-position="top" class="raw-task-form" @submit.prevent="submit">
    <p class="raw-muted">Project · {{ projectId }} · 调用方 · {{ caller.userId }}</p>
    <el-alert
      v-if="disabled"
      :title="disabledReason || '当前读取或任务状态不允许提交，请刷新核对。'"
      type="warning"
      :closable="false"
    />
    <section class="raw-task-section">
      <h3>执行配置</h3>
      <div class="raw-task-grid">
        <el-form-item label="WorkerGroup">
          <el-select
            v-model="groupId"
            filterable
            :disabled="!!task || frozen"
            aria-label="WorkerGroup"
          >
            <el-option
              v-for="g in groups"
              :key="g.workerGroupId"
              :label="g.workerGroupId + (g.workerGroup ? '' : '（未注册）')"
              :value="g.workerGroupId"
              :disabled="!g.workerGroup"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="format !== 'jsonl'" label="执行事件">
          <el-select
            v-model="eventCode"
            filterable
            :disabled="frozen"
            placeholder="选择 Group 已声明的事件"
            aria-label="执行事件"
          >
            <el-option
              v-for="event in group?.workerGroup?.eventCodes ?? []"
              :key="event"
              :label="event"
              :value="event"
            />
          </el-select>
        </el-form-item>
      </div>
      <div v-if="format !== 'jsonl'" class="raw-task-grid">
        <el-form-item label="Worker 查询函数">
          <el-select
            v-model="executorName"
            filterable
            :disabled="frozen"
            placeholder="请选择查询函数"
            aria-label="Item 查询函数"
          >
            <el-option
              v-for="fn in group?.matching.functions ?? []"
              :key="fn.name"
              :label="fn.name"
              :value="fn.name"
            />
          </el-select>
          <p class="raw-task-help">
            {{
              selectedFunction
                ? selectedFunction.poolName
                  ? "消费 Pool · " + selectedFunction.poolName
                  : "独立身份查询，不消费 Pool"
                : "选择后可查看对应 Pool"
            }}
          </p>
        </el-form-item>
        <el-form-item label="查询 input · 非 null JSON">
          <el-input
            v-model="queryInput"
            type="textarea"
            :rows="2"
            :disabled="frozen"
            aria-label="Item 查询参数"
            class="raw-code"
            placeholder='{} / [] / "worker-123"'
          />
          <p v-if="executorName === 'workerId'" class="raw-task-help">
            workerId 使用 JSON 字符串，如 "worker-123"。
          </p>
        </el-form-item>
      </div>
      <p class="raw-task-help">
        能力来自 Group 声明和当前 Matching 配置，不代表 Worker 在线或已安装
        Handler。参数由 Matching 按实际规则校验。
      </p>
      <template v-if="!task">
        <div class="raw-supply-heading">
          <strong>本 Task 的 Pool 供给</strong>
          <el-button
            size="small"
            :disabled="!selectedFunction || frozen || format === 'jsonl'"
            @click="applySuggestion"
            >应用所选查询的供给建议</el-button
          >
        </div>
        <TaskSupplyEditor
          v-model:mode="refillMode"
          v-model:text="refillText"
          v-model:rows="supplyRows"
          :pools="group?.matching.pools ?? []"
          :disabled="frozen"
        />
      </template>
      <p v-else class="raw-task-help">
        追加使用任务保存的 Group 和供给，任务配置保持不变。
      </p>
    </section>
    <section class="raw-task-section">
      <h3>输入数据</h3>
      <el-radio-group v-model="format" :disabled="frozen" aria-label="输入格式">
        <el-radio-button value="payload">payload JSONL</el-radio-button
        ><el-radio-button value="txt">TXT 单字段</el-radio-button
        ><el-radio-button value="jsonl">完整 Item JSONL</el-radio-button>
      </el-radio-group>
      <p v-if="format === 'payload'" class="raw-task-help">
        每行一个 payload 对象，例如 {"value":"hello"}。统一使用上方事件与查询，messageId
        自动生成。
      </p>
      <el-form-item v-else-if="format === 'txt'" label="每行映射到 payload 字段"
        ><el-input
          v-model="payloadKey"
          :disabled="frozen"
          aria-label="TXT payload 字段"
          placeholder="例如 value"
        />
        <p class="raw-task-help">
          保留每行文本，包括空行；不进行业务去重。
        </p></el-form-item
      >
      <p v-else class="raw-task-help">
        每行填写完整 Item：messageId、eventCode、对象 payload、workerSelector，可选
        priority／ttlMillis。各行使用自己的事件和查询，上方不覆盖。
      </p>
      <details v-if="format === 'jsonl'">
        <summary>查看完整 Item 示例</summary>
        <pre class="raw-code">
{"messageId":"example-1","eventCode":"选择的事件名称","payload":{"value":"hello"},"workerSelector":{"executorName":"workerId","input":"worker-123"}}</pre
        >
      </details>
      <el-radio-group
        v-model="inputMode"
        :disabled="frozen"
        size="small"
        aria-label="输入来源"
        ><el-radio-button value="paste">粘贴</el-radio-button
        ><el-radio-button value="file">上传文件</el-radio-button></el-radio-group
      >
      <el-input
        v-if="inputMode === 'paste'"
        v-model="pasteText"
        class="raw-code"
        type="textarea"
        :rows="8"
        :disabled="frozen"
        aria-label="原始输入内容"
        placeholder="每行一条输入"
      />
      <input
        v-else
        :key="inputKey"
        type="file"
        :accept="format === 'txt' ? '.txt' : '.jsonl,.ndjson,.txt'"
        :disabled="frozen"
        aria-label="原始任务文件"
        @change="chooseFile"
      />
      <p class="raw-task-help">
        UTF-8 · 最多 1 MiB / 10,000 行。JSONL
        忽略空白行，整份校验后再创建；不执行业务格式转换。
      </p>
      <p v-if="file && inputMode === 'file'" class="raw-task-help">{{ file.name }}</p>
      <p v-if="parsing">正在读取文件…</p>
      <el-alert
        v-if="validation.error"
        :title="validation.error"
        type="error"
        :closable="false"
        role="alert"
      />
    </section>
    <details v-if="!task" class="raw-task-section">
      <summary>高级任务配置</summary>
      <div class="raw-task-grid">
        <el-form-item label="Task 优先级"
          ><el-input-number v-model="priority" :min="0" :max="99" :disabled="frozen"
        /></el-form-item>
        <el-form-item label="最大重试"
          ><el-input-number v-model="retries" :min="0" :max="98" :disabled="frozen"
        /></el-form-item>
      </div>
    </details>
    <section class="raw-task-section raw-submit-summary">
      <h3>提交摘要</h3>
      <p>Task 供给：{{ supplySummary }}</p>
      <p>
        {{ groupId || "未选择 Group" }} ·
        {{
          format === "jsonl"
            ? "事件与查询由每行指定"
            : (eventCode || "未选择事件") + " / " + (executorName || "未选择查询")
        }}
      </p>
      <p>
        有效输入 <strong>{{ validation.count }}</strong> 条 · 每批最多 100 Items ·
        导入后待审核
      </p>
      <details v-if="validation.examples.length">
        <summary>转换预览 · 前 {{ validation.examples.length }} 条</summary>
        <pre class="raw-code raw-input-examples">{{
          JSON.stringify(validation.examples, null, 2)
        }}</pre>
      </details>
      <el-alert
        v-if="inputError"
        :title="inputError"
        type="error"
        :closable="false"
        role="alert"
      />
      <section v-if="ownReceipt" aria-label="本会话提交回执">
        <p>
          已确认 {{ ownReceipt.applied }} · 拒绝 {{ ownReceipt.rejected }} · 未确认
          {{ ownReceipt.unconfirmed }} · 未提交 {{ ownReceipt.notSubmitted }}
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
          结束提交仅清理本地输入，不关闭任务。业务 requestId 固定本次创建身份；HTTP
          X-Request-Id 仅用于诊断。
        </p>
      </section>
      <el-button
        v-else-if="!state.submission"
        type="primary"
        native-type="submit"
        :loading="operations.busy || parsing"
        :disabled="disabled || !validation.count || frozen"
        >{{ task ? "追加 Items，待审核" : "创建并导入，待审核" }}</el-button
      >
      <p v-else class="raw-task-help">请先处理本 Project 的上一笔提交。</p>
    </section>
  </el-form>
</template>
<style scoped>
.raw-task-form {
  display: grid;
  gap: 20px;
  max-width: 1120px;
}
.raw-task-section {
  display: grid;
  gap: 12px;
  padding: 20px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
}
.raw-task-section h3 {
  margin: 0 0 4px;
  font-size: 16px;
}
.raw-task-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 20px;
}
.raw-task-form :deep(.el-form-item) {
  margin-bottom: 0;
}
.raw-task-form :deep(.el-select) {
  width: 100%;
}
.raw-task-help {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
  margin: 0;
}
.raw-code,
.raw-code :deep(textarea),
.raw-code :deep(input) {
  font-family: ui-monospace, Consolas, monospace;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.raw-input-examples {
  max-height: 260px;
  overflow: auto;
}
.raw-supply-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.raw-submit-summary {
  background: var(--el-fill-color-light);
}
.raw-submit-summary p {
  margin: 0;
}
.raw-submit-summary > .el-button {
  justify-self: start;
}
summary {
  cursor: pointer;
}
</style>
