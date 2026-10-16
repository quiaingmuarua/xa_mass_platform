<script setup lang="ts">
import { computed, markRaw, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { ArrowLeft, UploadFilled } from "@element-plus/icons-vue";
import { captureTaskCaller } from "@/console-identity";
import { canUseConsole } from "@/access-policy";
import { consoleIdentity } from "@/console-identity";
import {
  checkLabel,
  checkTypeLabel,
  seedTypeLabel,
  countryLabel,
  type Catalog,
  type CheckTask,
  type SeedType
} from "./model";
import type { ConfigurationReadState } from "./configuration";
import {
  AppCheckCreationUnconfirmed,
  AppCheckImportUnconfirmed,
  type AppCheckTaskSource
} from "./task-source";
import { createImportReader } from "./import-reader";
import { importSnapshot, type ImportOptions } from "./import-model";
import ImportSummary from "./ImportSummary.vue";
import SubmissionRecovery from "./SubmissionRecovery.vue";
import {
  downloadBlob,
  checkCondition,
  quantity,
  requestIdentity,
  timestamp,
  type WorkbenchSession
} from "./workbench";

const props = defineProps<{
  source: AppCheckTaskSource;
  catalog: Catalog;
  session: Pick<WorkbenchSession, "userId" | "draft" | "unconfirmedSubmissions">;
  active: boolean;
  targetTask?: CheckTask;
  configurationRead?: ConfigurationReadState;
}>();
const emit = defineEmits<{
  back: [];
  created: [taskId: string];
  endUnconfirmed: [];
  inspect: [];
  configuration: [];
}>();
const draft = computed(() => props.session.draft);
const selectedCheck = computed(() =>
  props.catalog.checks.find((check) => check.checkId === draft.value.checkId)
);
const selectedType = computed(
  () => props.targetTask?.checkType ?? selectedCheck.value?.checkType
);
const seedType = computed(() => props.targetTask?.seedType ?? draft.value.seedType);
const phoneSeeds = computed(() => seedType.value === "phonenumber");
const availableChecks = computed(() =>
  props.catalog.checks.filter((check) =>
    props.catalog.checkTypes.some(
      (type) => type.id === check.checkType && type.seedType === seedType.value
    )
  )
);
const seedChoice = computed({
  get: () => seedType.value,
  set: (value: SeedType) => {
    draft.value.seedType = value;
    draft.value.checkId = availableChecks.value[0]?.checkId ?? "";
    configurationChanged();
  }
});
const identity = consoleIdentity.requireCurrent();
const activeIdentity = () => consoleIdentity.current.value === identity;
const reading = ref(false),
  importError = ref(""),
  dragging = ref(false);
const fileInput = ref<HTMLInputElement>(),
  heading = ref<HTMLElement>();
const reader = createImportReader();
let generation = 0,
  disposed = false,
  timer: ReturnType<typeof setTimeout> | undefined;
const fileLimit = computed(() => props.catalog.limits.importFileBytes);
const blocked = computed(() => draft.value.busy || draft.value.uncertain);
const report = computed(() => draft.value.report);
const csvColumns = ref(report.value?.columns ?? ["第 1 列"]);
const firstInvalid = computed(() =>
  report.value?.rows.find((row) => row.kind === "invalid")
);
const valid = computed(
  () =>
    !!report.value?.seeds.length &&
    !!seedType.value &&
    (!!props.targetTask || !!selectedCheck.value) &&
    !report.value.blocked &&
    !report.value.overLimit &&
    !reading.value &&
    !importError.value &&
    !blocked.value
);
function options(): ImportOptions {
  return {
    seedType: seedType.value ?? "phonenumber",
    format: draft.value.format,
    country: draft.value.country,
    limit: props.catalog.limits.seedsPerImport,
    header: draft.value.header,
    column: draft.value.column
  };
}
function cancelRead() {
  generation++;
  clearTimeout(timer);
  reader.cancel();
  reading.value = false;
}
async function inspect(
  content: string | ArrayBuffer = draft.value.text,
  opts = options(),
  sourceFile = draft.value.fileName
) {
  if (blocked.value || !props.active) return;
  clearTimeout(timer);
  const token = ++generation;
  reading.value = true;
  importError.value = "";
  try {
    const result = await reader.read(content, opts);
    if (disposed || token !== generation || !props.active) return;
    draft.value.text = result.text;
    draft.value.fileName = sourceFile;
    draft.value.format = opts.format;
    draft.value.column = opts.column;
    draft.value.report = markRaw(result.report);
    csvColumns.value = result.report.columns;
  } catch (error) {
    if (!disposed && token === generation) {
      draft.value.report = undefined;
      importError.value = error instanceof Error ? error.message : "种子解析失败";
    }
  } finally {
    if (token === generation) reading.value = false;
  }
}
function schedule() {
  cancelRead();
  draft.value.report = undefined;
  importError.value = "";
  if (props.active && !blocked.value) {
    reading.value = true;
    timer = setTimeout(() => void inspect(), 200);
  }
}
function textChanged() {
  draft.value.fileName = "";
  draft.value.format = "txt";
  draft.value.column = 0;
  schedule();
}
function configurationChanged() {
  draft.value.seedType = seedType.value ?? "phonenumber";
  if (!draft.value.text && !draft.value.fileName) {
    cancelRead();
    draft.value.report = undefined;
    importError.value = "";
    return;
  }
  schedule();
}
async function readFile(file: File) {
  if (blocked.value) return;
  cancelRead();
  if (!phoneSeeds.value && !/\.txt$/i.test(file.name)) {
    importError.value = "text 种子请选择 TXT 文件，原输入已保留。";
    return;
  }
  if (file.size > fileLimit.value) {
    importError.value =
      "种子文件不能超过 " + fileLimit.value / 1024 / 1024 + " MiB，已保留原内容。";
    return;
  }
  if (!/\.(txt|csv)$/i.test(file.name)) {
    importError.value = "请选择 TXT 或 CSV 文件";
    return;
  }
  const token = generation;
  reading.value = true;
  try {
    const content = await file.arrayBuffer();
    if (disposed || token !== generation || !props.active) return;
    await inspect(
      content,
      { ...options(), format: /\.csv$/i.test(file.name) ? "csv" : "txt", column: 0 },
      file.name
    );
  } catch (error) {
    if (token === generation) {
      importError.value = error instanceof Error ? error.message : "文件读取失败";
      reading.value = false;
    }
  }
}
function choose(event: Event) {
  const input = event.target as HTMLInputElement;
  if (input.files?.[0]) void readFile(input.files[0]);
  input.value = "";
}
function drop(event: DragEvent) {
  dragging.value = false;
  if (event.dataTransfer?.files[0]) void readFile(event.dataTransfer.files[0]);
}
async function submit() {
  if (!valid.value || !report.value || !activeIdentity()) return;
  const current = draft.value;
  const inputFile = new Blob([report.value.seeds.join("\n")], {
    type: "text/plain;charset=UTF-8"
  });
  const inputSummary = importSnapshot(report.value, current.fileName || "粘贴种子");
  current.createdAtMillis = Date.now();
  current.busy = true;
  current.error = "";
  current.frozenCaller ??= captureTaskCaller();
  let importConfirmed = false;
  try {
    if (!props.source.importSeeds) throw new Error("当前数据源未接入种子导入");
    if (!current.knownTaskId) {
      current.frozenCreate ??= markRaw({
        requestId: current.requestId,
        checkId: current.checkId,
        ...(phoneSeeds.value ? { country: current.country } : {})
      });
      const response = await props.source.createTask(
        current.frozenCaller,
        structuredClone(current.frozenCreate)
      );
      current.knownTaskId = response.taskId;
    }
    current.submissionPhase = "import";
    if (!disposed && props.active && activeIdentity()) {
      await props.source.importSeeds(
        current.frozenCaller,
        current.knownTaskId,
        inputFile,
        inputSummary
      );
      importConfirmed = true;
    }
    if (disposed || !props.active || !activeIdentity()) {
      current.error = importConfirmed
        ? "本次导入已确认，请返回原身份核对实际数量；不会重新创建任务。"
        : "创建已确认，离开当前身份后尚未开始导入；请核对已知任务并显式继续。";
      current.uncertain = true;
    } else emit("created", current.knownTaskId);
  } catch (error) {
    current.uncertain =
      error instanceof AppCheckCreationUnconfirmed ||
      error instanceof AppCheckImportUnconfirmed;
    if (error instanceof AppCheckCreationUnconfirmed)
      current.knownTaskId = error.taskId;
    current.error = error instanceof Error ? error.message : "创建失败，已保留草稿";
    if (!current.uncertain && !current.knownTaskId) {
      current.requestId = requestIdentity();
      current.frozenCreate = undefined;
      current.frozenCaller = undefined;
    }
  } finally {
    current.busy = false;
  }
}
async function checkCreation() {
  const current = draft.value;
  if (
    !activeIdentity() ||
    !current.uncertain ||
    current.busy ||
    !current.frozenCreate ||
    !current.frozenCaller ||
    current.submissionPhase !== "create"
  )
    return;
  current.busy = true;
  try {
    const response = await props.source.createTask(
      current.frozenCaller,
      structuredClone(current.frozenCreate)
    );
    current.knownTaskId = response.taskId;
    current.submissionPhase = "import";
    current.error = "创建已确认，尚未自动导入或启动。请核对任务，或显式导入当前种子。";
  } catch (error) {
    if (error instanceof AppCheckCreationUnconfirmed && error.taskId)
      current.knownTaskId = error.taskId;
    current.error =
      error instanceof Error ? error.message : "创建仍未确认，请保留原请求继续核对。";
  } finally {
    current.busy = false;
  }
}
const exampleNumber = computed(() =>
  draft.value.country === "CN"
    ? "8613800000001"
    : draft.value.country === "US"
      ? "12025550123"
      : "447700900123"
);
function saveSubmissionReceipts() {
  downloadBlob(
    new Blob(
      [
        JSON.stringify(
          { state: "unconfirmed", submissions: props.session.unconfirmedSubmissions },
          null,
          2
        )
      ],
      { type: "application/json;charset=utf-8" }
    ),
    "app-checks-unconfirmed-submissions.json"
  );
}
function downloadTemplate() {
  downloadBlob(
    new Blob(["\uFEFF号码,备注\r\n" + exampleNumber.value + ",示例号码"], {
      type: "text/csv;charset=utf-8"
    }),
    "号码导入模板.csv"
  );
}
function focusHeading() {
  if (props.active) heading.value?.focus();
}
defineExpose({ focusHeading });
watch(
  () => props.active,
  async (active) => {
    if (!active) {
      cancelRead();
      return;
    }
    if (!draft.value.checkId) {
      draft.value.checkId = availableChecks.value[0]?.checkId ?? "";
      draft.value.country = props.catalog.countries[0];
    }
    if (props.targetTask) {
      draft.value.checkId = props.targetTask.checkId!;
      draft.value.country = props.targetTask.country ?? "CN";
      draft.value.knownTaskId = props.targetTask.taskId;
      draft.value.submissionPhase = "import";
    }
    draft.value.seedType = seedType.value ?? "phonenumber";
    if (draft.value.text && !report.value && !blocked.value) void inspect();
    await nextTick();
    if (!disposed && props.active) focusHeading();
  },
  { immediate: true }
);
onBeforeUnmount(() => {
  disposed = true;
  cancelRead();
  if (draft.value.busy) {
    draft.value.uncertain = true;
    draft.value.error = "离开页面时提交尚未确认，请核对任务列表。";
  }
});
</script>

<template>
  <section class="checks-create-page">
    <button
      v-if="!targetTask"
      class="checks-back"
      :disabled="draft.busy"
      @click="emit('back')"
    >
      <ArrowLeft />返回任务列表，保留草稿
    </button>
    <header class="checks-heading">
      <div>
        <h1 ref="heading" tabindex="-1">
          {{ targetTask ? "导入种子" : "创建检查任务" }}
        </h1>
        <p class="checks-hint">
          {{
            targetTask ? targetTask.name : "选择种子格式和检查能力，导入后核对启动。"
          }}
        </p>
      </div>
    </header>
    <SubmissionRecovery
      v-if="draft.uncertain"
      :draft="draft"
      :source="source"
      :create-context="!targetTask"
      @back="emit('back')"
      @end="emit('endUnconfirmed')"
      @inspect="emit('inspect')"
    />
    <el-button
      v-if="draft.uncertain && draft.submissionPhase === 'create' && draft.frozenCreate"
      :loading="draft.busy"
      @click="checkCreation"
      >使用原请求核对创建</el-button
    >
    <el-button
      v-if="draft.uncertain && draft.submissionPhase === 'import' && draft.knownTaskId"
      :disabled="draft.busy || reading"
      @click="
        draft.uncertain = false;
        submit();
      "
      >重新导入当前种子</el-button
    >
    <p class="checks-hint">
      {{ checkCondition(selectedType) }}
    </p>
    <p v-if="source.mode === 'mock' && seedType === 'text'" class="checks-hint">
      Mock 仅演示固定样例，不作为计算结果验证。{{
        selectedType === "uint32-prime-v1"
          ? "样例：0、1、2、17、017、49、4294967291、4294967295"
          : "样例：a、abc、中文、A"
      }}；其他种子演示执行失败。
    </p>
    <details
      v-if="session.unconfirmedSubmissions.length"
      class="checks-surface checks-submission-receipts"
    >
      <summary>
        待核对提交关联信息（{{ session.unconfirmedSubmissions.length }}）
      </summary>
      <p class="checks-hint">
        结束草稿不代表提交成功或任务已取消。关联信息仅保留在当前会话，刷新页面会清空。
      </p>
      <button type="button" class="checks-link" @click="saveSubmissionReceipts">
        保存关联信息
      </button>
      <ul>
        <li
          v-for="submission in session.unconfirmedSubmissions"
          :key="submission.requestId"
        >
          <strong
            >{{ checkLabel(submission.checkId) }} ·
            {{ submission.country ? countryLabel(submission.country) : "text" }} ·
            {{ quantity(submission.expectedCount) }} 个种子</strong
          >
          <span class="checks-hint"
            >提交于 {{ timestamp(submission.submittedAt) }} ·
            {{ submission.sourceFile }}</span
          >
          <span>请求编号：{{ submission.requestId }}</span>
          <router-link
            v-if="submission.knownTaskId"
            :to="{
              path: '/app-checks/tasks/' + encodeURIComponent(submission.knownTaskId),
              query: { view: 'create' }
            }"
            >打开关联任务</router-link
          >
          <span v-else class="checks-hint">尚无任务编号，原提交结果仍未确认。</span>
        </li>
      </ul>
    </details>
    <section
      v-if="!targetTask"
      class="checks-surface checks-create-configuration"
      aria-label="创建配置摘要"
    >
      <div class="checks-section-heading">
        <span
          >{{ seedTypeLabel(seedType) }} ·
          {{ checkLabel(draft.checkId, catalog) }}</span
        >
        <button
          v-if="canUseConsole(identity, 'app-checks.configuration')"
          type="button"
          class="checks-link"
          @click="emit('configuration')"
        >
          查看检查配置
        </button>
      </div>
      <p class="checks-hint">
        单次最多 {{ quantity(catalog.limits.seedsPerImport) }} 个种子 /
        {{ catalog.limits.importFileBytes / 1024 / 1024 }} MiB · 来源：{{
          source.mode === "mock" ? "Mock 演示数据" : "Catalog"
        }}
      </p>
      <p v-if="configurationRead?.error" class="checks-error">
        检查目录读取失败，已保留上次快照。
      </p>
      <details v-if="selectedCheck">
        <summary>执行配置详情</summary>
        <p>
          Group：{{ selectedCheck.workerGroupId }} · 契约：{{
            checkTypeLabel(selectedType, catalog)
          }}
          / {{ selectedType }}
        </p>
        <pre>{{ JSON.stringify(selectedCheck.execution, null, 2) }}</pre>
      </details>
      <p class="checks-hint">
        执行绑定及契约参数由服务端在首次创建时保存；后续导入和结果解释沿用任务保存值。
      </p>
    </section>
    <form
      :id="targetTask ? 'app-check-import' : 'app-check-create'"
      class="checks-surface checks-create-form"
      @submit.prevent="submit"
    >
      <fieldset :disabled="blocked" class="checks-fieldset">
        <div class="checks-two-columns">
          <label class="checks-field"
            >种子格式
            <select
              v-model="seedChoice"
              aria-label="种子格式"
              :disabled="!!draft.knownTaskId"
            >
              <option value="phonenumber">电话号码</option>
              <option value="text">文本</option>
            </select>
          </label>
          <label class="checks-field"
            >检查能力
            <select
              v-if="!targetTask && availableChecks.length > 1"
              v-model="draft.checkId"
              aria-label="检查能力"
              :disabled="!!draft.knownTaskId"
              @change="configurationChanged"
            >
              <option
                v-for="check in availableChecks"
                :key="check.checkId"
                :value="check.checkId"
              >
                {{ check.label }}
              </option>
            </select>
            <span v-else>{{
              targetTask?.checkLabel ??
              selectedCheck?.label ??
              "当前种子格式没有可用检查能力"
            }}</span>
          </label>
          <label v-if="phoneSeeds" class="checks-field"
            >号码地区
            <select
              v-model="draft.country"
              aria-label="号码国家"
              :disabled="!!draft.knownTaskId"
              @change="configurationChanged"
            >
              <option
                v-for="country in catalog.countries"
                :key="country"
                :value="country"
              >
                {{ countryLabel(country) }}
              </option>
            </select>
          </label>
        </div>
        <div class="checks-section-heading">
          <div>
            <h2>导入种子</h2>
            <p class="checks-hint">
              每次最多 {{ quantity(catalog.limits.seedsPerImport) }} 个种子 ·
              {{
                phoneSeeds
                  ? "保留国家码，开头的 + 可省略"
                  : "每行一个文本种子，保留大小写和内部内容"
              }}
            </p>
          </div>
          <button
            v-if="phoneSeeds"
            type="button"
            class="checks-link"
            @click="downloadTemplate"
          >
            下载 CSV 模板
          </button>
        </div>
        <div class="checks-segments" aria-label="种子输入方式">
          <button
            type="button"
            :aria-pressed="draft.inputMode === 'file'"
            @click="draft.inputMode = 'file'"
          >
            上传文件
          </button>
          <button
            type="button"
            :aria-pressed="draft.inputMode === 'paste'"
            @click="draft.inputMode = 'paste'"
          >
            粘贴种子
          </button>
        </div>
        <div
          v-if="draft.inputMode === 'file'"
          class="checks-dropzone"
          :class="{ dragging }"
          @dragover.prevent="dragging = true"
          @dragleave.prevent="dragging = false"
          @drop.prevent="drop"
        >
          <UploadFilled /><strong>{{
            reading
              ? "正在校验种子…"
              : draft.fileName ||
                (draft.text.trim()
                  ? "选择文件以替换当前粘贴内容"
                  : phoneSeeds
                    ? "拖拽 TXT 或 CSV 文件到这里"
                    : "拖拽 TXT 文件到这里")
          }}</strong>
          <button type="button" class="checks-link" @click="fileInput?.click()">
            {{ draft.fileName ? "更换文件" : "选择文件" }}
          </button>
          <span>UTF-8 · 最大 {{ fileLimit / 1024 / 1024 }} MiB · TXT 每行一个种子</span>
          <input
            ref="fileInput"
            type="file"
            :accept="phoneSeeds ? '.txt,.csv,text/plain,text/csv' : '.txt,text/plain'"
            aria-label="种子文件"
            class="checks-file-input"
            @change="choose"
          />
        </div>
        <label v-else class="checks-field">
          {{ phoneSeeds ? "每行一个带国家码的号码，+ 可省略" : "每行一个文本种子"
          }}<textarea
            v-model="draft.text"
            aria-label="种子列表"
            rows="5"
            :placeholder="phoneSeeds ? exampleNumber : 'a\nabc'"
            @input="textChanged"
          ></textarea>
        </label>
        <div v-if="phoneSeeds && draft.format === 'csv'" class="checks-csv-options">
          <label
            ><input
              v-model="draft.header"
              type="checkbox"
              @change="configurationChanged"
            />第一行是表头</label
          >
          <label
            >号码列<select
              v-model.number="draft.column"
              aria-label="CSV 号码列"
              :disabled="reading"
              @change="configurationChanged"
            >
              <option v-for="(column, index) in csvColumns" :key="index" :value="index">
                {{ column }}
              </option>
            </select></label
          >
        </div>
        <p v-if="reading" role="status" class="checks-hint">正在校验，请稍候…</p>
        <p v-if="importError" role="alert" class="checks-error">
          {{ importError }}
          <button type="button" class="checks-link" @click="inspect()">
            重新校验当前内容
          </button>
        </p>
        <div v-if="draft.text && !reading" class="checks-input-source" role="status">
          <span>当前种子来源</span><strong>{{ draft.fileName || "粘贴种子" }}</strong>
          <small v-if="draft.inputMode === 'file' && !draft.fileName"
            >当前仍为已粘贴的内容，选择新文件前不会更换来源。</small
          >
        </div>
        <template v-if="report && !reading">
          <ImportSummary :summary="report" :final-count="report.seeds.length" />
          <p v-if="report.blocked" role="alert" class="checks-error">
            整批校验未通过：{{
              quantity(report.invalidCount)
            }}
            个种子格式或国家码不符。<template v-if="firstInvalid"
              >首个错误在第 {{ firstInvalid.line }} 行：{{
                firstInvalid.issue
              }}。</template
            >请修正后重新导入。
          </p>
          <p v-else-if="report.overLimit" role="alert" class="checks-error">
            去重后超过
            {{ quantity(catalog.limits.seedsPerImport) }} 个种子，请减少后重新导入。
          </p>
          <p v-else-if="!report.seeds.length" role="alert" class="checks-error">
            没有可提交的种子，请重新导入。
          </p>
          <p v-else class="checks-hint">
            校验通过。导入后核对实际执行项数量，再启动查询。
          </p>
        </template>
      </fieldset>
      <p v-if="draft.error && !draft.uncertain" role="alert" class="checks-error">
        {{ draft.error }}
      </p>
      <footer class="checks-form-footer">
        <span class="checks-hint">草稿仅在当前会话保留</span>
        <div class="checks-actions">
          <el-button :disabled="draft.busy" @click="emit('back')">返回</el-button
          ><el-button
            type="primary"
            native-type="submit"
            :loading="draft.busy"
            :disabled="!valid"
            >{{
              targetTask || draft.knownTaskId ? "导入种子" : "创建待审核任务"
            }}</el-button
          >
        </div>
      </footer>
    </form>
  </section>
</template>
