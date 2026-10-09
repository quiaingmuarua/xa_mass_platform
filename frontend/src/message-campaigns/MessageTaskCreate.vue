<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { ElForm, ElFormItem } from "element-plus";
import "element-plus/theme-chalk/el-form.css";
import "element-plus/theme-chalk/el-form-item.css";
import MessageRecipients from "./MessageRecipients.vue";
import { MessageApiError } from "./api";
import type { RecipientInput, RecipientLimits } from "@/files/phone-numbers";
import {
  MessageTaskCreationUnconfirmed,
  type CreateMessageTask,
  type MessageCountry,
  type MessageTaskSource
} from "./task-source";
const open = defineModel<boolean>({ required: true });
const props = defineProps<{ source: MessageTaskSource; limits: RecipientLimits }>();
const emit = defineEmits<{ created: [taskId: string] }>();
const initialDraft = () => ({
  recipientCountry: "CN" as MessageCountry,
  senderCountry: "ANY" as MessageCountry | "ANY",
  senderPhone: "",
  body: '{\n  "receipts_status": ["read", "replied"],\n  "delayMs": [1000, 4000],\n  "probability": 0.5,\n  "text": "收到了"\n}'
});
const draft = reactive(initialDraft());
const recipients = ref<RecipientInput>();
const reading = ref(false),
  busy = ref(false),
  uncertain = ref(false),
  confirmed = ref(false);
const knownTaskId = ref<string>(),
  frozen = ref<CreateMessageTask>();
const error = ref(""),
  note = ref(""),
  inputKey = ref(0);
const nameInput = ref<{ focus(): void }>();
const nameTime = ref(new Date());
let requestId = crypto.randomUUID(),
  originalText = "",
  disposed = false;
