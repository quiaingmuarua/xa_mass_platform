<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { Plus, Refresh, Search } from "@element-plus/icons-vue";
import {
  ElTable,
  ElTableColumn,
  ElTabs,
  ElTabPane,
  type TableInstance
} from "element-plus";
import "element-plus/es/components/table/style/css";
import "element-plus/es/components/table-column/style/css";
import "element-plus/es/components/tabs/style/css";
import "element-plus/es/components/tab-pane/style/css";
import { appLabel, countryLabel, type Catalog, type CheckTask } from "./model";
import type { AppCheckTaskSource } from "./task-source";
import {
  taskStage,
  timestamp,
  quantity,
  type PreviewState,
  type TaskAction,
  type WorkbenchSession
} from "./workbench";
import TaskStatus from "./TaskStatus.vue";
import TaskProgress from "./TaskProgress.vue";
import TaskActions from "./TaskActions.vue";
const props = defineProps<{
  source: AppCheckTaskSource;
  catalog: Catalog;
  tasks: CheckTask[];
  previews: Record<string, PreviewState>;
  session: WorkbenchSession;
  busy: boolean;
  actionBusy: boolean;
  submissionBusy: boolean;
  error: string;
  truncated: boolean;
  readAt: number;
  highlightedTaskId?: string;
}>();
const emit = defineEmits<{
  create: [];
  refresh: [];
  action: [task: CheckTask, action: TaskAction, trigger?: HTMLElement];
}>();
const session = props.session,
  filters = session.filters;
