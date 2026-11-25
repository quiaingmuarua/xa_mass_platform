<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElDropdown, ElDropdownMenu, ElDropdownItem } from "element-plus";
import "element-plus/es/components/dropdown/style/css";
import "element-plus/es/components/dropdown-menu/style/css";
import "element-plus/es/components/dropdown-item/style/css";
import { useRuntimeViewerConfig } from "@/runtime-context";
import { consoleIdentity } from "@/console-identity";
import JsonBlock from "@/components/JsonBlock.vue";
import TaskSupplyEditor from "@/components/TaskSupplyEditor.vue";
import RpcTaskDebug from "./RpcTaskDebug.vue";
import WorkbenchTaskTable from "./components/WorkbenchTaskTable.vue";
import TaskStatusBadge from "./components/TaskStatusBadge.vue";
import TaskDescriptorPanel from "./components/TaskDescriptorPanel.vue";
import GroupCapabilitiesCard from "./components/GroupCapabilitiesCard.vue";
import RpcWindowStats from "./components/RpcWindowStats.vue";
import { copyText, useViewGuard } from "./helpers";
import {
  formatCompactTime,
  formatDateTime,
  formatRefillList,
  isRunningBand,
  presentRpcStatus,
  presentTaskSource,
  rpcCallEndpointPath
} from "./presentation";
import { readSupply } from "@/task-management/supply";
import type { Project, TaskRow } from "@/task-management/projects";
import { rpcTaskId, type RpcTaskPrepareRequest } from "./rpc-task";
import { useTaskSession } from "./session";

const props = defineProps<{ project: Project }>();
const projectId = props.project.projectId;
const session = useTaskSession();
const workspace = session.rpc;
const saved = session.project(projectId);
const draft = saved.rpcCreate;
const config = useRuntimeViewerConfig();
const route = useRoute();
const router = useRouter();

const view = computed(() =>
  route.query.view === "create"
    ? "create"
    : route.query.view === "debug"
      ? "debug"
      : "list"
);
const routeTask = computed(() =>
  typeof route.query.taskId === "string" ? route.query.taskId : ""
);
const user = computed(() => consoleIdentity.requireCurrent());
const createId = computed(() =>
  draft.groupId ? rpcTaskId(projectId, user.value.userId, draft.groupId) : ""
);
const taskId = computed(() =>
  view.value === "create" ? createId.value : routeTask.value
);
const state = computed(() => workspace.detail.value);
const detail = computed(() => state.value.value);
const groups = computed(() => workspace.groups[projectId]);
const list = computed(() => workspace.windows[projectId]);
const groupId = computed(() =>
  view.value === "create" ? draft.groupId : (detail.value?.task?.workerGroupId ?? "")
);
const group = computed(() =>
  groups.value?.value?.find((item) => item.workerGroupId === groupId.value)
);
const attempt = computed(() => session.rpcPreparations[taskId.value]);
const createName = computed({
  get: () =>
    detail.value?.task?.name ??
    (attempt.value ? (attempt.value.request.name ?? "") : draft.name),
  set: (value: string) => {
    if (!attempt.value && !detail.value?.task) draft.name = value;
  }
});
const fresh = computed(
  () =>
    !!detail.value &&
    detail.value.taskId === taskId.value &&
    detail.value.task?.projectId === projectId &&
    !state.value.stale &&
    !state.value.loading
);
const resourceFresh = computed(
  () =>
    !!group.value?.workerGroup &&
    !!groups.value?.value &&
    !groups.value.stale &&
    !groups.value.loading &&
    !session.directory.stale &&
    !session.directory.loading
);
const debuggable = computed(
  () =>
    detail.value?.taskId === taskId.value &&
    detail.value?.task?.projectId === projectId &&
    detail.value.task.idleDisposition === "PARK_WHEN_IDLE" &&
    detail.value.task.metadata["server.inputOwner"] === "raw"
);
const rawRpc = computed(() => fresh.value && debuggable.value);
const callable = computed(
  () =>
    rawRpc.value &&
    resourceFresh.value &&
    ["running-initial", "running_visible"].includes(detail.value?.scoreBand ?? "")
);
const api = computed(() => config.mode === "api");
const canPrepare = computed(
  () =>
    api.value &&
    resourceFresh.value &&
    !attempt.value?.busy &&
    !state.value.loading &&
    (!state.value.stale || state.value.missing) &&
    attempt.value?.outcome !== "rejected" &&
    (state.value.missing ||
      (rawRpc.value &&
        detail.value?.scoreBand !== null &&
        detail.value?.scoreBand !== "terminal")) &&
    ((!!attempt.value && attempt.value.outcome === "unconfirmed") ||
      (view.value === "create" && state.value.missing) ||
      (rawRpc.value && detail.value?.scoreBand === "pre_review"))
);