const taskName = computed(() => {
  if (frozen.value) return frozen.value.name;
  const at = nameTime.value,
    pad = (n: number) => String(n).padStart(2, "0");
  return `msg-${draft.senderCountry}-${draft.recipientCountry}-${at.getFullYear()}${pad(at.getMonth() + 1)}${pad(at.getDate())}-${pad(at.getHours())}${pad(at.getMinutes())}${pad(at.getSeconds())}`;
});
watch(open, (visible) => {
  if (visible && !frozen.value) nameTime.value = new Date();
});
const bodyError = computed(() => {
  try {
    const value = JSON.parse(draft.body);
    return value && typeof value === "object" && !Array.isArray(value)
      ? ""
      : "正文必须是 JSON 对象";
  } catch {
    return "正文不是合法 JSON";
  }
});
const valid = computed(
  () =>
    !bodyError.value &&
    draft.body.length <= 4096 &&
    recipients.value &&
    !recipients.value.issues.length &&
    !reading.value
);
function reset() {
  Object.assign(draft, initialDraft());
  recipients.value = undefined;
  inputKey.value++;
  requestId = crypto.randomUUID();
  originalText = "";
  frozen.value = undefined;
  knownTaskId.value = undefined;
  uncertain.value = false;
  confirmed.value = false;
  error.value = "";
  note.value = "";
  nameTime.value = new Date();
}
function done() {
  const id = knownTaskId.value!;
  reset();
  open.value = false;
  emit("created", id);
}
function describe(failure: unknown) {
  let message = failure instanceof Error ? failure.message : "操作失败，请核对任务。";
  if (failure instanceof MessageApiError && failure.confirmedAddedCount !== undefined)
    message += ` 已确认写入 ${failure.confirmedAddedCount}，已存在 ${failure.existingCount ?? "未知"}；未确认部分请刷新任务核对。`;
  return message;
}
async function importOriginal() {
  if (!knownTaskId.value || !confirmed.value || busy.value || !originalText.trim())
    return;
  busy.value = true;
  error.value = "";
  try {
    await props.source.importRecipients(knownTaskId.value, originalText);
    if (!disposed) done();
  } catch (failure) {
    if (!disposed) error.value = `任务已创建，导入未完成。${describe(failure)}`;
  } finally {
    if (!disposed) busy.value = false;
  }
}
async function create(recheck = false) {
  if (
    busy.value ||
    (!recheck && (!valid.value || uncertain.value || knownTaskId.value))
  )
    return;
  if (!frozen.value) {
    frozen.value = {
      requestId,
      name: taskName.value,
      recipientCountry: draft.recipientCountry,
      senderCountry: draft.senderCountry === "ANY" ? null : draft.senderCountry,
      ...(draft.senderPhone.trim() ? { senderPhone: draft.senderPhone.trim() } : {}),
      body: draft.body
    };
    originalText = recipients.value?.text ?? "";
  }
  busy.value = true;
  error.value = "";
  note.value = "";
  try {
    const result = await props.source.createTask({ ...frozen.value });
    if (disposed) return;
    knownTaskId.value = result.taskId;
    confirmed.value = true;
    uncertain.value = false;
    if (recheck)
      note.value = "创建已核对。可显式导入原号码，或查看待审核任务；不会自动启动。";
    else if (!originalText.trim()) done();
  } catch (failure) {
    if (disposed) return;
    uncertain.value = failure instanceof MessageTaskCreationUnconfirmed;
    if (failure instanceof MessageTaskCreationUnconfirmed)
      knownTaskId.value = failure.taskId;
    error.value = uncertain.value
      ? "创建结果未确认，原请求和草稿已冻结。请使用原身份核对，不会自动重试。"
      : describe(failure);
  } finally {
    if (!disposed) busy.value = false;
  }
  if (!disposed && !recheck && confirmed.value && originalText.trim())
    await importOriginal();
}
async function inspect() {
  if (!knownTaskId.value || busy.value) return;
  busy.value = true;
  try {
    const result = await props.source.loadTask(knownTaskId.value);
    if (!disposed)
      note.value = `当前实际收件人数：${result.task.sendTotal ?? "不可用"}；调度状态：${result.task.state ?? "不可用"}。`;
  } catch (failure) {
    if (!disposed) error.value = describe(failure);
  } finally {
    if (!disposed) busy.value = false;
  }
}
onBeforeUnmount(() => {
  disposed = true;
});
</script>
<template>
  <el-drawer
    v-model="open"
    title="创建消息任务"
    size="min(680px, 100%)"
    class="message-task-create"
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    @opened="nameInput?.focus()"
  >
    <p class="create-context">
      Project <strong>messages</strong
      ><span v-if="source.mode === 'mock'"> · Mock，不会实际发送</span>
    </p>
    <p class="create-hint">
      创建后等待审核。可先创建空任务，也可同时导入收件人，核对实际数量后再启动。
    </p>
    <el-form id="message-task-form" label-position="top" @submit.prevent="create()">
      <fieldset :disabled="busy || !!frozen">
        <section class="create-section">
          <el-form-item label="任务名称（自动生成）"
            ><el-input
              ref="nameInput"
              :model-value="taskName"
              aria-label="任务名称"
              readonly
          /></el-form-item>
          <div class="create-columns">
            <el-form-item label="收件国家"
              ><el-select
                v-model="draft.recipientCountry"
                aria-label="收件国家"
                :disabled="!!frozen || busy"
                ><el-option
                  v-for="country in ['CN', 'US', 'GB']"
                  :key="country"
                  :value="country"
                  :label="country" /></el-select
            ></el-form-item>
            <el-form-item label="发送范围"
              ><el-select
                v-model="draft.senderCountry"
                aria-label="发送范围"
                :disabled="!!frozen || busy"
                ><el-option label="ANY · 不限发送国家" value="ANY" /><el-option
                  v-for="country in ['CN', 'US', 'GB']"
                  :key="country"
                  :value="country"
                  :label="country" /></el-select
            ></el-form-item>
          </div>
          <MessageRecipients
            :key="inputKey"
            :country="draft.recipientCountry"
            :limits="limits"
            :disabled="busy || !!frozen"
            @change="recipients = $event"
            @busy="reading = $event"
          />
          <details class="create-advanced">
            <summary>高级选项</summary>
            <el-form-item label="指定发送号码（可选）"
              ><el-input
                v-model="draft.senderPhone"
                aria-label="指定发送号码"
                maxlength="128"
                placeholder="留空由平台选择"
            /></el-form-item>
            <p>与发送国家共同限制发送 Worker，按现有号码索引精确匹配。</p>
          </details>
          <el-form-item label="JSON 正文"
            ><el-input
              v-model="draft.body"
              type="textarea"
              :rows="7"
              maxlength="4096"
              aria-label="JSON 正文"
          /></el-form-item>
          <p v-if="bodyError" class="create-error" role="alert">{{ bodyError }}</p>
          <details class="create-advanced">
            <summary>正文示例与 Lab 回执说明</summary>
            <p>
              <code>{}</code> 自动送达，后续可人工阅读和回复。receipts_status 可包含
              read、replied；Task 结束后仍可接收回执。
            </p>
          </details>
        </section>
      </fieldset>
      <el-alert
        v-if="error"
        :title="error"
        :type="uncertain || knownTaskId ? 'warning' : 'error'"
        :closable="false"
        role="alert"
      />
      <p v-if="note" role="status">{{ note }}</p>
      <router-link
        v-if="knownTaskId"
        :to="`/messages/tasks/${encodeURIComponent(knownTaskId)}`"
        @click="open = false"
        >查看已知任务 {{ knownTaskId }}</router-link
      >
      <div v-if="frozen" class="create-recovery">
        <el-button v-if="uncertain" :disabled="busy" @click="create(true)"
          >使用原身份核对创建</el-button
        >
        <el-button v-if="knownTaskId" :disabled="busy" @click="inspect"
          >刷新实际数量</el-button
        >
        <el-button
          v-if="confirmed && originalText.trim()"
          :disabled="busy"
          @click="importOriginal"
          >重新导入原号码</el-button
        >
        <el-button :disabled="busy" @click="reset">结束本次提交</el-button>
        <p>结束本次提交会清空本地草稿；已创建的服务端任务继续保留。</p>
      </div>
    </el-form>
    <template #footer
      ><div class="create-footer">
        <span>关闭后保留当前草稿</span
        ><el-button :disabled="busy" @click="open = false">关闭</el-button
        ><el-button
          type="primary"
          form="message-task-form"
          native-type="submit"
          :loading="busy"
          :disabled="!valid || busy || uncertain || !!knownTaskId"
          >{{
            frozen ? "重试创建" : recipients?.validCount ? "创建并导入" : "创建空任务"
          }}</el-button
        >
      </div></template
    >
  </el-drawer>
</template>
<style scoped>
.create-context,
.create-hint,
.create-advanced {
  color: var(--rv-text-secondary);
  font-size: 12px;
  line-height: 1.8;
}
fieldset {
  border: 0;
  margin: 0;
  padding: 0;
}
.create-section {
  padding: 12px 0;
}
.create-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.create-advanced {
  margin-bottom: 16px;
}
.create-advanced summary {
  cursor: pointer;
  padding: 8px 0;
}
.create-error {
  color: var(--el-color-danger);
}
.create-recovery {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}
.create-recovery p {
  width: 100%;
  color: var(--rv-text-secondary);
  font-size: 12px;
}
.create-footer {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: flex-end;
}
.create-footer span {
  margin-right: auto;
  font-size: 12px;
  color: var(--rv-text-secondary);
}
</style>
