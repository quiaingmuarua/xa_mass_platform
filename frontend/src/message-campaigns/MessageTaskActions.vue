<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { ElMessageBox } from "element-plus";
import "element-plus/theme-chalk/el-message-box.css";
import MessageRecipients from "./MessageRecipients.vue";
import { MessageApiError } from "./api";
import { senderRange, type MessageTask, type MessageTaskSource } from "./task-source";
import type { RecipientInput, RecipientLimits } from "@/files/phone-numbers";
const props = defineProps<{
  task: MessageTask;
  source: MessageTaskSource;
  stale: boolean;
  limits: RecipientLimits;
}>();
const emit = defineEmits<{ changed: [taskId: string] }>();
const importing = ref(false),
  busy = ref(false),
  reading = ref(false);
const input = ref<RecipientInput>();
const managementRoot = ref<HTMLElement>();
const error = ref(""),
  note = ref(""),
  inputKey = ref(0);
let generation = 0,
  confirmationOpen = false;
const business = computed(
  () => !props.task.managed && !!props.task.body && !!props.task.recipientCountry
);
const review = computed(() => props.task.state === "pre_review");
const mutable = computed(
  () => business.value && !!props.task.state && props.task.state !== "terminal"
);
const disabled = computed(() => props.stale || busy.value);
function clear() {
  generation++;
  if (confirmationOpen) ElMessageBox.close();
  confirmationOpen = false;
  importing.value = false;
  busy.value = false;
  input.value = undefined;
  inputKey.value++;
  error.value = "";
  note.value = "";
}
watch(() => props.task.taskId, clear);
onBeforeUnmount(clear);
function describe(failure: unknown) {
  let message = failure instanceof Error ? failure.message : "操作失败，请核对任务";
  if (failure instanceof MessageApiError && failure.confirmedAddedCount !== undefined)
    message += `；已确认写入 ${failure.confirmedAddedCount}，已存在 ${failure.existingCount ?? "未知"}，未确认部分请刷新实际数量核对。`;
  return message;
}
async function manage(action: "approve" | "close", event: MouseEvent) {
  const trigger =
    event.currentTarget instanceof HTMLElement ? event.currentTarget : undefined;
  if (disabled.value || !mutable.value) return;
  const current = ++generation,
    task = { ...props.task };
  const title =
    action === "approve"
      ? "核对并启动"
      : task.state === "pre_review"
        ? "取消任务"
        : "中止任务";
  busy.value = true;
  error.value = "";
  confirmationOpen = true;
  try {
    await ElMessageBox.confirm(
      `发送范围：${senderRange(task.senderCountry)}；收件国家：${task.recipientCountry}；实际收件人数：${task.sendTotal ?? "不可用"}。${action === "close" ? "保留已有结果，已经发送消息的后续回执仍可更新。" : "确认后开始发送。"}`,
      title,
      {
        confirmButtonText: "确认",
        cancelButtonText: "取消",
        type: action === "close" ? "warning" : "info"
      }
    );
    confirmationOpen = false;
    if (current !== generation || task.taskId !== props.task.taskId || props.stale)
      return;
    if (action === "approve")
      await props.source.approveTask(task.taskId, task.sendTotal!);
    else await props.source.closeTask(task.taskId);
    if (current === generation) {
      note.value = `${title}请求已确认。`;
      emit("changed", task.taskId);
    }
  } catch (failure) {
    if (current === generation && failure !== "cancel" && failure !== "close")
      error.value = describe(failure);
  } finally {
    if (current === generation) {
      busy.value = false;
      confirmationOpen = false;
      await nextTick();
      if (current === generation && task.taskId === props.task.taskId) {
        if (trigger?.isConnected && !trigger.hasAttribute("disabled"))
          trigger.focus({ preventScroll: true });
        else managementRoot.value?.focus({ preventScroll: true });
      }
    }
  }
}
async function upload() {
  if (
    disabled.value ||
    reading.value ||
    !input.value?.validCount ||
    input.value.issues.length
  )
    return;
  const current = ++generation,
    id = props.task.taskId,
    text = input.value.text;
  busy.value = true;
  error.value = "";
  try {
    const receipt = await props.source.importRecipients(id, text);
    if (current !== generation || id !== props.task.taskId) return;
    note.value = `本次已确认写入 ${receipt.confirmedAddedCount}，已存在 ${receipt.existingCount}，文件内重复 ${receipt.duplicateCount}。任务仍待审核。`;
    importing.value = false;
    inputKey.value++;
    input.value = undefined;
    emit("changed", id);
  } catch (failure) {
    if (current === generation) error.value = describe(failure);
  } finally {
    if (current === generation) busy.value = false;
  }
}
</script>
<template>
  <div
    v-if="business"
    ref="managementRoot"
    class="message-task-management"
    tabindex="-1"
    aria-label="任务操作"
  >
    <p v-if="review && task.inputVersion !== '2'">
      旧输入版本仅支持读取和取消，不支持继续导入或启动。
    </p>
    <div v-if="mutable" class="management-actions">
      <template v-if="review && task.inputVersion === '2'">
        <el-button :disabled="disabled" @click="importing = true">导入收件人</el-button>
        <el-button
          type="primary"
          :disabled="disabled || !task.sendTotal"
          @click="manage('approve', $event)"
          >核对并启动</el-button
        >
      </template>
      <el-button :disabled="disabled" @click="manage('close', $event)">{{
        review ? "取消任务" : "中止任务"
      }}</el-button>
    </div>
    <p v-if="note" role="status">{{ note }}</p>
    <el-alert
      v-if="error && !importing"
      :title="error"
      type="warning"
      :closable="false"
      role="alert"
    />
    <el-drawer
      v-model="importing"
      title="导入收件人"
      size="min(680px, 100%)"
      :show-close="!busy"
      :close-on-click-modal="!busy"
      :close-on-press-escape="!busy"
    >
      <p>{{ task.name ?? task.taskId }} · 收件国家 {{ task.recipientCountry }}</p>
      <p>只向当前待审核任务追加。重复号码跳过，导入后不会自动启动。</p>
      <MessageRecipients
        :key="inputKey"
        :country="task.recipientCountry!"
        :limits="limits"
        :disabled="disabled"
        @change="input = $event"
        @busy="reading = $event"
      />
      <el-alert
        v-if="error"
        :title="error"
        type="warning"
        :closable="false"
        role="alert"
      />
      <p>当前实际收件人数：{{ task.sendTotal ?? "不可用" }}</p>
      <el-button :disabled="busy" @click="emit('changed', task.taskId)"
        >刷新实际数量</el-button
      >
      <template #footer
        ><el-button :disabled="busy" @click="importing = false">关闭</el-button
        ><el-button
          type="primary"
          :loading="busy"
          :disabled="
            disabled || reading || !input?.validCount || !!input?.issues.length
          "
          @click="upload"
          >{{ error ? "重新导入" : "导入收件人" }}</el-button
        ></template
      >
    </el-drawer>
  </div>
</template>
<style scoped>
.message-task-management {
  display: grid;
  gap: 12px;
}
.management-actions {
  display: flex;
  gap: 10px;
}
.message-task-management p {
  font-size: 13px;
  color: var(--rv-text-secondary);
  line-height: 1.7;
  margin: 0;
}
</style>