const rows = computed(() =>
  (list.value?.value?.tasks ?? [])
    .filter((row) => !row.task || row.task.idleDisposition === "PARK_WHEN_IDLE")
    .sort(
      (a, b) =>
        (b.createdAtMillis ?? -1) - (a.createdAtMillis ?? -1) ||
        a.taskId.localeCompare(b.taskId)
    )
);

type InactiveSegment = "all" | "pre_review" | "terminal";
const inactiveSegment = ref<InactiveSegment>("all");
const drawerTab = ref<"config" | "raw">("config");

const baseFiltered = computed(() =>
  rows.value.filter((row) => {
    const search = saved.rpcFilter.search.trim().toLowerCase();
    return (
      (!search ||
        [row.taskId, row.task?.name ?? "", row.task?.metadata.userId ?? ""].some((v) =>
          v.toLowerCase().includes(search)
        )) &&
      (!saved.rpcFilter.group || row.task?.workerGroupId === saved.rpcFilter.group)
    );
  })
);

const activeRows = computed(() =>
  baseFiltered.value.filter((row) => isRunningBand(row.scoreBand))
);

const inactiveRows = computed(() =>
  baseFiltered.value.filter((row) => !isRunningBand(row.scoreBand))
);

const inactiveCounts = computed(() => {
  const counts = {
    all: inactiveRows.value.length,
    pre_review: 0,
    terminal: 0
  };
  for (const row of inactiveRows.value) {
    const category = presentRpcStatus(row).category;
    if (category === "pre_review") counts.pre_review++;
    else if (category === "terminal") counts.terminal++;
  }
  return counts;
});

const tableRows = computed(() =>
  inactiveSegment.value === "all"
    ? inactiveRows.value
    : inactiveRows.value.filter(
        (row) => presentRpcStatus(row).category === inactiveSegment.value
      )
);

const table = ref<InstanceType<typeof WorkbenchTaskTable>>();
const heading = ref<HTMLElement>();
const guard = useViewGuard();
let previousView = "";
let restoring = false;
const error = ref("");
const knownId = ref(routeTask.value);

watch(routeTask, (id) => {
  knownId.value = id;
  if (id) drawerTab.value = "config";
});

const createFrozen = computed(
  () => !!attempt.value || !!detail.value || state.value.loading
);

function go(value: "list" | "create" | "debug", id = "") {
  if (view.value === "list" && !routeTask.value) guard.rememberFocus();
  return router.push({
    path: "/runtime/tasks",
    query: {
      project: projectId,
      tab: "rpc",
      ...(value !== "list" ? { view: value } : {}),
      ...(id ? { taskId: id } : {})
    }
  });
}

function open(row: TaskRow) {
  return go("list", row.taskId);
}

function debug(id: string) {
  return go("debug", id);
}

function eligible(row: TaskRow) {
  return (
    row.task?.idleDisposition === "PARK_WHEN_IDLE" &&
    row.task.metadata["server.inputOwner"] === "raw"
  );
}

