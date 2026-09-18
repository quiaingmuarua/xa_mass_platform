<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { ElForm, ElFormItem } from "element-plus";
import "element-plus/theme-chalk/el-form.css";
import "element-plus/theme-chalk/el-form-item.css";
import { inspectRecipients, readRecipientFile } from "@/files/phone-numbers";
import {
  MessageTaskCreationUnconfirmed,
  type MessageCountry,
  type MessageTaskSource
} from "./task-source";

const open = defineModel<boolean>({ required: true });
const props = defineProps<{ source: MessageTaskSource }>();
const emit = defineEmits<{ created: [taskId: string] }>();
const knownTaskId = ref<string>();
const initialDraft = () => ({
  recipientCountry: "CN" as MessageCountry,
  senderCountry: "ANY" as MessageCountry | "ANY",
  senderPhone: "",
  recipients: "",
  body: '{\n  "receipts_status": ["read", "replied"],\n  "delayMs": [1000, 4000],\n  "probability": 0.5,\n  "text": "收到了"\n}'
});
const draft = reactive(initialDraft());
const nameInput = ref<{ focus(): void }>();
const busy = ref(false);
const uncertain = ref(false);
const error = ref("");
const fileError = ref("");
const fileName = ref("");
const reading = ref(false);
const nameTime = ref(new Date());
let requestId = crypto.randomUUID();
let readGeneration = 0;
let disposed = false;
const recipients = computed(() =>
  inspectRecipients(draft.recipients, draft.recipientCountry, 1000)
);
const taskName = computed(() => {
  const at = nameTime.value;
  const pad = (value: number) => String(value).padStart(2, "0");
  const day = `${at.getFullYear()}${pad(at.getMonth() + 1)}${pad(at.getDate())}`;
  const time = `${pad(at.getHours())}${pad(at.getMinutes())}${pad(at.getSeconds())}`;
  return `msg-${draft.senderCountry}-${draft.recipientCountry}-${recipients.value.validCount}-${day}-${time}`;
});
watch(open, (visible) => {
  if (visible && !busy.value && !uncertain.value) nameTime.value = new Date();
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
    recipients.value.recipients.length > 0 &&
    recipients.value.issues.length === 0 &&
    !reading.value
);

async function chooseFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  const current = ++readGeneration;
  reading.value = true;
  fileError.value = "";
  try {
    const text = await readRecipientFile(file);
    if (!disposed && current === readGeneration) {
      draft.recipients = text;
      fileName.value = file.name;
    }
  } catch (failure) {
    if (!disposed && current === readGeneration)
      fileError.value = failure instanceof Error ? failure.message : "号码文件读取失败";
  } finally {
    input.value = "";
    if (!disposed && current === readGeneration) reading.value = false;
  }
}
async function submit() {
  if (!valid.value || busy.value || uncertain.value) return;
  busy.value = true;
  error.value = "";
  nameTime.value = new Date();
  try {
    const result = await props.source.createTask({
      requestId,
      name: taskName.value,
      recipientCountry: draft.recipientCountry,
      senderCountry: draft.senderCountry === "ANY" ? null : draft.senderCountry,
      ...(draft.senderPhone.trim() ? { senderPhone: draft.senderPhone.trim() } : {}),
      recipientIds: [...recipients.value.recipients],
      body: draft.body
    });
    if (disposed) return;
    Object.assign(draft, initialDraft());
    fileName.value = "";
    fileError.value = "";
    requestId = crypto.randomUUID();
    open.value = false;
    emit("created", result.taskId);
  } catch (failure) {
    if (disposed) return;
    uncertain.value = failure instanceof MessageTaskCreationUnconfirmed;
    knownTaskId.value =
      failure instanceof MessageTaskCreationUnconfirmed ? failure.taskId : undefined;
    error.value = uncertain.value
      ? "提交结果未确认。已保留草稿，请返回任务列表核对；不会自动重试。"
      : failure instanceof Error
        ? failure.message
        : "创建失败，已保留输入。";
  } finally {
    if (!disposed) busy.value = false;
  }
}
onBeforeUnmount(() => {
  disposed = true;
  readGeneration++;
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
      ><span v-if="source.mode === 'mock'">Mock 数据 · 只在本次会话内创建</span>
    </p>
    <p class="create-hint">
      全部消息追加成功后自动批准，无需人工审核。<span v-if="source.mode === 'mock'"
        >当前为 Mock，不会实际发送。</span
      >
    </p>
    <el-form id="message-task-form" label-position="top" @submit.prevent="submit">
      <fieldset :disabled="busy || uncertain">
        <section class="create-section">
          <h3><span>01</span>基本信息</h3>
          <el-form-item label="任务名称（自动生成）">
            <el-input
              ref="nameInput"
              :model-value="taskName"
              aria-label="任务名称"
              readonly
            />
          </el-form-item>
          <p class="create-hint">
            按发送国家、收件国家、有效号码数和提交时间（本地时间）生成。名称用于展示，Task
            ID 由服务端生成。
          </p>
        </section>
        <section class="create-section">
          <h3><span>02</span>收件人与发送范围</h3>
          <div class="create-columns">
            <el-form-item label="收件国家">
              <el-select v-model="draft.recipientCountry" aria-label="收件国家">
                <el-option
                  v-for="country in ['CN', 'US', 'GB']"
                  :key="country"
                  :value="country"
                  :label="country"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="发送范围">
              <el-select v-model="draft.senderCountry" aria-label="发送范围">
                <el-option label="ANY · 不限发送国家" value="ANY" />
                <el-option
                  v-for="country in ['CN', 'US', 'GB']"
                  :key="country"
                  :value="country"
                  :label="country"
                />
              </el-select>
            </el-form-item>
          </div>
          <p class="create-hint">
            一份号码文件对应一个收件国家。发送 Worker 的国家可以不同。
          </p>
          <div class="recipient-file">
            <label for="message-recipient-file">导入号码文件</label>
            <span>UTF-8 · 每行一个号码 · 最大 1 MiB</span>
            <input
              id="message-recipient-file"
              type="file"
              accept=".txt,text/plain"
              aria-label="号码文件"
              :disabled="reading || busy || uncertain"
              @change="chooseFile"
            />
          </div>
          <p v-if="fileName" class="create-hint">
            已读取 {{ fileName }}，可以继续编辑。
          </p>
          <p v-if="fileError" class="create-error" role="alert">{{ fileError }}</p>
          <el-form-item :label="`收件号码 · 有效 ${recipients.validCount} / 1,000`">
            <el-input
              v-model="draft.recipients"
              aria-label="收件号码"
              type="textarea"
              :rows="5"
              placeholder="+8613800000001&#10;+8613800000002"
            />
          </el-form-item>
          <ul v-if="recipients.issues.length" class="create-error" role="alert">
            <li v-for="(issue, index) in recipients.issues.slice(0, 10)" :key="index">
              第 {{ issue.line }} 行：{{ issue.message }}
            </li>
          </ul>
          <p v-if="recipients.issues.length > 10" class="create-error">
            共 {{ recipients.issues.length }} 处问题，显示前 10 处。
          </p>
          <p class="create-hint">
            文件只在浏览器读取。检查格式、国家前缀与重复，不验证号码是否真实可达。
          </p>
          <details class="create-advanced">
            <summary>高级选项</summary>
            <el-form-item label="指定发送号码（可选）">
              <el-input
                v-model="draft.senderPhone"
                aria-label="指定发送号码"
                maxlength="128"
                placeholder="留空由平台选择"
              />
            </el-form-item>
            <p class="create-hint">与发送国家共同限制发送 Worker。</p>
          </details>
        </section>
        <section class="create-section">
          <h3><span>03</span>消息内容</h3>
          <el-form-item label="JSON 正文">
            <el-input
              v-model="draft.body"
              type="textarea"
              :rows="7"
              maxlength="4096"
              aria-label="JSON 正文"
              class="json-editor"
            />
          </el-form-item>
          <p v-if="bodyError" class="create-error" role="alert">{{ bodyError }}</p>
          <details class="create-advanced">
            <summary>正文示例与 Lab 回执说明</summary>
            <p>
              Lab 接收成功生成 delivered；receipts_status 可填写
              read、replied，允许多次回复。delayMs 是每一步的延迟或随机区间，probability
              是省略最后一步的概率，text 是回复内容。
            </p>
            <p><code>{}</code> 只自动送达，后续可人工阅读和回复。</p>
          </details>
        </section>
      </fieldset>
      <el-alert
        v-if="error"
        :title="error"
        :type="uncertain ? 'warning' : 'error'"
        :closable="false"
        role="alert"
      />
      <router-link
        v-if="knownTaskId"
        :to="`/messages/tasks/${encodeURIComponent(knownTaskId)}`"
        @click="open = false"
        >查看已知任务 {{ knownTaskId }}</router-link
      >
    </el-form>
    <template #footer>
      <div class="create-footer">
        <span>关闭后保留当前草稿</span>
        <el-button :disabled="busy" @click="open = false">取消</el-button>
        <el-button
          type="primary"
          native-type="submit"
          form="message-task-form"
          :loading="busy"
          :disabled="!valid || busy || uncertain"
          >创建并开始发送</el-button
        >
      </div>
    </template>
  </el-drawer>
</template>

<style>
.message-task-create .el-drawer__header {
  margin: 0;
  padding: 24px;
  border-bottom: 1px solid var(--rv-border);
  color: var(--rv-text);
}
.message-task-create .el-drawer__title {
  font-size: 20px;
  font-weight: 650;
}
.message-task-create .el-drawer__body {
  padding: 20px 28px;
}
.message-task-create .el-drawer__footer {
  padding: 18px 24px;
  border-top: 1px solid var(--rv-border);
}
.message-task-create .el-select {
  width: 100%;
}
.message-task-create fieldset {
  min-width: 0;
  border: 0;
  padding: 0;
  margin: 0;
}
.create-context {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 18px;
  font-size: 12px;
  color: var(--rv-text-secondary);
}
.create-context span {
  margin-left: auto;
}
.create-section {
  padding: 6px 0 22px;
}
.create-section + .create-section {
  border-top: 1px solid var(--rv-border);
  padding-top: 20px;
}
.create-section h3 {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 0 0 20px;
  font-size: 15px;
}
.create-section h3 > span {
  font-size: 11px;
  color: var(--rv-primary);
  background: var(--rv-primary-soft);
  border-radius: 6px;
  padding: 5px 7px;
}
.create-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}
.create-hint {
  margin: 8px 0 16px;
  color: var(--rv-text-secondary);
  font-size: 12px;
  line-height: 1.7;
}
.recipient-file {
  display: grid;
  gap: 8px;
  padding: 16px;
  border: 1px dashed var(--rv-border-strong);
  border-radius: 8px;
  margin: 16px 0;
}
.recipient-file label {
  font-size: 13px;
  font-weight: 600;
}
.recipient-file span {
  font-size: 12px;
  color: var(--rv-text-secondary);
}
.recipient-file input {
  max-width: 100%;
  font-size: 12px;
}
.recipient-file input::file-selector-button {
  padding: 7px 12px;
  border: 1px solid var(--rv-border-strong);
  border-radius: 6px;
  background: var(--rv-surface);
  color: var(--rv-text);
  margin-right: 10px;
  cursor: pointer;
}
.create-advanced {
  font-size: 12px;
  color: var(--rv-text-secondary);
  line-height: 1.8;
}
.create-advanced summary {
  cursor: pointer;
  padding: 8px 0;
  color: var(--rv-text);
}
.create-advanced .el-form-item {
  margin-top: 12px;
}
.create-error {
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.7;
}
.json-editor textarea {
  font-family: Consolas, monospace;
  font-size: 12px;
  line-height: 1.7;
}
.create-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}
.create-footer > span {
  margin-right: auto;
  color: var(--rv-text-secondary);
  font-size: 12px;
}
.create-footer .el-button + .el-button {
  margin-left: 0;
}
@media (max-width: 600px) {
  .message-task-create .el-drawer__body {
    padding: 16px;
  }
  .message-task-create .el-drawer__header,
  .message-task-create .el-drawer__footer {
    padding: 16px;
  }
  .create-context {
    flex-wrap: wrap;
  }
  .create-context span {
    margin-left: 0;
    flex-basis: 100%;
  }
  .create-columns {
    gap: 12px;
  }
  .create-footer > span {
    display: none;
  }
}
</style>
