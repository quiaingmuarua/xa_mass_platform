<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElDialog } from "element-plus";
import "element-plus/es/components/dialog/style/css";
import AppCheckCreate from "./AppCheckCreate.vue";
import AppCheckList from "./AppCheckList.vue";
import AppCheckPreviewDrawer from "./AppCheckPreviewDrawer.vue";
import { appLabel, countryLabel, type Catalog, type CheckTask } from "./model";
import type { AppCheckTaskSource } from "./task-source";
import {
  createWorkbenchSession,
  freshDraft,
  quantity,
  type WorkbenchSession,
  type PreviewState,
  type TaskAction,
  type ExportFilter
} from "./workbench";
const props = defineProps<{
  source: AppCheckTaskSource;
  catalog: Catalog;
  session?: WorkbenchSession;
}>();
const session = props.session ?? createWorkbenchSession();
const route = useRoute(),
  router = useRouter();
const taskId = computed(() =>
  typeof route.params.taskId === "string" ? route.params.taskId : undefined
);
const creating = computed(() => route.query.view === "create");
const tasks = shallowRef<CheckTask[]>([]),
  previews = ref<Record<string, PreviewState>>({});
const listBusy = ref(false),
  listError = ref(""),
  listReadAt = ref(0),
  truncated = ref(false);
const notice = ref(""),
  createdTaskId = ref<string>();
const current = shallowRef<CheckTask>(),
  detailStale = ref(true);
const listView = ref<InstanceType<typeof AppCheckList>>();
const previewView = ref<InstanceType<typeof AppCheckPreviewDrawer>>();
const actionTask = shallowRef<CheckTask>(),
  confirmation = ref<"approve" | "close">();
const displayConfirmation = ref<"approve" | "close">("approve");
const actionBusy = ref(false),
  actionError = ref("");
const confirmationFocus = ref<HTMLElement>(),
  exportFocus = ref<HTMLElement>();
const exportOpen = ref(false),
  exportFilter = ref<ExportFilter>("all"),
  counts = ref<Record<ExportFilter, number>>();
const exportLoading = ref(false),
  exportError = ref("");
const exportFile = ref<{ url: string; fileName: string; count: number }>();
let disposed = false,
  listGeneration = 0,
  exportGeneration = 0;
let previewTrigger: HTMLElement | undefined, dialogTrigger: HTMLElement | undefined;
let previewCanGoBack = false,
  dialogOrigin = "";