const table = ref<TableInstance>();
const heading = ref<HTMLElement>();
const business = computed(() =>
  props.tasks.filter((task) => !task.managed && !!task.appId)
);
const stages = [
  { key: "all", label: "全部" },
  { key: "pre_review", label: "待审核" },
  { key: "running", label: "处理中" },
  { key: "terminal", label: "已结束" }
];
const rangeError = computed(
  () => filters.from && filters.to && filters.from > filters.to
);
const filtered = computed(() =>
  business.value
    .filter(
      (task) =>
        !rangeError.value &&
        (!filters.query.trim() ||
          ((task.name ?? "") + " " + task.taskId)
            .toLowerCase()
            .includes(filters.query.trim().toLowerCase())) &&
        (filters.app === "all" || task.appId === filters.app) &&
        (filters.country === "all" || task.country === filters.country) &&
        (filters.state === "all" || taskStage(task) === filters.state) &&
        (!filters.from ||
          task.createdAtMillis >= new Date(filters.from + "T00:00:00").getTime()) &&
        (!filters.to ||
          task.createdAtMillis <= new Date(filters.to + "T23:59:59.999").getTime())
    )
    .sort(
      (a, b) =>
        b.createdAtMillis - a.createdAtMillis || a.taskId.localeCompare(b.taskId)
    )
);
function rememberScroll({
  scrollTop,
  scrollLeft
}: {
  scrollTop: number;
  scrollLeft: number;
}) {
  session.listTableScrollTop = scrollTop;
  session.listTableScrollLeft = scrollLeft;
}
function resetScroll() {
  session.listTableScrollTop = 0;
  session.listTableScrollLeft = 0;
  table.value?.setScrollTop(0);
  table.value?.setScrollLeft(0);
}
watch(
  () => [
    filters.query,
    filters.app,
    filters.country,
    filters.state,
    filters.from,
    filters.to
  ],
  resetScroll
);
watch(
  () => props.busy,
  async (busy) => {
    if (busy) return;
    await nextTick();
    table.value?.setScrollTop(session.listTableScrollTop);
    table.value?.setScrollLeft(session.listTableScrollLeft);
  },
  { immediate: true }
);
function reset() {
  Object.assign(filters, {
    query: "",
    app: "all",
    country: "all",
    state: "all",
    from: "",
    to: ""
  });
}
function open(task: CheckTask, event: MouseEvent) {
  emit("action", task, "preview", event.currentTarget as HTMLElement);
}
function revealTask(id: string) {
  if (!filtered.value.some((task) => task.taskId === id)) return false;
  const target = document.getElementById("check-task-" + id);
  if (!target) return false;
  table.value?.setScrollTop(target.closest("tr")?.offsetTop ?? 0);
  target.focus({ preventScroll: true });
  return true;
}
function focusHeading() {
  heading.value?.focus({ preventScroll: true });
}
defineExpose({ revealTask, focusHeading });
</script>
<template>
  <section class="checks-list-page">
    <header class="checks-heading">
      <h1 ref="heading" tabindex="-1">应用注册查询</h1>
      <div class="checks-list-controls">
        <div class="checks-actions">
          <el-button :icon="Refresh" :loading="busy" @click="emit('refresh')"
            >刷新</el-button
          >
          <el-button
            type="primary"
            :icon="Plus"
            :disabled="actionBusy"
            @click="emit('create')"
            >创建任务</el-button
          >
        </div>
        <small class="checks-hint checks-list-read-at" role="status">
          <template v-if="readAt"
            >{{ error ? "上次成功读取" : "最近读取" }}
            <time :datetime="new Date(readAt).toISOString()">{{
              timestamp(readAt)
            }}</time
            >{{ error ? " · 快照已过期" : "" }}
          </template>
          <template v-else>{{ busy ? "正在读取任务…" : "尚未成功读取任务" }}</template>
        </small>
      </div>
    </header>
    <p v-if="error" class="checks-error" role="alert">
      {{ tasks.length ? "当前显示上次快照；状态变更已禁用。" : "" }}{{ error }}
      <button class="checks-link" @click="emit('refresh')">重新读取</button>
    </p>
    <section class="checks-surface">
      <ElTabs
        v-model="filters.state"
        class="checks-state-tabs"
        aria-label="当前载入任务状态"
      >
        <ElTabPane v-for="stage in stages" :key="stage.key" :name="stage.key">
          <template #label
            ><span
              >{{ stage.label }}
              <span class="checks-tab-count">{{
                quantity(
                  stage.key === "all"
                    ? business.length
                    : business.filter((task) => taskStage(task) === stage.key).length
                )
              }}</span></span
            ></template
          >
        </ElTabPane>
      </ElTabs>
      <div class="checks-filters">
        <label class="checks-search"
          ><Search /><input
            v-model="filters.query"
            aria-label="搜索查询任务"
            placeholder="任务名称或编号"
        /></label>
        <select v-model="filters.app" aria-label="筛选应用">
          <option value="all">全部应用</option>
          <option v-for="app in catalog.apps" :key="app.appId" :value="app.appId">
            {{ appLabel(app.appId) }}
          </option>
        </select>
        <select v-model="filters.country" aria-label="筛选地区">
          <option value="all">全部地区</option>
          <option v-for="country in catalog.countries" :key="country" :value="country">
            {{ countryLabel(country) }}
          </option>
        </select>
        <button
          class="checks-link"
          :aria-expanded="session.filtersExpanded"
          aria-controls="checks-date-filters"
          @click="session.filtersExpanded = !session.filtersExpanded"
        >
          高级筛选{{ filters.from || filters.to ? " · 已启用" : "" }}
        </button>
        <button class="checks-link" @click="reset">重置筛选</button>
      </div>
      <div
        v-if="session.filtersExpanded"
        id="checks-date-filters"
        class="checks-advanced-filters"
      >
        <label class="checks-date"
          >创建时间<input v-model="filters.from" type="date" aria-label="创建开始日期"
        /></label>
        <label class="checks-date"
          >至<input v-model="filters.to" type="date" aria-label="创建结束日期"
        /></label>
      </div>
      <p v-if="rangeError" class="checks-error checks-inset" role="alert">
        开始日期不能晚于结束日期。
      </p>
      <p class="checks-table-note" role="status">
        当前载入 {{ quantity(business.length) }} 个 · 匹配
        {{ quantity(filtered.length) }} 个 · 仅筛选本次载入，最多 100 条{{
          truncated ? " · 列表未完整加载" : ""
        }}
      </p>
      <ElTable
        ref="table"
        :data="filtered"
        row-key="taskId"
        class="checks-task-table"
        aria-label="当前载入任务"
        :max-height="
          session.filtersExpanded ? 'calc(100vh - 440px)' : 'calc(100vh - 390px)'
        "
        :scrollbar-tabindex="0"
        :row-class-name="
          ({ row }: { row: CheckTask }) =>
            row.taskId === highlightedTaskId ? 'checks-created-row' : ''
        "
        @scroll="rememberScroll"
      >
        <ElTableColumn label="任务名称" min-width="240">
          <template #default="{ row }">
            <button
              :id="'check-task-' + row.taskId"
              class="checks-task-link"
              :title="row.name || row.taskId"
              @click="open(row, $event)"
            >
              {{ row.name || row.taskId }}
            </button>
            <small class="checks-task-id">{{ row.taskId }}</small>
          </template>
        </ElTableColumn>
        <ElTableColumn label="应用 / 地区" width="120">
          <template #default="{ row }"
            >{{ appLabel(row.appId)
            }}<small>{{ countryLabel(row.country) }}</small></template
          >
        </ElTableColumn>
        <ElTableColumn label="号码数量" width="110" align="right">
          <template #default="{ row }"
            ><span class="checks-number">{{ quantity(row.totalCount) }}</span></template
          >
        </ElTableColumn>
        <ElTableColumn label="处理进度" min-width="170">
          <template #default="{ row }"
            ><span v-if="row.state === 'pre_review'" class="checks-hint"
              >审核后开始</span
            ><TaskProgress v-else :task="row"
          /></template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="105">
          <template #default="{ row }"
            ><TaskStatus :task="row" :preview="previews[row.taskId]"
          /></template>
        </ElTableColumn>
        <ElTableColumn label="创建时间" width="165">
          <template #default="{ row }"
            ><span class="checks-date-cell">{{
              timestamp(row.createdAtMillis)
            }}</span></template
          >
        </ElTableColumn>
        <ElTableColumn label="操作" width="105" fixed="right" align="right">
          <template #default="{ row }"
            ><TaskActions
              :task="row"
              :source="source"
              :blocked="actionBusy || submissionBusy || busy || !!error"
              @action="(action, trigger) => emit('action', row, action, trigger)"
          /></template>
        </ElTableColumn>
        <template #empty>
          <div class="checks-empty">
            <h3>
              {{
                busy
                  ? "正在读取任务"
                  : error
                    ? "暂时无法读取任务"
                    : business.length
                      ? "当前载入范围内没有匹配任务"
                      : "暂无查询任务"
              }}
            </h3>
            <p>
              {{
                business.length
                  ? "调整筛选条件查看已载入任务。"
                  : "选择应用并导入号码，创建第一批查询。"
              }}
            </p>
          </div>
        </template>
      </ElTable>
    </section>
  </section>
</template>
