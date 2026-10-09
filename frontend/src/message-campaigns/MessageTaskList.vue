<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { Plus, Refresh } from "@element-plus/icons-vue";
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
import MessageTaskActions from "./MessageTaskActions.vue";
import {
  senderRange,
  taskStateLabel,
  type MessageTask,
  type MessageTaskSource
} from "./task-source";
import {
  applicationLabel,
  taskStage,
  timestamp,
  quantity,
  type MessageSession,
  type MessageAction
} from "./workbench";
const props = defineProps<{
  tasks: MessageTask[];
  source: MessageTaskSource;
  session: MessageSession;
  busy: boolean;
  blocked: boolean;
  error: string;
  readAt: number;
  truncated: boolean;
  highlighted?: string;
}>();
const emit = defineEmits<{
  create: [];
  refresh: [];
  action: [task: MessageTask, action: MessageAction, trigger?: HTMLElement];
}>();
const session = props.session;
const filters = session.filters;
const heading = ref<HTMLElement>(),
  table = ref<TableInstance>();
const business = computed(() => props.tasks.filter((task) => !task.managed));
const stages = [
  { key: "all", label: "全部" },
  { key: "pre_review", label: "待审核" },
  { key: "running", label: "发送中" },
  { key: "terminal", label: "调度已结束" }
];
const apps = computed(() => {
  const values = new Map(
    props.source.applications.map((app) => [app.workerGroupId, app.label])
  );
  for (const task of business.value)
    if (task.workerGroupId && !values.has(task.workerGroupId))
      values.set(task.workerGroupId, task.workerGroupId);
  return [...values].map(([id, label]) => ({ id, label }));
});
const rangeError = computed(
  () => filters.from && filters.to && filters.from > filters.to
);
const filtered = computed(() =>
  business.value
    .filter((task) => {
      const query = filters.query.trim().toLocaleLowerCase();
      return (
        !rangeError.value &&
        (!query ||
          `${task.name ?? ""} ${task.taskId}`.toLocaleLowerCase().includes(query)) &&
        (filters.app === "all" || task.workerGroupId === filters.app) &&
        (filters.country === "all" || task.recipientCountry === filters.country) &&
        (filters.state === "all" || taskStage(task) === filters.state) &&
        (filters.sender === "all" ||
          (filters.sender === "ANY"
            ? task.senderCountry === null
            : task.senderCountry === filters.sender)) &&
        (!filters.from ||
          task.createdAtMillis >= new Date(filters.from + "T00:00:00").getTime()) &&
        (!filters.to ||
          task.createdAtMillis <= new Date(filters.to + "T23:59:59.999").getTime())
      );
    })
    .sort(
      (a, b) =>
        b.createdAtMillis - a.createdAtMillis || a.taskId.localeCompare(b.taskId)
    )
);
function reset() {
  Object.assign(filters, {
    query: "",
    app: "all",
    country: "all",
    state: "all",
    sender: "all",
    from: "",
    to: ""
  });
}
watch(
  () => [
    filters.query,
    filters.app,
    filters.country,
    filters.state,
    filters.sender,
    filters.from,
    filters.to
  ],
  () => {
    session.scrollTop = 0;
    session.scrollLeft = 0;
    table.value?.setScrollTop(0);
    table.value?.setScrollLeft(0);
  }
);
watch(
  () => props.busy,
  async (busy) => {
    if (!busy) {
      await nextTick();
      table.value?.setScrollTop(session.scrollTop);
      table.value?.setScrollLeft(session.scrollLeft);
    }
  }
);
function revealTask(id: string) {
  if (!filtered.value.some((task) => task.taskId === id)) return false;
  const node = document.getElementById("message-task-" + id);
  if (!node) return false;
  table.value?.setScrollTop(node.closest("tr")?.offsetTop ?? 0);
  node.focus({ preventScroll: true });
  return true;
}
defineExpose({
  revealTask,
  focusHeading: () => heading.value?.focus({ preventScroll: true })
});
</script>
<template>
  <section>
    <header class="message-heading">
      <h1 ref="heading" tabindex="-1">消息任务</h1>
      <div class="message-heading-controls">
        <div class="message-controls">
          <el-button :icon="Refresh" :loading="busy" @click="emit('refresh')"
            >刷新</el-button
          ><el-button
            type="primary"
            :icon="Plus"
            :disabled="blocked || !source.applications.length"
            @click="emit('create')"
            >创建任务</el-button
          >
        </div>
        <small class="message-hint"
          >{{ error ? "上次成功读取" : "最近读取" }} {{ timestamp(readAt)
          }}{{ error && readAt ? " · 快照已过期" : "" }}</small
        >
      </div>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <el-alert
      v-if="!source.applications.length"
      title="应用配置不可用，无法确认唯一 WorkerGroup。创建暂不可用，仍可读取已有任务。"
      type="warning"
      :closable="false"
    />
    <section class="message-surface" :aria-busy="busy" aria-label="Messages 任务列表">
      <ElTabs v-model="filters.state" class="message-tabs"
        ><ElTabPane
          v-for="stage in stages"
          :key="stage.key"
          :name="stage.key"
          :label="`${stage.label} ${business.filter((task) => stage.key === 'all' || taskStage(task) === stage.key).length}`"
      /></ElTabs>
      <div class="message-filters">
        <el-input
          v-model="filters.query"
          aria-label="搜索任务"
          placeholder="搜索名称或编号"
          clearable
        /><el-select v-model="filters.app" aria-label="筛选应用"
          ><el-option label="全部应用" value="all" /><el-option
            v-for="app in apps"
            :key="app.id"
            :label="app.label"
            :value="app.id" /></el-select
        ><el-select v-model="filters.country" aria-label="筛选收件国家"
          ><el-option label="全部收件国家" value="all" /><el-option
            v-for="country in ['CN', 'US', 'GB']"
            :key="country"
            :value="country"
            :label="country" /></el-select
        ><el-button @click="reset">重置</el-button
        ><el-button
          text
          :aria-expanded="filters.advanced"
          @click="filters.advanced = !filters.advanced"
          >高级筛选{{
            filters.sender !== "all" || filters.from || filters.to ? " · 已启用" : ""
          }}</el-button
        >
      </div>
      <div v-if="filters.advanced" class="message-filters message-advanced-filters">
        <el-select v-model="filters.sender" aria-label="筛选发送国家"
          ><el-option label="全部发送国家" value="all" /><el-option
            label="不限国家"
            value="ANY" /><el-option
            v-for="country in ['CN', 'US', 'GB']"
            :key="country"
            :label="country"
            :value="country" /></el-select
        ><label
          >创建日期
          <input v-model="filters.from" type="date" aria-label="创建日期起始" /></label
        ><span>至</span
        ><input v-model="filters.to" type="date" aria-label="创建日期结束" /><span
          v-if="rangeError"
          role="alert"
          class="message-error"
          >起始日期不能晚于结束日期</span
        >
      </div>
      <p class="message-range">
        当前载入 {{ business.length }} 个业务任务 · 匹配 {{ filtered.length }} 个 ·
        最多读取 100 条{{ truncated ? " · 返回数据已截断" : "" }}
      </p>
      <ElTable
        ref="table"
        :data="filtered"
        row-key="taskId"
        max-height="560"
        :row-class-name="
          ({ row }: { row: MessageTask }) =>
            row.taskId === highlighted ? 'message-highlight' : ''
        "
        @scroll="
          ({ scrollTop, scrollLeft }: { scrollTop: number; scrollLeft: number }) => {
            session.scrollTop = scrollTop;
            session.scrollLeft = scrollLeft;
          }
        "
      >
        <ElTableColumn label="任务" min-width="205"
          ><template #default="{ row }"
            ><button
              :id="'message-task-' + row.taskId"
              class="message-link message-task-name"
              data-testid="message-task-row"
              @click="
                emit('action', row, 'preview', $event.currentTarget as HTMLElement)
              "
            >
              {{ row.name || row.taskId }}</button
            ><small class="message-id" :title="row.taskId">{{
              row.taskId
            }}</small></template
          ></ElTableColumn
        >
        <ElTableColumn label="应用／国家" min-width="135"
          ><template #default="{ row }"
            ><span>{{ applicationLabel(row, source.applications) }}</span
            ><small class="message-id"
              >{{ senderRange(row.senderCountry) }} →
              {{ row.recipientCountry ?? "不可用" }}</small
            ></template
          ></ElTableColumn
        >
        <ElTableColumn label="收件人数" width="100"
          ><template #default="{ row }">{{
            quantity(row.sendTotal)
          }}</template></ElTableColumn
        >
        <ElTableColumn label="已发送／失败" width="120"
          ><template #default="{ row }"
            >{{ quantity(row.sentCount) }} / {{ quantity(row.failedCount) }}</template
          ></ElTableColumn
        >
        <ElTableColumn label="已送达" width="90"
          ><template #default="{ row }">{{
            quantity(row.deliveredCount)
          }}</template></ElTableColumn
        >
        <ElTableColumn label="调度状态" width="115"
          ><template #default="{ row }"
            ><span class="message-state" :data-state="taskStage(row)">{{
              taskStateLabel(row.state)
            }}</span></template
          ></ElTableColumn
        >
        <ElTableColumn label="创建时间" width="148"
          ><template #default="{ row }">{{
            timestamp(row.createdAtMillis)
          }}</template></ElTableColumn
        >
        <ElTableColumn label="操作" width="94" fixed="right"
          ><template #default="{ row }"
            ><MessageTaskActions
              :task="row"
              :blocked="blocked || busy || !!error"
              @action="
                (action, trigger) => emit('action', row, action, trigger)
              " /></template
        ></ElTableColumn>
        <template #empty>{{
          busy
            ? "正在读取任务…"
            : business.length
              ? "当前载入任务中没有匹配项"
              : error
                ? "任务读取失败，请重试"
                : "还没有消息任务"
        }}</template>
      </ElTable>
    </section>
  </section>
</template>