const backgroundPath = computed(() =>
  creating.value ? "/app-checks?view=create" : "/app-checks"
);
const operationsBusy = computed(() => actionBusy.value || session.draft.busy);
watch(
  confirmation,
  (action) => {
    if (action) displayConfirmation.value = action;
  },
  { flush: "sync" }
);
const confirmTitle = computed(() =>
  displayConfirmation.value === "approve"
    ? "核对并启动任务"
    : actionTask.value?.state === "pre_review"
      ? "取消这个任务？"
      : "中止这个任务？"
);
function newDraft() {
  session.draft = {
    ...freshDraft(),
    appId: props.catalog.apps[0].appId,
    country: props.catalog.countries[0]
  };
}
function dismissNotice() {
  notice.value = "";
  createdTaskId.value = undefined;
}
async function loadList() {
  const token = ++listGeneration;
  listBusy.value = true;
  listError.value = "";
  try {
    const value = await props.source.listTasks();
    if (disposed || token !== listGeneration) return;
    tasks.value = value.tasks.slice(0, 100);
    listReadAt.value = Date.now();
    truncated.value = value.truncated || value.tasks.length > 100;
    previews.value = Object.fromEntries(
      tasks.value.map((task) => [
        task.taskId,
        props.source.previewState?.(task.taskId) ?? {}
      ])
    );
  } catch (error) {
    if (!disposed && token === listGeneration)
      listError.value = error instanceof Error ? error.message : "任务读取失败";
  } finally {
    if (token === listGeneration) listBusy.value = false;
  }
}
function rememberList() {
  if (!creating.value && !taskId.value) session.listScroll = window.scrollY;
}
function openTask(id: string, trigger?: HTMLElement) {
  rememberList();
  if (!taskId.value) previewTrigger = trigger;
  void router.push({
    path: "/app-checks/tasks/" + encodeURIComponent(id),
    query: creating.value ? { view: "create" } : {}
  });
}
function closePreview() {
  if (!taskId.value) return;
  if (previewCanGoBack) router.back();
  else void router.replace(backgroundPath.value);
}
function focusBackground(trigger?: HTMLElement) {
  if (trigger?.isConnected) trigger.focus({ preventScroll: true });
  else if (creating.value)
    document
      .querySelector<HTMLElement>("#app-check-create select")
      ?.focus({ preventScroll: true });
  else listView.value?.focusHeading();
}
function restorePreviewFocus() {
  if (disposed || taskId.value) return;
  focusBackground(previewTrigger);
  previewTrigger = undefined;
}
function restoreDialogFocus() {
  if (disposed || route.fullPath !== dialogOrigin) return;
  if (dialogTrigger?.isConnected) dialogTrigger.focus({ preventScroll: true });
  else if (taskId.value) previewView.value?.focusHeading();
  else focusBackground();
}
function openCreate() {
  rememberList();
  dismissNotice();
  void router.push("/app-checks?view=create");
}
function created(id: string) {
  newDraft();
  createdTaskId.value = id;
  notice.value =
    props.source.mode === "mock"
      ? "任务已创建，等待核对并启动。"
      : "任务已创建，可预览查询结果。";
  void router.push("/app-checks");
}
function endUnconfirmedDraft() {
  const draft = session.draft;
  if (!draft.uncertain || draft.busy) return;
  session.unconfirmedSubmissions.push({
    requestId: draft.requestId,
    knownTaskId: draft.knownTaskId,
    appId: draft.appId,
    country: draft.country,
    expectedCount: draft.report?.numbers.length ?? 0,
    sourceFile: draft.fileName || "粘贴号码",
    submittedAt: draft.createdAtMillis,
    endedAt: Date.now()
  });
  newDraft();
  notice.value = "已结束本地草稿并保留关联信息，原提交仍需单独核对。";
}
function clearExportFile() {
  if (exportFile.value) URL.revokeObjectURL(exportFile.value.url);
  exportFile.value = undefined;
}
async function openExport(task: CheckTask) {
  actionTask.value = task;
  exportFilter.value = "all";
  counts.value = undefined;
  exportError.value = "";
  clearExportFile();
  exportOpen.value = true;
  exportLoading.value = true;
  const token = ++exportGeneration;
  try {
    const value = await props.source.exportCounts?.(task.taskId);
    if (token === exportGeneration && !disposed && exportOpen.value)
      counts.value = value;
  } catch (error) {
    if (token === exportGeneration && !disposed)
      exportError.value = error instanceof Error ? error.message : "导出数量读取失败";
  } finally {
    if (token === exportGeneration) exportLoading.value = false;
  }
}
function requestAction(task: CheckTask, action: TaskAction, trigger?: HTMLElement) {
  if (action === "preview") {
    openTask(task.taskId, trigger);
    return;
  }
  if (
    operationsBusy.value ||
    (taskId.value ? detailStale.value : listBusy.value || !!listError.value)
  )
    return;
  actionTask.value = task;
  actionError.value = "";
  dialogOrigin = route.fullPath;
  dialogTrigger = trigger;
  if (action === "export") {
    if (props.source.exportTask && task.state === "terminal") void openExport(task);
  } else if (action === "approve" && props.source.approveTask)
    confirmation.value = action;
  else if (action === "close" && props.source.closeTask) confirmation.value = action;
}
async function refreshVisible(targetId: string) {
  const reads: Promise<unknown>[] = [];
  if (!creating.value) reads.push(loadList());
  if (taskId.value === targetId)
    reads.push(previewView.value?.refresh() ?? Promise.resolve());
  await Promise.all(reads);
}
async function perform(action: "approve" | "close") {
  const target = actionTask.value;
  if (!target || actionBusy.value) return;
  const method =
    action === "approve" ? props.source.approveTask : props.source.closeTask;
  if (!method) return;
  const origin = route.fullPath;
  actionBusy.value = true;
  actionError.value = "";
  dismissNotice();
  try {
    await method.call(props.source, target.taskId);
    if (disposed) return;
    confirmation.value = undefined;
    if (route.fullPath === origin)
      notice.value =
        action === "approve"
          ? "任务已核对并启动。"
          : target.state === "pre_review"
            ? "任务已取消。"
            : "任务已中止，已有结果已保留。";
    await refreshVisible(target.taskId);
  } catch (error) {
    if (!disposed && route.fullPath === origin)
      actionError.value =
        error instanceof Error ? error.message : "操作失败，请刷新后核对。";
  } finally {
    actionBusy.value = false;
  }
}
async function demo(action: "advance" | "complete" | "late") {
  const target = current.value;
  if (!target || operationsBusy.value || detailStale.value) return;
  const origin = route.fullPath;
  actionBusy.value = true;
  actionError.value = "";
  dismissNotice();
  try {
    if (action === "complete") await props.source.completeTask?.(target.taskId);
    else await props.source.advanceTask?.(target.taskId, action === "late");
    if (!disposed) await refreshVisible(target.taskId);
  } catch (error) {
    if (!disposed && route.fullPath === origin)
      actionError.value = error instanceof Error ? error.message : "演示操作失败";
  } finally {
    actionBusy.value = false;
  }
}
async function generateExport() {
  const target = actionTask.value;
  if (!target || !props.source.exportTask || exportLoading.value) return;
  const token = ++exportGeneration,
    selected = exportFilter.value;
  exportLoading.value = true;
  exportError.value = "";
  clearExportFile();
  try {
    const file = await props.source.exportTask(target.taskId, selected);
    if (disposed || token !== exportGeneration || !exportOpen.value) return;
    exportFile.value = {
      url: URL.createObjectURL(file.blob),
      fileName: file.fileName,
      count: file.count
    };
    if (taskId.value === target.taskId) await previewView.value?.refresh();
  } catch (error) {
    if (!disposed && token === exportGeneration)
      exportError.value = error instanceof Error ? error.message : "导出失败";
  } finally {
    if (token === exportGeneration) exportLoading.value = false;
  }
}
watch(exportFilter, clearExportFile);
watch(exportOpen, (open) => {
  if (!open) {
    exportGeneration++;
    exportLoading.value = false;
    clearExportFile();
  }
});
watch(
  creating,
  async (value, previous) => {
    if (value) return;
    await loadList();
    await nextTick();
    if (disposed || creating.value || taskId.value) return;
    if (createdTaskId.value && listView.value?.revealTask(createdTaskId.value)) return;
    if (previous) {
      window.scrollTo({ top: session.listScroll, left: 0, behavior: "instant" });
      listView.value?.focusHeading();
    }
  },
  { immediate: true }
);
watch(
  () => route.fullPath,
  (_path, previous) => {
    if (taskId.value) {
      previewCanGoBack = previous === backgroundPath.value;
      if (
        !previewTrigger &&
        document.activeElement instanceof HTMLElement &&
        document.activeElement !== document.body
      )
        previewTrigger = document.activeElement;
    }
    current.value = undefined;
    detailStale.value = true;
    actionError.value = "";
    confirmation.value = undefined;
    exportOpen.value = false;
  },
  { flush: "sync" }
);
onBeforeUnmount(() => {
  disposed = true;
  listGeneration++;
  exportGeneration++;
  clearExportFile();
});
</script>
<template>
  <div class="checks-workspace" data-testid="app-check-workspace">
    <div v-if="notice && !taskId" role="status" class="checks-notice">
      <span>{{ notice }}</span>
      <button
        v-if="createdTaskId"
        class="checks-link"
        @click="openTask(createdTaskId, $event.currentTarget as HTMLElement)"
      >
        预览新任务
      </button>
      <button class="checks-link" aria-label="关闭操作提示" @click="dismissNotice">
        关闭
      </button>
    </div>
    <p v-if="actionError && !confirmation && !taskId" role="alert" class="checks-error">
      {{ actionError }}
    </p>
    <AppCheckList
      v-if="!creating"
      ref="listView"
      :source="source"
      :catalog="catalog"
      :tasks="tasks"
      :previews="previews"
      :session="session"
      :busy="listBusy"
      :action-busy="actionBusy"
      :submission-busy="session.draft.busy"
      :error="listError"
      :read-at="listReadAt"
      :truncated="truncated"
      :highlighted-task-id="createdTaskId"
      @create="openCreate"
      @refresh="loadList"
      @action="requestAction"
    />
    <AppCheckCreate
      v-else
      :source="source"
      :catalog="catalog"
      :session="session"
      :active="creating"
      @back="router.push('/app-checks')"
      @created="created"
      @end-unconfirmed="endUnconfirmedDraft"
    />
    <AppCheckPreviewDrawer
      ref="previewView"
      :source="source"
      :task-id="taskId"
      :action-busy="actionBusy"
      :submission-busy="session.draft.busy"
      :action-error="confirmation ? '' : actionError"
      :notice="notice"
      @close="closePreview"
      @closed="restorePreviewFocus"
      @action="requestAction"
      @demo="demo"
      @dismiss-notice="dismissNotice"
      @loaded="
        (task, stale) => {
          current = task;
          detailStale = stale;
        }
      "
    />
    <ElDialog
      :model-value="!!confirmation"
      :title="confirmTitle"
      width="520px"
      :close-on-click-modal="!actionBusy"
      :close-on-press-escape="!actionBusy"
      :show-close="!actionBusy"
      @update:model-value="
        (value) => {
          if (!value) confirmation = undefined;
        }
      "
      @closed="restoreDialogFocus"
      @opened="confirmation && confirmationFocus?.focus()"
    >
      <template v-if="actionTask">
        <p ref="confirmationFocus" class="checks-dialog-task" tabindex="-1">
          {{ actionTask.name || actionTask.taskId }}
        </p>
        <p>
          {{ appLabel(actionTask.appId) }} · {{ countryLabel(actionTask.country) }} ·
          {{ quantity(actionTask.totalCount) }} 个号码
        </p>
        <p class="checks-hint">
          {{
            displayConfirmation === "approve"
              ? "请核对应用、地区和号码数量。确认启动后将进入查询。"
              : actionTask.state === "pre_review"
                ? "取消后不能启动，已导入的号码不会开始查询。"
                : "停止后续演示查询，保留已有结果；已开始的执行仍可能返回结果。中止后不能重新启动。"
          }}
        </p>
        <p v-if="actionError" role="alert" class="checks-error">{{ actionError }}</p>
      </template>
      <template #footer>
        <el-button :disabled="actionBusy" @click="confirmation = undefined"
          >返回</el-button
        >
        <el-button
          :type="displayConfirmation === 'close' ? 'danger' : 'primary'"
          :loading="actionBusy"
          @click="confirmation && perform(confirmation)"
        >
          {{
            displayConfirmation === "approve"
              ? "确认启动"
              : actionTask?.state === "pre_review"
                ? "确认取消任务"
                : "确认中止任务"
          }}
        </el-button>
      </template>
    </ElDialog>
    <ElDialog
      v-model="exportOpen"
      title="导出查询结果"
      width="min(520px, calc(100vw - 32px))"
      :close-on-click-modal="!exportLoading"
      :close-on-press-escape="!exportLoading"
      :show-close="!exportLoading"
      @closed="restoreDialogFocus"
      @opened="exportOpen && exportFocus?.focus()"
    >
      <p ref="exportFocus" class="checks-dialog-task" tabindex="-1">
        {{ actionTask?.name }}
      </p>
      <p class="checks-hint">
        仅包含有效成功结果，查询失败、内容异常和未完成号码不会导出。
      </p>
      <label class="checks-field"
        >导出范围<select
          v-model="exportFilter"
          aria-label="导出范围"
          :disabled="exportLoading"
        >
          <option value="all">全部有效成功结果</option>
          <option value="registered">已注册</option>
          <option value="unregistered">未注册</option>
        </select></label
      >
      <div class="checks-export-count">
        <span>本次导出</span
        ><strong>{{
          counts ? quantity(counts[exportFilter]) + " 条" : "数量暂不可用"
        }}</strong
        ><small>CSV · 号码 / 注册状态 / 应用 / 地区</small>
      </div>
      <p v-if="exportError" role="alert" class="checks-error">{{ exportError }}</p>
      <div v-if="exportFile" role="status" class="checks-download-ready">
        <strong>文件已生成</strong
        ><span>{{ exportFile.fileName }} · {{ quantity(exportFile.count) }} 条</span
        ><a
          :href="exportFile.url"
          :download="exportFile.fileName"
          class="checks-download-link"
          >下载 CSV</a
        >
      </div>
      <template #footer
        ><el-button :disabled="exportLoading" @click="exportOpen = false"
          >关闭</el-button
        ><el-button
          type="primary"
          :loading="exportLoading"
          :disabled="!source.exportTask"
          @click="generateExport"
          >{{ exportFile ? "重新生成 CSV" : "生成 CSV 文件" }}</el-button
        ></template
      >
    </ElDialog>
  </div>
</template>
