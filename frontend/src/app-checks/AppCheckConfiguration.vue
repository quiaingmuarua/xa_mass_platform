<script setup lang="ts">
import { computed, markRaw, onBeforeUnmount, onMounted, ref } from "vue";
import { Refresh } from "@element-plus/icons-vue";
import { TaskApiError } from "@/task-caller";
import { consoleIdentity } from "@/console-identity";
import type { Catalog } from "./model";
import { scenarioConfiguration, type ConfigurationReadState } from "./configuration";
import {
  parseConfigurationDocument,
  formatConfigurationDocument,
  settingsIdentity,
  type ConfigurationSession
} from "./settings";
import {
  AppCheckConfigurationUnconfirmed,
  type AppCheckTaskSource
} from "./task-source";
import { timestamp } from "./workbench";
import ConfigurationRows from "./ConfigurationRows.vue";

const props = defineProps<{
  catalog: Catalog;
  source: AppCheckTaskSource;
  session: ConfigurationSession;
  read: ConfigurationReadState;
  returnToCreate: boolean;
}>();
const emit = defineEmits<{ refresh: []; returnToDraft: [] }>();
const identity = consoleIdentity.requireCurrent();
const state = props.session;
const busy = ref(false),
  heading = ref<HTMLElement>(),
  editor = ref<HTMLTextAreaElement>();
const editing = computed(() => state.draft !== undefined);
const display = computed(
  () =>
    state.draft ??
    formatConfigurationDocument(state.snapshot?.saved.configuration ?? {})
);
const common = computed(() => scenarioConfiguration(props.catalog, props.source.mode));
const canSave = computed(
  () =>
    editing.value &&
    !busy.value &&
    !state.saving &&
    !state.uncertain &&
    !state.stale &&
    state.baseFingerprint === state.snapshot?.saved.fingerprint
);
let generation = 0,
  disposed = false;
