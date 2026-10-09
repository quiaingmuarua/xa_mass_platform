<script setup lang="ts">
import {
  computed,
  inject,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  shallowRef,
  watch
} from "vue";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import { ElDialog } from "element-plus";
import "element-plus/es/components/dialog/style/css";
import MessageTaskList from "./MessageTaskList.vue";
import MessageTaskCreate from "./MessageTaskCreate.vue";
import MessageTaskPreview from "./MessageTaskPreview.vue";
import MessageTemplateContent from "./MessageTemplateContent.vue";
import MessageRecipients from "./MessageRecipients.vue";
import { useMessageAvailability } from "./availability";
import { MESSAGE_IMPORT_LIMITS } from "@/files/phone-numbers";
import {
  messageTaskSourceKey,
  senderRange,
  type MessageTask,
  type MessageDemoAction
} from "./task-source";
import {
  actionReason,
  applicationLabel,
  createMessageSession,
  describe,
  messageSessionKey,
  quantity,
  recipientEditor,
  type MessageAction
} from "./workbench";
import "./style.css";
const source = inject(messageTaskSourceKey)!;
if (!source) throw new Error("Messages requires an explicit task source");
const session = inject(messageSessionKey, createMessageSession, true);
const availability = useMessageAvailability();
const limits = computed(() =>
  availability.state.value.status === "enabled"
    ? availability.state.value.catalog.limits
    : MESSAGE_IMPORT_LIMITS
);
const route = useRoute(),
  router = useRouter();
const taskId = computed(() =>
  typeof route.params.taskId === "string" ? route.params.taskId : undefined
);
const creating = computed(() => route.query.view === "create");
const background = computed(() =>
  creating.value ? "/messages?view=create" : "/messages"
);
const createVisited = ref(creating.value);
const tasks = shallowRef<MessageTask[]>([]),
  listBusy = ref(false),
  listError = ref(""),
  readAt = ref(0),
  truncated = ref(false);
const listView = ref<InstanceType<typeof MessageTaskList>>(),
  preview = ref<InstanceType<typeof MessageTaskPreview>>();
const current = shallowRef<MessageTask>(),
  detailStale = ref(true);
const notice = ref(""),
  createdId = ref<string>();
const actionError = ref(""),
  actionBusy = ref(false),
  target = shallowRef<MessageTask>();
const confirmation = ref<"approve" | "close">(),
  confirmationLabel = ref("");
const confirmHeading = ref<HTMLElement>();
const importOpen = ref(false),
  importReading = ref(false),
  importNeedsRead = ref(false);
const editor = reactive(recipientEditor()),
  importKey = ref(0);
const blocked = computed(() => actionBusy.value || session.draft.busy);
let disposed = false,
  listGeneration = 0,
  actionGeneration = 0;
let previewTrigger: HTMLElement | undefined, dialogTrigger: HTMLElement | undefined;
let previewBack = false,
  dialogOrigin = "";
