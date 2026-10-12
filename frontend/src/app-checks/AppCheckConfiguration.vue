<script setup lang="ts">
import { computed, markRaw, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { ElDialog } from "element-plus";
import "element-plus/es/components/dialog/style/css";
import { Refresh } from "@element-plus/icons-vue";
import type { Catalog } from "./model";
import {
  applicationConfiguration,
  scenarioConfiguration,
  type ConfigurationReadState
} from "./configuration";
import {
  settingsSchema,
  settingsIdentity,
  type ConfigurationSession
} from "./settings";
import {
  AppCheckConfigurationUnconfirmed,
  AppCheckApiError,
  type AppCheckTaskSource
} from "./task-source";
import { timestamp } from "./workbench";
import ConfigurationRows from "./ConfigurationRows.vue";
import SimulationSummary from "./SimulationSummary.vue";
import SimulationEditor from "./SimulationEditor.vue";
const props = defineProps<{
  catalog: Catalog;
  appId: string;
  source: AppCheckTaskSource;
  session: ConfigurationSession;
  read: ConfigurationReadState;
  returnToCreate: boolean;
}>();
const emit = defineEmits<{ select: [appId: string]; refresh: []; returnToDraft: [] }>();
const state = props.session;
const busy = ref(false),
  saving = computed(() => state.saving),
  showEffective = ref(false),
  addOpen = ref(false),
  addError = ref("");
const heading = ref<HTMLElement>(),
  newAppInput = ref<HTMLInputElement>();
const proposed = reactive({
  appId: "",
  label: "",
  workerGroupId: "",
  windowMillis: 60000,
  maxCount: 10
});
const settings = computed(() =>
  showEffective.value
    ? state.snapshot?.effective.configuration
    : (state.draft ?? state.snapshot?.saved.configuration)
);
const apps = computed(() => settings.value?.applications ?? props.catalog.apps);
const selected = computed(() =>
  settings.value?.applications.find((app) => app.appId === props.appId)
);
const limit = computed(() =>
  selected.value ? settings.value?.poolGroups[selected.value.workerGroupId] : undefined
);
const sourceLabel = computed(() =>
  props.source.mode === "mock"
    ? "mock"
    : showEffective.value
      ? "effective"
      : state.draft
        ? "draft"
        : "saved"
);
const configuration = computed(() =>
  applicationConfiguration(
    props.catalog,
    props.appId,
    props.source.mode,
    settings.value,
    sourceLabel.value
  )
);
const common = computed(() => scenarioConfiguration(props.catalog, props.source.mode));
const canSave = computed(
  () =>
    !!state.draft &&
    !busy.value &&
    !saving.value &&
    !state.uncertain &&
    !state.stale &&
    state.baseRevision === state.snapshot?.saved.revision
);
const windowChange = computed(
  () =>
    state.snapshot &&
    settings.value &&
    Object.entries(state.snapshot.effective.configuration.poolGroups).some(
      ([group, current]) =>
        settings.value!.poolGroups[group]?.windowMillis !== current.windowMillis
    )
);
let generation = 0,
  disposed = false;

async function load(refreshCatalog = false) {
  if (busy.value || saving.value) return;
  const token = ++generation;
  busy.value = true;
  if (refreshCatalog) emit("refresh");
  try {
    const value = await props.source.loadConfiguration();
    if (disposed || generation !== token) return;
    state.snapshot = markRaw(value);
    state.readAt = Date.now();
    state.stale = false;
    state.error = "";
    if (state.uncertain) {
      if (
        state.draft &&
        settingsIdentity(state.draft) === settingsIdentity(value.saved.configuration)
      ) {
        state.draft = undefined;
        state.notice = "已核对：当前保存配置与本次提交一致。";
      } else state.notice = "已读取当前保存配置，编辑内容保持不变，请核对修订后处理。";
      state.uncertain = false;
    }
  } catch (failure) {
    if (!disposed && generation === token) {
      state.error = failure instanceof Error ? failure.message : "配置读取失败";
      state.stale = true;
    }
  } finally {
    if (generation === token) busy.value = false;
  }
}
function edit() {
  if (!state.snapshot || busy.value || saving.value || state.stale) return;
  state.draft = settingsSchema.parse(state.snapshot.saved.configuration);
  state.baseRevision = state.snapshot.saved.revision;
  showEffective.value = false;
  state.error = "";
  state.notice = "";
}
function cancel() {
  if (busy.value || saving.value) return;
  state.draft = undefined;
  state.baseRevision = undefined;
  state.uncertain = false;
  state.error = "";
  state.notice = "";
}
async function save() {
  if (!canSave.value || !state.draft || !state.baseRevision) return;
  const validated = settingsSchema.safeParse(state.draft);
  if (!validated.success) {
    state.error =
      "配置无效：请检查应用名称、正整数窗口参数，以及完整互斥的模拟区间和延迟范围。";
    return;
  }
  const token = ++generation;
  busy.value = true;
  state.saving = true;
  state.error = "";
  state.notice = "";
  try {
    const value = await props.source.saveConfiguration(
      state.baseRevision,
      validated.data
    );
    if (disposed || generation !== token) return;
    state.snapshot = markRaw(value);
    state.readAt = Date.now();
    state.stale = false;
    state.uncertain = false;
    state.draft = undefined;
    state.notice = value.restartRequired
      ? "配置已保存，待手动重启生效。当前运行保持原配置。"
      : "配置已保存，与当前生效值一致。";
  } catch (failure) {
    if (!disposed && generation === token) {
      state.error =
        failure instanceof Error ? failure.message : "保存失败，编辑内容已保留";
      state.uncertain = failure instanceof AppCheckConfigurationUnconfirmed;
      if (failure instanceof AppCheckApiError && failure.status === 409)
        state.stale = true;
    }
  } finally {
    state.saving = false;
    if (generation === token) busy.value = false;
  }
}
function openAdd() {
  if (!state.draft || busy.value || saving.value || state.uncertain) return;
  const first = Object.values(state.draft.poolGroups)[0];
  Object.assign(proposed, {
    appId: "",
    label: "",
    workerGroupId: "",
    windowMillis: first.windowMillis,
    maxCount: first.maxCount
  });
  addError.value = "";
  addOpen.value = true;
}
function addApplication() {
  if (!state.draft) return;
  const { appId, label, workerGroupId, windowMillis, maxCount } = proposed;
  const value = settingsSchema.safeParse({
    ...state.draft,
    applications: [...state.draft.applications, { appId, label, workerGroupId }],
    poolGroups: {
      ...state.draft.poolGroups,
      [workerGroupId]: { windowMillis, maxCount }
    }
  });
  if (!value.success) {
    addError.value = "请填写完整应用信息、唯一的 App ID / Group 和正整数窗口参数。";
    return;
  }
  state.draft = value.data;
  addOpen.value = false;
  emit("select", appId);
}
async function simulateRestart() {
  if (!props.source.simulateConfigurationRestart || busy.value || state.draft) return;
  busy.value = true;
  try {
    await props.source.simulateConfigurationRestart();
  } finally {
    busy.value = false;
  }
  if (!disposed) await load(true);
}
onMounted(() => {
  if (!state.snapshot) void load();
});
onBeforeUnmount(() => {
  disposed = true;
  generation++;
  if (saving.value) {
    state.uncertain = true;
    state.error = "离开页面时配置保存尚未确认，请重新读取核对。";
  }
});
defineExpose({ focusHeading: () => heading.value?.focus({ preventScroll: true }) });
</script>
<template>
  <section class="checks-configuration-page" aria-label="应用与运行策略">
    <header class="checks-heading">
      <h1 ref="heading" tabindex="-1">应用与运行策略</h1>
      <div class="checks-list-controls">
        <div class="checks-actions">
          <el-button v-if="returnToCreate" @click="emit('returnToDraft')"
            >返回创建草稿</el-button
          >
          <el-button
            :icon="Refresh"
            :loading="busy && !saving"
            :disabled="saving"
            @click="load(true)"
            >刷新配置</el-button
          >
          <el-button
            v-if="!state.draft"
            type="primary"
            :disabled="!state.snapshot || busy || saving || state.stale"
            @click="edit"
            >编辑配置</el-button
          >
          <template v-else>
            <el-button :disabled="busy || saving" @click="cancel">取消编辑</el-button>
            <el-button
              type="primary"
              :disabled="!canSave"
              :loading="saving"
              @click="save"
              >保存配置</el-button
            >
          </template>
        </div>
        <small class="checks-hint">{{
          state.readAt
            ? "最近成功读取 " + timestamp(state.readAt)
            : busy
              ? "正在读取配置…"
              : "配置尚未读取"
        }}</small>
      </div>
    </header>
    <p v-if="state.error" class="checks-error" role="alert">
      {{ state.error
      }}{{ state.stale && state.snapshot ? " 已保留上次快照，配置可能已过期。" : "" }}
    </p>
    <p v-if="state.notice" class="checks-notice" role="status">{{ state.notice }}</p>
    <p v-if="state.draft" class="checks-hint" role="status">
      编辑内容仅在当前会话保留；刷新和切换应用不会覆盖。编辑基于修订
      {{ state.baseRevision }}。
    </p>
    <p
      v-if="
        state.draft &&
        state.snapshot &&
        state.baseRevision !== state.snapshot.saved.revision
      "
      class="checks-error"
    >
      已保存配置修订已变化，当前编辑未覆盖。请核对后取消编辑，再基于最新配置修改。
    </p>
    <div class="checks-configuration-toolbar">
      <p class="checks-hint">
        {{
          source.mode === "mock" ? "Mock 演示数据 · 仅当前会话" : "App Checks 场景配置"
        }}
        · 保存后手动重启生效
      </p>
      <div v-if="state.snapshot" class="checks-segments" aria-label="配置版本">
        <button
          :aria-pressed="!showEffective"
          :disabled="!!state.draft"
          @click="showEffective = false"
        >
          已保存配置 · {{ state.snapshot.saved.revision }}
        </button>
        <button
          :aria-pressed="showEffective"
          :disabled="!!state.draft"
          @click="showEffective = true"
        >
          当前生效值 · {{ state.snapshot.effective.revision }}
        </button>
      </div>
    </div>
    <p
      v-if="state.snapshot?.restartRequired"
      class="checks-configuration-gap"
      role="status"
    >
      有已保存配置待重启生效。新增应用还需宿主准备 Group、事件、Matching 资源及 Worker
      执行能力，重启时校验依赖。
    </p>
    <p v-if="windowChange || state.snapshot?.newScopeRequired" class="checks-error">
      既有 Group 的窗口长度已改变，需使用新的 Redis scope
      后重启；不会自动迁移或清理运行数据。
    </p>
    <section
      class="checks-surface checks-configuration-sections"
      aria-label="App Checks 通用配置"
    >
      <h2>App Checks 通用配置</h2>
      <p class="checks-hint">所有应用共用。支持国家和导入限制由服务提供，只读展示。</p>
      <ConfigurationRows :section="common" />
      <p v-if="read.error" class="checks-error">
        Catalog 读取失败，国家与导入限制保留原快照。
      </p>
      <details class="checks-configuration-simulation" :open="!!state.draft">
        <summary>共用模拟默认参数</summary>
        <p class="checks-hint">
          新草稿复制当前生效默认值。保存或刷新配置不会替换已有草稿和任务的保存值。
        </p>
        <fieldset
          v-if="state.draft"
          class="checks-config-fieldset"
          :disabled="busy || saving || state.uncertain"
        >
          <SimulationEditor v-model="state.draft.common.simulationDefault" />
        </fieldset>
        <SimulationSummary
          v-else
          :value="settings?.common.simulationDefault ?? catalog.simulationExample"
          :source="settings ? sourceLabel : source.mode === 'mock' ? 'mock' : 'catalog'"
          scope="场景"
        />
      </details>
    </section>
    <section
      class="checks-surface checks-configuration-sections"
      aria-label="App 运行配置"
    >
      <div class="checks-config-heading">
        <h2>App 运行配置</h2>
        <el-button
          v-if="state.draft"
          :disabled="busy || saving || state.uncertain"
          @click="openAdd"
          >新增应用</el-button
        >
      </div>
      <div class="checks-configuration-selector">
        <label class="checks-field"
          >应用<select
            :value="appId"
            aria-label="配置应用"
            @change="emit('select', ($event.target as HTMLSelectElement).value)"
          >
            <option v-if="!configuration" :value="appId" disabled>
              请选择可用应用
            </option>
            <option v-for="app in apps" :key="app.appId" :value="app.appId">
              {{ app.label }}
            </option>
          </select></label
        >
        <span
          v-if="
            selected &&
            !state.snapshot?.effective.configuration.applications.some(
              (app) => app.appId === selected!.appId
            )
          "
          class="checks-hint"
          >尚未生效 · 创建任务暂不可选</span
        >
      </div>
      <p v-if="!configuration" role="alert" class="checks-configuration-gap">
        应用「{{ appId }}」不在当前配置中，请重新选择。
      </p>
      <div v-else class="checks-application-sections">
        <section>
          <ConfigurationRows :section="configuration.binding" />
          <fieldset
            v-if="state.draft && selected && limit"
            class="checks-config-fieldset"
            :disabled="busy || saving || state.uncertain"
          >
            <label class="checks-field"
              >应用显示名称<input
                v-model="selected.label"
                aria-label="应用显示名称"
                maxlength="128"
            /></label>
            <div class="checks-two-columns">
              <label class="checks-field"
                >观察窗口（ms）<input
                  v-model.number="limit.windowMillis"
                  aria-label="观察窗口"
                  type="number"
                  min="1"
                  step="1"
              /></label>
              <label class="checks-field"
                >单 Worker 观察阈值<input
                  v-model.number="limit.maxCount"
                  aria-label="观察阈值"
                  type="number"
                  min="1"
                  step="1"
              /></label>
            </div>
            <p class="checks-hint">已有应用的 App ID 和 Group 绑定固定。</p>
          </fieldset>
        </section>
        <section>
          <h3>关联 Pool 规则</h3>
          <ConfigurationRows :section="configuration.pool" />
          <p class="checks-hint">
            对 Group 内单个 Worker 的观察筛选，在入池时判断资格；不是 Project
            配额、Group 总额度或严格吞吐保证。
          </p>
        </section>
      </div>
    </section>
    <details v-if="source.mode === 'mock'" class="checks-auxiliary">
      <summary>Mock 演示控制</summary>
      <p class="checks-hint">
        只模拟本次会话的配置重新加载，不重启任何服务，不改变已有任务保存值。
      </p>
      <el-button
        :disabled="busy || !!state.draft || !state.snapshot?.restartRequired"
        @click="simulateRestart"
        >模拟重启生效</el-button
      >
    </details>
    <ElDialog
      v-model="addOpen"
      title="新增应用"
      width="560px"
      @opened="newAppInput?.focus()"
    >
      <p class="checks-hint">
        新增应用随整份配置保存，手动重启后生效。Group 与执行能力仍由宿主提供。
      </p>
      <label class="checks-field"
        >App ID<input
          ref="newAppInput"
          v-model="proposed.appId"
          aria-label="新应用 ID"
          maxlength="128"
      /></label>
      <label class="checks-field"
        >显示名称<input
          v-model="proposed.label"
          aria-label="新应用名称"
          maxlength="128"
      /></label>
      <label class="checks-field"
        >WorkerGroup<input
          v-model="proposed.workerGroupId"
          aria-label="新应用 Group"
          maxlength="128"
      /></label>
      <div class="checks-two-columns">
        <label class="checks-field"
          >观察窗口（ms）<input
            v-model.number="proposed.windowMillis"
            aria-label="新应用观察窗口"
            type="number"
            min="1"
            step="1"
        /></label>
        <label class="checks-field"
          >单 Worker 观察阈值<input
            v-model.number="proposed.maxCount"
            aria-label="新应用观察阈值"
            type="number"
            min="1"
            step="1"
        /></label>
      </div>
      <p class="checks-hint">初始窗口填写值参考当前配置，可在保存前调整。</p>
      <p v-if="addError" class="checks-error" role="alert">{{ addError }}</p>
      <template #footer
        ><el-button @click="addOpen = false">取消</el-button
        ><el-button type="primary" @click="addApplication"
          >加入编辑配置</el-button
        ></template
      >
    </ElDialog>
  </section>
</template>