async function load(refreshCatalog = false) {
  if (busy.value || state.saving) return;
  const token = ++generation;
  busy.value = true;
  if (refreshCatalog) emit("refresh");
  try {
    const value = await props.source.loadConfiguration(identity);
    if (disposed || token !== generation) return;
    state.snapshot = markRaw(value);
    state.readAt = Date.now();
    state.stale = false;
    state.error = "";
    if (!state.uncertain) state.notice = "";
    if (state.uncertain) {
      const submitted = state.submittedText;
      if (
        submitted !== undefined &&
        settingsIdentity(parseConfigurationDocument(submitted)) ===
          settingsIdentity(value.saved.configuration)
      ) {
        state.draft = undefined;
        state.baseFingerprint = undefined;
        state.notice = "已核对：当前保存配置与本次提交一致。";
      } else
        state.notice = "已读取当前保存配置，原提交文本与指纹已保留；请核对后处理。";
      state.uncertain = false;
    }
  } catch (failure) {
    if (!disposed && token === generation) {
      state.error = failure instanceof Error ? failure.message : "配置读取失败";
      state.stale = true;
    }
  } finally {
    if (token === generation) busy.value = false;
  }
}
function edit() {
  if (!state.snapshot || busy.value || state.saving || state.stale) return;
  state.draft = formatConfigurationDocument(state.snapshot.saved.configuration);
  state.baseFingerprint = state.snapshot.saved.fingerprint;
  state.submittedText = undefined;
  state.error = "";
  state.notice = "";
}
function cancel() {
  if (busy.value || state.saving) return;
  state.draft = undefined;
  state.baseFingerprint = undefined;
  state.submittedText = undefined;
  state.uncertain = false;
  state.error = "";
  state.notice = "";
}
function format() {
  if (!editing.value || busy.value || state.saving || state.uncertain) return;
  try {
    state.draft = formatConfigurationDocument(parseConfigurationDocument(state.draft!));
    state.error = "";
  } catch (failure) {
    state.error = failure instanceof Error ? failure.message : "JSON 无法解析";
    editor.value?.focus();
  }
}
async function save() {
  if (!canSave.value || state.draft === undefined || !state.baseFingerprint) return;
  let document: Record<string, unknown>;
  try {
    document = parseConfigurationDocument(state.draft);
  } catch (failure) {
    state.error = failure instanceof Error ? failure.message : "JSON 无法解析";
    editor.value?.focus();
    return;
  }
  const token = ++generation;
  state.submittedText = state.draft;
  busy.value = true;
  state.saving = true;
  state.error = "";
  state.notice = "";
  try {
    const value = await props.source.saveConfiguration(
      identity,
      state.baseFingerprint,
      document
    );
    if (disposed || token !== generation) return;
    state.snapshot = markRaw(value);
    state.readAt = Date.now();
    state.stale = false;
    state.uncertain = false;
    state.draft = undefined;
    state.baseFingerprint = undefined;
    state.submittedText = undefined;
    state.notice = value.restartRequired
      ? "配置已保存，待手动重启生效。"
      : "配置已保存，与当前生效值一致。";
  } catch (failure) {
    if (!disposed && token === generation) {
      state.error =
        failure instanceof Error ? failure.message : "保存失败，编辑内容已保留";
      state.uncertain = failure instanceof AppCheckConfigurationUnconfirmed;
      if (failure instanceof TaskApiError && failure.status === 409) state.stale = true;
    }
  } finally {
    state.saving = false;
    if (token === generation) busy.value = false;
  }
}
async function simulateRestart() {
  if (
    !props.source.simulateConfigurationRestart ||
    busy.value ||
    editing.value ||
    state.saving
  )
    return;
  busy.value = true;
  try {
    await props.source.simulateConfigurationRestart(identity);
  } catch (failure) {
    state.error = failure instanceof Error ? failure.message : "模拟重启失败";
    return;
  } finally {
    busy.value = false;
  }
  if (!disposed) {
    await load(true);
    if (!state.error) state.notice = "已模拟重启，配置已生效；原有任务保存值保持不变。";
  }
}
onMounted(() => {
  if (!state.snapshot) void load();
});
onBeforeUnmount(() => {
  disposed = true;
  generation++;
  if (state.saving) {
    state.uncertain = true;
    state.error = "离开页面时配置保存尚未确认，请重新读取核对。";
  }
});
defineExpose({ focusHeading: () => heading.value?.focus({ preventScroll: true }) });
</script>
<template>
  <section class="checks-configuration-page" aria-label="检查配置">
    <header class="checks-heading">
      <div>
        <h1 ref="heading" tabindex="-1">检查配置</h1>
        <small class="checks-hint"
          >{{
            source.mode === "mock" ? "Mock 演示数据 · 仅当前会话" : "App Checks 配置"
          }}
          · 保存后手动重启生效</small
        >
      </div>
      <div class="checks-list-controls">
        <div class="checks-actions">
          <el-button v-if="returnToCreate" @click="emit('returnToDraft')"
            >返回创建草稿</el-button
          >
          <el-button
            :icon="Refresh"
            :loading="busy && !state.saving"
            :disabled="state.saving"
            @click="load(true)"
            >重新读取</el-button
          >
          <el-button
            v-if="!editing"
            type="primary"
            :disabled="!state.snapshot || busy || state.saving || state.stale"
            @click="edit"
            >编辑配置</el-button
          >
          <template v-else>
            <el-button
              :disabled="busy || state.saving || state.uncertain"
              @click="format"
              >格式化</el-button
            >
            <el-button :disabled="busy || state.saving" @click="cancel"
              >取消编辑</el-button
            >
            <el-button
              type="primary"
              :disabled="!canSave"
              :loading="state.saving"
              @click="save"
              >保存配置</el-button
            >
          </template>
        </div>
        <small class="checks-hint">{{
          state.readAt ? "最近成功读取 " + timestamp(state.readAt) : "配置尚未读取"
        }}</small>
      </div>
    </header>
    <p v-if="state.error" class="checks-error" role="alert">
      {{ state.error
      }}{{ state.stale && state.snapshot ? " 已保留上次快照，配置可能已过期。" : "" }}
    </p>
    <p v-if="state.notice" class="checks-notice" role="status">{{ state.notice }}</p>
    <div v-if="state.snapshot" class="checks-configuration-toolbar">
      <span
        >已保存指纹 {{ state.snapshot.saved.fingerprint.slice(0, 12) }} · 当前生效指纹
        {{ state.snapshot.effective.fingerprint.slice(0, 12) }}</span
      >
      <strong v-if="state.snapshot.restartRequired">待重启生效</strong>
      <span v-else class="checks-hint">已生效</span>
    </div>
    <p v-if="editing" class="checks-hint" role="status">
      编辑基于指纹
      {{
        state.baseFingerprint?.slice(0, 12)
      }}。重新读取和离开返回保留原文本；新增检查项直接加入 checks 对象，以 checkId
      为键。
    </p>
    <p
      v-if="
        editing &&
        state.snapshot &&
        state.baseFingerprint !== state.snapshot.saved.fingerprint
      "
      class="checks-error"
    >
      已保存配置内容已变化，当前编辑未覆盖。请核对后取消编辑，再基于最新配置修改。
    </p>
    <textarea
      v-if="state.snapshot || editing"
      ref="editor"
      class="checks-config-json"
      aria-label="配置 JSON"
      spellcheck="false"
      :value="display"
      :readonly="!editing || busy || state.saving || state.uncertain"
      @input="state.draft = ($event.target as HTMLTextAreaElement).value"
    />
    <p class="checks-hint">
      参数按检查项整份覆盖或采用类型默认值；不递归合并。已有 checkId、检查类型和 Group
      固定。保存校验配置，重启校验宿主资源。
    </p>

    <details v-if="state.snapshot" class="checks-auxiliary">
      <summary>
        查看当前生效配置 · 指纹 {{ state.snapshot.effective.fingerprint.slice(0, 12) }}
      </summary>
      <pre class="checks-json">{{
        formatConfigurationDocument(state.snapshot.effective.configuration)
      }}</pre>
    </details>
    <details class="checks-auxiliary">
      <summary>检查类型与配置参考</summary>
      <p class="checks-hint">
        同协议可绑定不同事件和 Group。事件声明不代表 Handler
        已安装；业务结果关联校验也不替代独立真实性证明。
      </p>
      <ConfigurationRows :section="common" />
      <p v-if="read.error" class="checks-error">
        Catalog 读取失败，容量限制保留原快照。
      </p>
      <section v-for="contract in state.snapshot?.checkTypes" :key="contract.id">
        <h3>{{ contract.id }}</h3>
        <p>{{ contract.description }}</p>
        <pre class="checks-json">{{
          JSON.stringify(
            {
              parameters: contract.parameterExample,
              input: contract.inputExample,
              result: contract.resultExample
            },
            null,
            2
          )
        }}</pre>
      </section>
      <p class="checks-hint">
        assignment-window 是 Group 内单个 Worker
        的观察筛选，不是严格配额。修改既有窗口长度须使用新 scope。
      </p>
    </details>
    <details v-if="source.mode === 'mock'" class="checks-auxiliary">
      <summary>Mock 演示控制</summary>
      <p class="checks-hint">
        只重新加载当前会话配置；已有任务继续使用保存的执行快照。
      </p>
      <el-button
        :disabled="busy || editing || state.saving || !state.snapshot?.restartRequired"
        @click="simulateRestart"
        >模拟重启生效</el-button
      >
    </details>
  </section>
</template>
<style scoped>
.checks-config-json {
  display: block;
  width: 100%;
  min-height: 530px;
  resize: vertical;
  box-sizing: border-box;
  font:
    13px/1.55 ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
  tab-size: 2;
  padding: 16px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
}
.checks-config-json:focus {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}
</style>