async function loadList() {
  const token = ++listGeneration;
  listBusy.value = true;
  try {
    const value = await source.listTasks();
    if (disposed || token !== listGeneration) return;
    tasks.value = value.tasks.slice(0, 100);
    truncated.value = value.truncated || value.tasks.length > 100;
    readAt.value = Date.now();
    listError.value = "";
  } catch (failure) {
    if (!disposed && token === listGeneration) listError.value = describe(failure);
  } finally {
    if (token === listGeneration) listBusy.value = false;
  }
}
function openTask(id: string, trigger?: HTMLElement) {
  if (session.draft.busy) return;
  previewTrigger = trigger;
  void router.push({
    path: `/messages/tasks/${encodeURIComponent(id)}`,
    query: creating.value ? { view: "create" } : {}
  });
}
function closePreview() {
  if (previewBack) router.back();
  else void router.replace(background.value);
}
async function restorePreviewFocus() {
  await nextTick();
  if (disposed || taskId.value) return;
  if (previewTrigger?.isConnected) previewTrigger.focus({ preventScroll: true });
  else if (!creating.value) listView.value?.focusHeading();
  previewTrigger = undefined;
}
function restoreDialogFocus() {
  if (!disposed && route.fullPath === dialogOrigin && dialogTrigger?.isConnected)
    dialogTrigger.focus({ preventScroll: true });
}
async function created(id: string) {
  createdId.value = id;
  notice.value = "任务已创建，核对实际收件人数后再启动。";
  await router.push("/messages");
  await loadList();
  await nextTick();
  if (!disposed && !creating.value && !taskId.value) listView.value?.revealTask(id);
}
async function requestAction(
  task: MessageTask,
  action: MessageAction,
  trigger?: HTMLElement
) {
  if (action === "preview") {
    openTask(task.taskId, trigger);
    return;
  }
  if (
    blocked.value ||
    actionReason(
      task,
      action,
      taskId.value ? detailStale.value : listBusy.value || !!listError.value
    )
  )
    return;
  const token = ++actionGeneration,
    origin = route.fullPath;
  dialogOrigin = origin;
  dialogTrigger = trigger;
  actionBusy.value = true;
  actionError.value = "";
  try {
    const value = await source.loadTask(task.taskId);
    if (disposed || token !== actionGeneration || route.fullPath !== origin) return;
    preview.value?.accept(value);
    const reason = actionReason(value.task, action, false);
    if (reason) throw new Error(reason);
    target.value = value.task;
    if (action === "import") {
      Object.assign(editor, recipientEditor());
      importKey.value++;
      importNeedsRead.value = false;
      importOpen.value = true;
    } else {
      confirmationLabel.value =
        action === "approve"
          ? "核对并启动"
          : value.task.state === "pre_review"
            ? "取消任务"
            : "中止任务";
      confirmation.value = action;
    }
  } catch (failure) {
    if (!disposed && token === actionGeneration) {
      actionError.value = describe(failure);
      listError.value = "任务核对失败，请刷新后再操作。";
      if (taskId.value === task.taskId) preview.value?.invalidate(actionError.value);
    }
  } finally {
    actionBusy.value = false;
  }
}
async function refreshAfter(id: string) {
  await loadList();
  if (!disposed && taskId.value === id) await preview.value?.refresh();
}
async function confirm() {
  if (!target.value || !confirmation.value || blocked.value) return;
  const task = target.value,
    action = confirmation.value,
    token = ++actionGeneration;
  actionBusy.value = true;
  actionError.value = "";
  try {
    if (action === "approve") await source.approveTask(task.taskId, task.sendTotal!);
    else await source.closeTask(task.taskId);
    if (disposed || token !== actionGeneration) return;
    confirmation.value = undefined;
    notice.value = `${confirmationLabel.value}已确认。`;
    await refreshAfter(task.taskId);
  } catch (failure) {
    if (!disposed && token === actionGeneration) {
      actionError.value = describe(failure);
      // A changed count or uncertain result needs a new read and confirmation.
      confirmation.value = undefined;
      detailStale.value = true;
      listError.value = "操作未确认，请刷新任务后重新核对。";
      if (taskId.value === task.taskId) preview.value?.invalidate(actionError.value);
    }
  } finally {
    actionBusy.value = false;
  }
}
async function refreshImport() {
  if (!target.value || blocked.value) return;
  const id = target.value.taskId,
    token = ++actionGeneration;
  actionBusy.value = true;
  try {
    const value = await source.loadTask(id);
    if (disposed || token !== actionGeneration) return;
    target.value = value.task;
    preview.value?.accept(value);
    importNeedsRead.value = false;
    actionError.value = actionReason(value.task, "import", false);
  } catch (failure) {
    if (!disposed && token === actionGeneration) {
      actionError.value = describe(failure);
      importNeedsRead.value = true;
    }
  } finally {
    actionBusy.value = false;
  }
}
async function upload() {
  const task = target.value,
    input = editor.summary;
  if (
    !task ||
    !input?.validCount ||
    input.issues.length ||
    editor.error ||
    blocked.value ||
    importReading.value ||
    importNeedsRead.value ||
    actionReason(task, "import", false)
  )
    return;
  const token = ++actionGeneration;
  actionBusy.value = true;
  actionError.value = "";
  try {
    const receipt = await source.importRecipients(task.taskId, input.text);
    session.imports[task.taskId] = { fileName: editor.fileName || "粘贴输入", receipt };
    if (disposed || token !== actionGeneration) return;
    importOpen.value = false;
    notice.value = `导入已确认：新增 ${quantity(receipt.confirmedAddedCount)}，已存在 ${quantity(receipt.existingCount)}，仍待审核。`;
    await refreshAfter(task.taskId);
  } catch (failure) {
    if (!disposed && token === actionGeneration) {
      actionError.value = describe(failure);
      importNeedsRead.value = true;
    }
  } finally {
    actionBusy.value = false;
  }
}
async function demo(action: MessageDemoAction) {
  const task = current.value;
  if (!task || !source.demonstrate || blocked.value || detailStale.value) return;
  const token = ++actionGeneration;
  actionBusy.value = true;
  actionError.value = "";
  try {
    await source.demonstrate(task.taskId, action);
    if (!disposed && token === actionGeneration) {
      if (action === "fail-read") await preview.value?.refresh();
      else await refreshAfter(task.taskId);
    }
  } catch (failure) {
    if (!disposed && token === actionGeneration) actionError.value = describe(failure);
  } finally {
    actionBusy.value = false;
  }
}
watch(creating, (value) => {
  if (value) createVisited.value = true;
});
watch(
  () => route.fullPath,
  (_path, previous) => {
    previewBack = !!taskId.value && previous === background.value;
    actionGeneration++;
    confirmation.value = undefined;
    importOpen.value = false;
    actionError.value = "";
    current.value = undefined;
    detailStale.value = true;
  },
  { flush: "sync" }
);
onBeforeRouteLeave(() => !session.draft.busy);
onMounted(loadList);
onBeforeUnmount(() => {
  disposed = true;
  listGeneration++;
  actionGeneration++;
});
</script>
<template>
  <div class="message-workspace" data-testid="message-task-workspace">
    <div
      v-if="notice"
      v-show="!taskId && !creating"
      class="message-notice"
      role="status"
    >
      <span>{{ notice }}</span
      ><button
        v-if="createdId"
        class="message-link"
        @click="openTask(createdId, $event.currentTarget as HTMLElement)"
      >
        预览新任务</button
      ><button
        class="message-link"
        aria-label="关闭操作提示"
        @click="
          notice = '';
          createdId = undefined;
        "
      >
        关闭
      </button>
    </div>
    <p
      v-if="actionError && !confirmation && !importOpen && !taskId"
      class="message-error"
      role="alert"
    >
      {{ actionError }}
    </p>
    <MessageTaskList
      v-show="!creating"
      ref="listView"
      :source="source"
      :session="session"
      :tasks="tasks"
      :busy="listBusy"
      :blocked="blocked"
      :error="listError"
      :read-at="readAt"
      :truncated="truncated"
      :highlighted="createdId"
      @create="router.push('/messages?view=create')"
      @refresh="loadList"
      @action="requestAction"
    />
    <MessageTaskCreate
      v-if="createVisited"
      v-show="creating"
      :source="source"
      :session="session"
      :limits="limits"
      @back="router.push('/messages')"
      @created="created"
      @inspect="openTask"
    />
    <MessageTaskPreview
      ref="preview"
      :task-id="taskId"
      :source="source"
      :blocked="blocked"
      :action-error="confirmation || importOpen ? '' : actionError"
      :imported="taskId ? session.imports[taskId] : undefined"
      @close="closePreview"
      @closed="restorePreviewFocus"
      @action="requestAction"
      @loaded="
        (task, stale) => {
          current = task;
          detailStale = stale;
        }
      "
      @demo="demo"
    />
    <ElDialog
      :model-value="!!confirmation"
      :title="confirmationLabel"
      width="520px"
      :close-on-click-modal="!actionBusy"
      :close-on-press-escape="!actionBusy"
      :show-close="!actionBusy"
      @update:model-value="
        (open: boolean) => {
          if (!open) confirmation = undefined;
        }
      "
      @opened="confirmHeading?.focus()"
      @closed="restoreDialogFocus"
    >
      <template v-if="target"
        ><p ref="confirmHeading" tabindex="-1">{{ target.name || target.taskId }}</p>
        <p>
          {{ applicationLabel(target, source.applications) }} ·
          {{ senderRange(target.senderCountry) }} → {{ target.recipientCountry }} ·
          实际收件人数 {{ quantity(target.sendTotal) }}
        </p>
        <MessageTemplateContent
          v-if="confirmation === 'approve'"
          :content="target.body"
        />
        <p v-if="target.senderPhone" class="message-warning">
          旧任务指定发送号码：{{ target.senderPhone }}。该限制保持不变。
        </p>
        <p class="message-hint">
          {{
            confirmation === "approve"
              ? "确认后开始发送。人数变化时需要重新核对。"
              : "保留已有结果和未完成数量，已经发送的消息仍可更新后续回执。"
          }}
        </p></template
      >
      <template #footer
        ><el-button :disabled="actionBusy" @click="confirmation = undefined"
          >返回</el-button
        ><el-button
          type="primary"
          :loading="actionBusy"
          :disabled="actionBusy"
          @click="confirm"
          >确认</el-button
        ></template
      >
    </ElDialog>
    <ElDialog
      v-model="importOpen"
      title="导入收件人"
      width="660px"
      :close-on-click-modal="!actionBusy"
      :close-on-press-escape="!actionBusy"
      :show-close="!actionBusy"
      @closed="restoreDialogFocus"
    >
      <template v-if="target"
        ><p>
          {{ target.name || target.taskId }} ·
          {{ applicationLabel(target, source.applications) }} · 收件国家
          {{ target.recipientCountry }}
        </p>
        <p class="message-hint">
          向当前待审核任务追加，重复号码跳过。导入后不会自动启动。
        </p>
        <MessageRecipients
          v-if="importOpen"
          :key="importKey"
          :editor="editor"
          :country="target.recipientCountry!"
          :limits="limits"
          :disabled="blocked"
          @busy="importReading = $event"
        /><el-alert
          v-if="actionError"
          :title="actionError"
          type="warning"
          :closable="false"
          role="alert"
        />
        <p>
          当前实际收件人数：{{ quantity(target.sendTotal)
          }}{{ importNeedsRead ? " · 需要重新核对" : "" }}
        </p>
        <el-button :disabled="blocked" @click="refreshImport"
          >刷新实际数量</el-button
        ></template
      >
      <template #footer
        ><el-button :disabled="blocked" @click="importOpen = false">关闭</el-button
        ><el-button
          type="primary"
          :loading="actionBusy"
          :disabled="
            blocked ||
            importReading ||
            importNeedsRead ||
            !editor.summary?.validCount ||
            !!editor.summary?.issues.length ||
            !!editor.error ||
            !target ||
            !!actionReason(target, 'import', false)
          "
          @click="upload"
          >确认导入</el-button
        ></template
      >
    </ElDialog>
  </div>
</template>