const copy = (text: string) =>
  copyText(text, "已复制", "复制失败，请手动复制 Task ID。");

async function sync() {
  const ticket = guard.next();
  const changedView = previousView !== view.value;
  previousView = view.value;
  error.value = "";
  saved.rpcQuery = {
    ...(view.value !== "list" ? { view: view.value } : {}),
    ...(routeTask.value ? { taskId: routeTask.value } : {})
  };
  if (!groups.value) void workspace.loadGroups(projectId);
  if (view.value === "list" && !list.value) void workspace.loadTasks(projectId);
  if (taskId.value) {
    if (workspace.detailKey.value !== projectId + "/" + taskId.value)
      void workspace.loadDetail(projectId, taskId.value);
  } else workspace.clearDetail();
  const scroll = saved.rpcFilter.scroll;
  restoring = true;
  await nextTick();
  if (!guard.isCurrent(ticket)) return;
  if (view.value !== "list" && changedView) heading.value?.focus();
  else if (view.value === "list" && changedView && !routeTask.value)
    guard.restoreFocus();
  if (view.value === "list" && scroll) await table.value?.restoreScrollTop(scroll);
  if (guard.isCurrent(ticket)) restoring = false;
}

watch(
  () => [route.fullPath, createId.value],
  () => {
    if (
      route.path === "/runtime/tasks" &&
      route.query.tab !== "finite" &&
      route.query.tab !== "global" &&
      (!route.query.project || route.query.project === projectId)
    )
      void sync();
  }
);
onMounted(sync);

watch(
  () => draft.groupId,
  () => {
    if (attempt.value) return;
    draft.supply.supplyRows = draft.supply.supplyRows.filter((row) =>
      group.value?.matching.pools.includes(row.poolName)
    );
    // Keep manually edited JSON unchanged and validate it against the new Group.
  }
);

async function refresh() {
  const id = taskId.value;
  await Promise.allSettled([
    workspace.loadGroups(projectId),
    ...(view.value === "list" ? [workspace.loadTasks(projectId)] : []),
    ...(id ? [workspace.loadDetail(projectId, id)] : []),
    ...(session.directory.stale ? [session.loadDirectory()] : [])
  ]);
}

async function prepare() {
  if (!canPrepare.value) return;
  const id = taskId.value;
  const ticket = guard.current();
  error.value = "";
  try {
    const existing = detail.value?.task;
    const metadata = existing
      ? Object.fromEntries(
          Object.entries(existing.metadata).filter(
            ([key]) => key !== "userId" && !key.startsWith("server.")
          )
        )
      : {};
    const request: RpcTaskPrepareRequest = attempt.value?.request ?? {
      projectId,
      workerGroupId: groupId.value,
      ...(existing?.name
        ? { name: existing.name }
        : draft.name.trim()
          ? { name: draft.name.trim() }
          : {}),
      metadata,
      refill:
        existing?.refill ?? readSupply(draft.supply, group.value?.matching.pools ?? [])
    };
    const result = await session.prepareRpc(id, request);
    if (result === "ready") {
      saved.rpcCreatedId = id;
      await workspace.loadTasks(projectId);
      if (guard.isCurrent(ticket)) await go("list");
    } else if (guard.isCurrent(ticket)) await workspace.loadDetail(projectId, id);
  } catch (cause) {
    if (guard.isCurrent(ticket))
      error.value = cause instanceof Error ? cause.message : "准备失败";
  }
}

async function close(done: () => void) {
  const id = routeTask.value;
  done();
  if (
    guard.isDisposed() ||
    view.value !== "list" ||
    !id ||
    taskId.value !== id ||
    (route.query.project && route.query.project !== projectId)
  )
    return;
  await router.push({ query: { project: projectId, tab: "rpc" } });
  const ticket = guard.current();
  await nextTick();
  if (guard.isCurrent(ticket) && !routeTask.value) guard.restoreFocus();
}

function forgetRejected() {
  if (attempt.value?.outcome === "rejected")
    delete session.rpcPreparations[taskId.value];
}

