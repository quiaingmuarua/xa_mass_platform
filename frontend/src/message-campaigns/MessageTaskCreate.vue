<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import MessageRecipients from "./MessageRecipients.vue";
import { MessageApiError } from "./api";
import type { RecipientLimits } from "@/files/phone-numbers";
import { MessageTaskCreationUnconfirmed, type MessageTaskSource } from "./task-source";
import { describe, freshMessageDraft, type MessageSession } from "./workbench";
const props = defineProps<{
  source: MessageTaskSource;
  limits: RecipientLimits;
  session: MessageSession;
}>();
const emit = defineEmits<{
  created: [taskId: string];
  back: [];
  inspect: [taskId: string, trigger?: HTMLElement];
}>();
const session = props.session;
const draft = computed(() => session.draft);
const reading = ref(false);
let disposed = false;
watch(
  () => props.source.applications,
  (apps) => {
    if (!draft.value.appId && !draft.value.frozen)
      draft.value.appId = apps[0]?.id ?? "";
  },
  { immediate: true }
);
const app = computed(() =>
  props.source.applications.find((app) => app.id === draft.value.appId)
);
const taskName = computed(() => {
  const d = draft.value;
  if (d.frozen) return d.frozen.name;
  const at = new Date(d.createdAt),
    pad = (n: number) => String(n).padStart(2, "0");
  return `msg-${d.appId}-${d.senderCountry}-${d.recipientCountry}-${at.getFullYear()}${pad(at.getMonth() + 1)}${pad(at.getDate())}-${pad(at.getHours())}${pad(at.getMinutes())}${pad(at.getSeconds())}`.slice(
    0,
    128
  );
});
const bodyError = computed(() => {
  if (!draft.value.body.trim()) return "请填写模板内容";
  return draft.value.body.length > 4096 ? "模板内容最多 4096 字符" : "";
});
const valid = computed(
  () =>
    !!app.value &&
    !bodyError.value &&
    !reading.value &&
    !!draft.value.editor.summary &&
    !draft.value.editor.summary.issues.length &&
    !draft.value.editor.error
);
function reset() {
  session.draft = freshMessageDraft(props.source.applications[0]?.id);
}
function done(id: string) {
  reset();
  emit("created", id);
}
async function importOriginal() {
  const d = draft.value;
  if (!d.knownTaskId || !d.confirmed || d.busy || !d.originalText.trim()) return;
  d.busy = true;
  d.error = "";
  try {
    const receipt = await props.source.importRecipients(d.knownTaskId, d.originalText);
    session.imports[d.knownTaskId] = {
      fileName: d.editor.fileName || "粘贴输入",
      receipt
    };
    if (!disposed && draft.value === d) done(d.knownTaskId);
  } catch (failure) {
    if (!disposed) d.error = `任务已创建，导入未完成。${describe(failure)}`;
  } finally {
    d.busy = false;
  }
}
async function create(recheck = false) {
  const d = draft.value;
  if (d.busy || (!recheck && (!valid.value || d.uncertain || d.knownTaskId))) return;
  // A rejected reconciliation does not settle the earlier unknown submission.
  const priorUnconfirmed = d.uncertain || !!d.knownTaskId;
  if (!d.frozen) {
    d.frozen = {
      appId: d.appId,
      requestId: d.requestId,
      name: taskName.value,
      recipientCountry: d.recipientCountry,
      senderCountry: d.senderCountry === "ANY" ? null : d.senderCountry,
      body: d.body
    };
    d.originalText = d.editor.summary?.text ?? "";
  }
  d.busy = true;
  d.error = "";
  d.note = "";
  try {
    const result = await props.source.createTask({ ...d.frozen });
    d.knownTaskId = result.taskId;
    d.confirmed = true;
    d.uncertain = false;
    if (disposed) return;
    if (recheck)
      d.note = "创建已核对。可显式导入原号码或查看待审核任务，不会自动启动。";
    else if (!d.originalText.trim()) done(result.taskId);
  } catch (failure) {
    if (failure instanceof MessageTaskCreationUnconfirmed) {
      d.uncertain = true;
      d.knownTaskId = failure.taskId ?? d.knownTaskId;
      d.error = "创建结果未确认，原请求和草稿已冻结。请使用原身份核对，不会自动重试。";
    } else if (priorUnconfirmed) {
      d.uncertain = true;
      d.error = `本次核对失败：${describe(failure)} 此前提交仍未确认，原请求和输入继续保留。`;
    } else if (failure instanceof MessageApiError && failure.status === 409) {
      d.knownTaskId = failure.taskId;
      d.error = describe(failure);
      d.note = "请求身份发生冲突，请先核对已有任务。需要新建时，请手动结束本次提交。";
    } else {
      // The source reports unknown outcomes explicitly. A definitive rejection
      // before any known Task can be corrected using the still-unused identity.
      d.frozen = undefined;
      d.originalText = "";
      d.error = describe(failure);
      d.note = "输入已保留，可修改后重新提交。";
    }
  } finally {
    d.busy = false;
  }
  if (
    !disposed &&
    !recheck &&
    draft.value === d &&
    d.confirmed &&
    d.originalText.trim()
  )
    await importOriginal();
}
async function inspect() {
  const d = draft.value;
  if (!d.knownTaskId || d.busy) return;
  d.busy = true;
  try {
    const result = await props.source.loadTask(d.knownTaskId);
    if (!disposed)
      d.note = `当前实际收件人数：${result.task.sendTotal ?? "不可用"}；${result.task.state === "pre_review" ? "仍待审核" : "请在任务预览中核对调度状态"}。`;
  } catch (failure) {
    if (!disposed) d.error = describe(failure);
  } finally {
    d.busy = false;
  }
}
onBeforeUnmount(() => {
  disposed = true;
});
</script>
<template>
  <section class="message-create-page">
    <header class="message-heading">
      <div>
        <el-button link :disabled="draft.busy" @click="emit('back')"
          >返回任务列表</el-button
        >
        <h1>创建消息任务</h1>
      </div>
    </header>
    <div v-if="!source.applications.length" class="message-error" role="alert">
      应用配置不可用。当前不能创建任务。
    </div>
    <form
      id="message-task-form"
      class="message-surface message-create-form"
      @submit.prevent="create()"
    >
      <fieldset :disabled="draft.busy || !!draft.frozen">
        <label class="message-field"
          >任务名称（自动生成）<el-input
            :model-value="taskName"
            aria-label="任务名称"
            readonly
        /></label>
        <div class="message-create-fields">
          <label class="message-field"
            >应用
            <el-select
              v-if="source.applications.length > 1"
              v-model="draft.appId"
              aria-label="应用"
              :disabled="draft.busy || !!draft.frozen"
              ><el-option
                v-for="item in source.applications"
                :key="item.id"
                :label="item.label"
                :value="item.id"
            /></el-select>
            <el-input
              v-else
              :model-value="app?.label ?? '不可用'"
              aria-label="应用"
              readonly
            />
          </label>
          <label class="message-field"
            >收件国家<el-select
              v-model="draft.recipientCountry"
              aria-label="收件国家"
              :disabled="draft.busy || !!draft.frozen"
              ><el-option
                v-for="country in ['CN', 'US', 'GB']"
                :key="country"
                :label="country"
                :value="country" /></el-select
          ></label>
          <label class="message-field"
            >发送国家<el-select
              v-model="draft.senderCountry"
              aria-label="发送范围"
              :disabled="draft.busy || !!draft.frozen"
              ><el-option label="不限国家" value="ANY" /><el-option
                v-for="country in ['CN', 'US', 'GB']"
                :key="country"
                :label="country"
                :value="country" /></el-select
          ></label>
        </div>
        <h2>收件人</h2>
        <MessageRecipients
          :key="draft.requestId"
          :editor="draft.editor"
          :country="draft.recipientCountry"
          :limits="limits"
          :disabled="draft.busy || !!draft.frozen"
          @busy="reading = $event"
        />
        <label class="message-field">
          模板内容（Template content）
          <el-input
            v-model="draft.body"
            aria-label="模板内容"
            aria-describedby="message-content-hint message-content-error"
            :aria-invalid="!!bodyError"
            type="textarea"
            :rows="5"
            :disabled="draft.busy || !!draft.frozen"
          />
        </label>
        <p id="message-content-hint" class="message-hint">
          内容原样提交，由所选应用解释。创建成功不代表内容已通过接收端校验。当前 Lab
          演示要求 JSON 对象文本，普通文本会在执行时被拒绝；填写
          <code>{}</code> 可演示自动送达。
          <br />
          Lab JSON 示例：
          <code class="message-content-example"
            >{"receipts_status":["read","replied"],"delayMs":[1000,4000],"probability":0,"text":"收到了"}</code
          >
          <br />
          <code>receipts_status</code> 指定已读／回复顺序，<code>delayMs</code>
          为步骤延迟（毫秒），<code>probability</code> 为省略最后一步的概率（0～1），
          <code>text</code> 为模拟回复文字。
        </p>
        <p
          v-if="bodyError"
          id="message-content-error"
          class="message-error"
          role="alert"
        >
          {{ bodyError }}
        </p>
      </fieldset>
      <el-alert
        v-if="draft.error"
        :title="draft.error"
        :type="draft.uncertain || draft.knownTaskId ? 'warning' : 'error'"
        :closable="false"
        role="alert"
      />
      <p v-if="draft.note" role="status">{{ draft.note }}</p>
      <div v-if="draft.frozen" class="message-recovery">
        <button
          v-if="draft.knownTaskId"
          type="button"
          class="message-link"
          :disabled="draft.busy"
          @click="
            emit('inspect', draft.knownTaskId, $event.currentTarget as HTMLElement)
          "
        >
          查看已知任务 {{ draft.knownTaskId }}
        </button>
        <div class="message-controls">
          <el-button v-if="draft.uncertain" :disabled="draft.busy" @click="create(true)"
            >使用原身份核对创建</el-button
          >
          <el-button v-if="draft.knownTaskId" :disabled="draft.busy" @click="inspect"
            >刷新实际数量</el-button
          >
          <el-button
            v-if="draft.confirmed && draft.originalText.trim()"
            :disabled="draft.busy"
            @click="importOriginal"
            >重新导入原号码</el-button
          >
          <el-button :disabled="draft.busy" @click="reset">结束本次提交</el-button>
        </div>
        <p class="message-hint">结束本次提交只清空本地草稿，服务端任务继续保留。</p>
      </div>
      <footer class="message-create-footer">
        <span class="message-hint"
          >创建后待审核 · 草稿仅保留在本次控制台会话，刷新页面会丢失</span
        ><el-button
          type="primary"
          native-type="submit"
          :loading="draft.busy"
          :disabled="!valid || draft.busy || draft.uncertain || !!draft.knownTaskId"
          >{{
            draft.frozen
              ? "重试创建"
              : draft.editor.summary?.validCount
                ? "创建并导入"
                : "创建空任务"
          }}</el-button
        >
      </footer>
    </form>
  </section>
</template>