function scroll(event: { scrollTop: number }) {
  if (!restoring && view.value === "list") saved.rpcFilter.scroll = event.scrollTop;
}
</script>
<template>
  <section class="rpc-page raw-tasks tw-page">
    <div v-show="view === 'list'" class="tw-finite-stack">
      <div class="tw-action-bar">
        <div class="tw-filters-inline">
          <el-input
            v-model="saved.rpcFilter.search"
            placeholder="搜索名称 / Task ID / 创建者"
            aria-label="RPC 任务搜索"
            clearable
          />
          <el-select
            v-model="saved.rpcFilter.group"
            placeholder="全部 Group"
            aria-label="RPC Group 筛选"
            clearable
          >
            <el-option
              v-for="g in groups?.value ?? []"
              :key="g.workerGroupId"
              :label="g.workerGroupId"
              :value="g.workerGroupId"
            />
          </el-select>
        </div>
        <div class="tw-button-row">
          <span class="tw-read-at">
            Redis 快照 {{ rows.length }} 条{{
              list?.value?.truncated ? "（已截断）" : ""
            }}
            · {{ formatCompactTime(list?.readAt, "尚未读取") }}
          </span>
          <el-button type="primary" @click="go('create')">创建 RPC Task</el-button>
          <el-button @click="go('debug')">按 Task ID 调试</el-button>
          <el-button :loading="list?.loading" @click="refresh">刷新</el-button>
        </div>
      </div>

      <div v-if="saved.rpcCreatedId || list?.error" class="tw-alerts">
        <el-alert
          v-if="saved.rpcCreatedId"
          type="success"
          title="RPC Task 已确认启用"
          closable
          @close="saved.rpcCreatedId = ''"
        >
          <el-button link @click="go('list', saved.rpcCreatedId)">
            打开 {{ saved.rpcCreatedId }}
          </el-button>
        </el-alert>
        <el-alert
          v-if="list?.error"
          :title="'读取失败，保留已有快照：' + list.error"
          type="error"
          :closable="false"
        />
      </div>

      <section
        class="tw-board"
        data-testid="rpc-active-board"
        aria-label="服务中 RPC 通道看板"
      >
        <header class="tw-board__header">
          <div class="tw-board__title">
            <h2>服务中</h2>
            <span class="tw-board__count">{{ activeRows.length }}</span>
          </div>
          <span class="raw-muted">保留窗口统计 · 手动刷新</span>
        </header>

        <div v-if="activeRows.length" class="tw-board__grid">
          <article v-for="row in activeRows" :key="row.taskId" class="tw-run-card">
            <div class="tw-run-card__head">
              <div class="tw-run-card__identity">
                <el-button link type="primary" @click="open(row)">
                  {{ row.task?.name || row.task?.workerGroupId || row.taskId }}
                </el-button>
                <span v-if="!presentTaskSource(row).raw" class="tw-cell-source">
                  {{ presentTaskSource(row).label }} · 只读
                </span>
              </div>
              <TaskStatusBadge :row="row" mode="rpc" />
            </div>

            <div class="tw-rpc-key-row">
              <code class="tw-rpc-key-row__id" :title="row.taskId">
                {{ row.taskId }}
              </code>
              <el-button size="small" link @click="copy(row.taskId)">复制 ID</el-button>
            </div>

            <RpcWindowStats :stats="row.stats" />

            <footer class="tw-run-card__foot">
              <div class="tw-run-card__meta">
                <code :title="row.task?.workerGroupId">
                  {{ row.task?.workerGroupId ?? "—" }}
                </code>
                <span
                  v-if="row.task"
                  :title="formatRefillList(row.task.refill).join('、') || '无供给'"
                >
                  {{ formatRefillList(row.task.refill).join("、") || "无供给" }}
                </span>
              </div>
              <div class="tw-rpc-row-actions">
                <el-button
                  link
                  type="primary"
                  :disabled="!eligible(row)"
                  @click="debug(row.taskId)"
                >
                  调试
                </el-button>
                <el-button link @click="open(row)">配置</el-button>
              </div>
            </footer>
          </article>
        </div>
        <p v-else class="tw-board__empty raw-muted">当前批次没有服务中的 RPC 通道。</p>
      </section>

      <section class="tw-panel" aria-label="待启用与已停用 RPC 任务">
        <header class="tw-list-bar">
          <div class="tw-segment" role="group" aria-label="RPC 状态筛选">
            <button
              type="button"
              :class="{ 'is-active': inactiveSegment === 'all' }"
              @click="inactiveSegment = 'all'"
            >
              全部 <span>{{ inactiveCounts.all }}</span>
            </button>
            <button
              type="button"
              :class="{ 'is-active': inactiveSegment === 'pre_review' }"
              @click="inactiveSegment = 'pre_review'"
            >
              待启用 <span>{{ inactiveCounts.pre_review }}</span>
            </button>
            <button
              type="button"
              :class="{ 'is-active': inactiveSegment === 'terminal' }"
              @click="inactiveSegment = 'terminal'"
            >
              已停用 <span>{{ inactiveCounts.terminal }}</span>
            </button>
          </div>
          <span class="raw-muted">待启用与已停用 · 共 {{ tableRows.length }} 条</span>
        </header>

        <WorkbenchTaskTable
          ref="table"
          mode="rpc"
          :rows="tableRows"
          :loading="list?.loading"
          :error="list?.error"
          @open="open"
          @scroll="scroll"
        >
          <template #actions="{ row }">
            <div class="tw-rpc-row-actions">
              <el-button
                link
                type="primary"
                :disabled="!eligible(row)"
                @click="debug(row.taskId)"
              >
                调试
              </el-button>
              <el-dropdown
                trigger="click"
                @command="
                  (command: string) =>
                    command === 'debug'
                      ? debug(row.taskId)
                      : command === 'copy'
                        ? copy(row.taskId)
                        : open(row)
                "
              >
                <el-button link>操作 ▾</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="view">查看配置</el-dropdown-item>
                    <el-dropdown-item command="debug" :disabled="!eligible(row)">
                      网页调试
                    </el-dropdown-item>
                    <el-dropdown-item command="copy">复制 Task ID</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </WorkbenchTaskTable>
      </section>
    </div>

    <section v-if="view !== 'list'" class="tw-panel tw-panel--padded">
      <header class="tw-section-header">
        <div>
          <h2 ref="heading" tabindex="-1">
            {{ view === "create" ? "创建 RPC Task" : "RPC 网页调试" }}
          </h2>
          <p class="raw-muted">
            Project · {{ projectId }} · 当前用户 {{ user.displayName }}（{{
              user.userId
            }}）
          </p>
        </div>
        <el-button @click="go('list')">返回任务列表</el-button>
      </header>

      <template v-if="view === 'create'">
        <div class="rpc-create-fields">
          <label>
            WorkerGroup
            <el-select
              v-model="draft.groupId"
              filterable
              :disabled="attempt?.busy"
              aria-label="RPC WorkerGroup"
              placeholder="选择已注册 Group"
            >
              <el-option
                v-for="g in groups?.value ?? []"
                :key="g.workerGroupId"
                :value="g.workerGroupId"
                :label="g.workerGroupId + (g.workerGroup ? '' : '（未注册）')"
                :disabled="!g.workerGroup"
              />
            </el-select>
          </label>
          <label>
            任务名称（可选）
            <el-input
              v-model="createName"
              :disabled="createFrozen"
              :maxlength="128"
              aria-label="RPC 任务名称"
            />
          </label>
        </div>

        <p v-if="createId" class="rpc-page__identity">
          Task ID：<code class="tw-code-inline">{{ createId }}</code>
        </p>
        <p class="raw-muted">
          同一 Project、用户和 Group 复用一个长期任务。优先级 0，最大重试
          3；创建时一并批准。
        </p>

        <GroupCapabilitiesCard
          v-if="draft.groupId"
          :group="group"
          :group-id="draft.groupId"
          compact
        />

        <template v-if="detail?.task && fresh">
          <el-alert
            title="此身份已有任务，配置保持不变"
            type="info"
            :closable="false"
          />
          <TaskDescriptorPanel :task="detail" mode="rpc" />
          <div class="tw-button-row">
            <el-button @click="go('list', detail.taskId)">打开已有任务</el-button>
            <el-button v-if="rawRpc" type="primary" @click="debug(detail.taskId)">
              前往网页调试
            </el-button>
          </div>
        </template>
        <template v-else>
          <section class="tw-card">
            <header class="tw-card__header">
              <h3>Task Pool 供给</h3>
            </header>
            <JsonBlock
              v-if="attempt"
              :value="(attempt.request.refill ?? []).map((row) => ({ ...row }))"
            />
            <TaskSupplyEditor
              v-else
              v-model:mode="draft.supply.refillMode"
              v-model:text="draft.supply.refillText"
              v-model:rows="draft.supply.supplyRows"
              :pools="group?.matching.pools ?? []"
              :disabled="createFrozen || !resourceFresh"
            />
          </section>
        </template>

        <div class="raw-tasks__actions">
          <el-button
            type="primary"
            :disabled="!canPrepare"
            :loading="attempt?.busy"
            @click="prepare"
          >
            {{
              attempt?.outcome === "unconfirmed" || detail?.scoreBand === "pre_review"
                ? "继续准备原 Task"
                : "创建并启用"
            }}
          </el-button>
          <el-button :loading="state.loading || groups?.loading" @click="refresh">
            刷新核对
          </el-button>
        </div>
        <el-alert
          v-if="!api"
          title="Mock 仅展示创建配置，不执行准备。"
          type="info"
          :closable="false"
        />
      </template>

      <template v-if="view === 'debug'">
        <form class="rpc-task-target" @submit.prevent="debug(knownId.trim())">
          <el-input
            v-model="knownId"
            placeholder="输入已知 Task ID"
            aria-label="调试 Task ID"
          />
          <el-button native-type="submit" :disabled="!knownId.trim()">
            读取 Task
          </el-button>
          <el-button :disabled="!taskId" @click="refresh">刷新</el-button>
        </form>
        <div v-if="taskId" class="tw-debug-meta">
          <span class="rpc-page__identity">
            Task ID：<code class="tw-code-inline">{{ taskId }}</code>
          </span>
          <TaskStatusBadge v-if="detail" :row="detail" mode="rpc" show-raw-band />
          <span class="tw-read-at">
            最近读取 {{ formatDateTime(state.readAt, "尚未成功读取") }}
          </span>
        </div>
        <el-alert
          v-if="detail?.task && !debuggable && !state.loading"
          title="该任务的 Project、生命周期或输入 Owner 不支持当前通用 RPC 调试。"
          type="info"
          :closable="false"
        />
        <RpcTaskDebug
          v-if="taskId && debuggable"
          :key="taskId"
          :task-id="taskId"
          :task="detail"
          :group="group"
          :enabled="api && callable"
          :readable="api && rawRpc"
          :mode="config.mode"
        />
      </template>

      <div class="tw-alerts">
        <el-alert
          v-if="groups?.error"
          :title="'Group 能力读取失败：' + groups.error"
          type="error"
          :closable="false"
        />
        <el-alert
          v-if="state.error && !state.missing"
          :title="'读取失败，保留已有快照：' + state.error"
          type="error"
          :closable="false"
        />
        <el-alert
          v-if="state.missing && view === 'debug'"
          title="Task 不存在，请核对身份；调试不会创建任务。"
          type="info"
          :closable="false"
        />
        <el-alert
          v-if="group && !group.workerGroup"
          title="Group 尚未注册，请完成资源注册后刷新。"
          type="warning"
          :closable="false"
        />
        <el-alert
          v-if="attempt?.error"
          :title="attempt.error"
          type="error"
          :closable="false"
        >
          {{
            attempt.outcome === "unconfirmed"
              ? "准备未确认，保留原 Task ID 和请求，不自动重试。"
              : "请求被拒绝，请核对配置和任务状态。"
          }}
          <el-button v-if="attempt.outcome === 'rejected'" link @click="forgetRejected">
            结束本次准备
          </el-button>
        </el-alert>
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
      </div>
    </section>

    <el-drawer
      :model-value="view === 'list' && !!routeTask"
      title="RPC Task 配置"
      size="760px"
      :before-close="close"
      destroy-on-close
    >
      <div class="tw-drawer-body">
        <header class="tw-drawer-summary">
          <div class="tw-drawer-summary__top">
            <TaskStatusBadge v-if="detail" :row="detail" mode="rpc" show-raw-band />
            <code v-else class="tw-code-inline">{{ routeTask }}</code>
            <div class="tw-button-row">
              <el-button size="small" @click="copy(routeTask)">复制 Task ID</el-button>
              <el-button size="small" @click="refresh">刷新</el-button>
              <el-button
                size="small"
                type="primary"
                :disabled="!rawRpc"
                @click="debug(routeTask)"
              >
                网页调试
              </el-button>
            </div>
          </div>
          <RpcWindowStats :stats="detail?.stats" detailed />
          <div class="tw-rpc-endpoint">
            <span class="tw-rpc-endpoint__method">POST</span>
            <code class="tw-code-inline">{{ rpcCallEndpointPath(routeTask) }}</code>
            <el-button size="small" link @click="copy(rpcCallEndpointPath(routeTask))">
              复制路径
            </el-button>
          </div>
          <div class="tw-drawer-summary__times">
            <span>创建 {{ formatDateTime(detail?.createdAtMillis, "未提供") }}</span>
            <span class="tw-read-at">
              最近读取 {{ formatDateTime(state.readAt, "尚未成功读取")
              }}{{ state.stale ? " · 快照已过期" : "" }}
            </span>
          </div>
        </header>

        <el-alert
          v-if="state.error"
          :title="state.error"
          type="error"
          :closable="false"
        />

        <template v-if="detail?.task">
          <el-alert
            v-if="!rawRpc && fresh"
            title="该任务仅可查看；通用调试要求原始输入 Owner 和 PARK_WHEN_IDLE。"
            type="info"
            :closable="false"
          />
          <el-tabs v-model="drawerTab" class="tw-drawer-tabs">
            <el-tab-pane label="配置与能力" name="config">
              <div class="tw-drawer-pane">
                <TaskDescriptorPanel :task="detail" mode="rpc" />
                <GroupCapabilitiesCard
                  :group="group"
                  :group-id="detail.task.workerGroupId"
                  compact
                />
                <p class="raw-muted">
                  代码使用此 Task ID 调用 <code>items:call</code>，按
                  <code>messageId</code> 通过 <code>results:take</code>
                  核对结果。网页调试可生成完整请求与 cURL。
                </p>
                <el-button
                  v-if="rawRpc && detail.scoreBand === 'pre_review'"
                  type="primary"
                  :disabled="!canPrepare"
                  :loading="attempt?.busy"
                  @click="prepare"
                >
                  继续准备原 Task
                </el-button>
                <el-alert
                  v-if="attempt?.error || error"
                  :title="attempt?.error || error"
                  type="error"
                  :closable="false"
                />
              </div>
            </el-tab-pane>
            <el-tab-pane label="原始 JSON" name="raw">
              <div class="tw-drawer-pane">
                <JsonBlock :value="detail.task" />
              </div>
            </el-tab-pane>
          </el-tabs>
        </template>
      </div>
    </el-drawer>
  </section>
</template>
