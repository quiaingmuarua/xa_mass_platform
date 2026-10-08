<script setup lang="ts">
import { computed, markRaw, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { ArrowLeft, UploadFilled } from "@element-plus/icons-vue";
import { appLabel, countryLabel, checkName, type Catalog } from "./model";
import { AppCheckCreationUnconfirmed, type AppCheckTaskSource } from "./task-source";
import { createImportReader } from "./import-reader";
import { importSnapshot, type ImportOptions } from "./import-model";
import ImportSummary from "./ImportSummary.vue";
import SubmissionRecovery from "./SubmissionRecovery.vue";
import {
  downloadBlob,
  quantity,
  requestIdentity,
  timestamp,
  type WorkbenchSession
} from "./workbench";

const props = defineProps<{
  source: AppCheckTaskSource;
  catalog: Catalog;
  session: WorkbenchSession;
  active: boolean;
}>();
const emit = defineEmits<{ back: []; created: [taskId: string]; endUnconfirmed: [] }>();
const draft = computed(() => props.session.draft);
const reading = ref(false),
  importError = ref(""),
  dragging = ref(false);
const fileInput = ref<HTMLInputElement>(),
  heading = ref<HTMLElement>();
const reader = createImportReader();
let generation = 0,
  disposed = false,
  timer: ReturnType<typeof setTimeout> | undefined;
const preview = computed(() => props.source.mode === "mock");
const fileLimit = computed(() => (preview.value ? 10 : 1) * 1024 * 1024);
const blocked = computed(() => draft.value.busy || draft.value.uncertain);
const report = computed(() => draft.value.report);
const csvColumns = ref(report.value?.columns ?? ["第 1 列"]);
const displayName = computed(() =>
  checkName(
    draft.value.appId,
    draft.value.country,
    report.value?.numbers.length ?? 0,
    new Date(draft.value.createdAtMillis)
  )
);
const firstInvalid = computed(() =>
  report.value?.rows.find((row) => row.kind === "invalid")
);
const valid = computed(
  () =>
    !!report.value?.numbers.length &&
    !report.value.blocked &&
    !report.value.overLimit &&
    !reading.value &&
    !importError.value &&
    !blocked.value
);
function options(): ImportOptions {
  return {
    format: draft.value.format,
    country: draft.value.country,
    limit: props.catalog.limits.numbersPerTask,
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
      importError.value = error instanceof Error ? error.message : "号码解析失败";
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
  schedule();
}
async function readFile(file: File) {
  if (blocked.value) return;
  cancelRead();
  if (file.size > fileLimit.value) {
    importError.value =
      "号码文件不能超过 " + fileLimit.value / 1024 / 1024 + " MiB，已保留原内容。";
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
  if (!valid.value || !report.value) return;
  const current = draft.value;
  current.createdAtMillis = Date.now();
  current.busy = true;
  current.error = "";
  try {
    const response = await props.source.createTask({
      requestId: current.requestId,
      name: displayName.value,
      appId: current.appId,
      country: current.country,
      numbers: [...report.value.numbers],
      simulation: structuredClone(props.catalog.simulationExample),
      sourceFile: current.fileName || undefined,
      importSnapshot: importSnapshot(report.value, current.fileName || "粘贴号码")
    });
    if (disposed) {
      current.knownTaskId = response.taskId;
      current.error = "任务已创建，请打开已知任务核对。";
      current.uncertain = true;
      return;
    }
    emit("created", response.taskId);
  } catch (error) {
    current.uncertain = error instanceof AppCheckCreationUnconfirmed;
    current.knownTaskId =
      error instanceof AppCheckCreationUnconfirmed ? error.taskId : undefined;
    current.error = error instanceof Error ? error.message : "创建失败，已保留草稿";
    if (!current.uncertain) current.requestId = requestIdentity();
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
watch(
  () => props.active,
  async (active) => {
    if (!active) {
      cancelRead();
      return;
    }
    if (!draft.value.appId) {
      draft.value.appId = props.catalog.apps[0].appId;
      draft.value.country = props.catalog.countries[0];
    }
    if (draft.value.text && !report.value && !blocked.value) void inspect();
    await nextTick();
    heading.value?.focus();
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
    <button class="checks-back" :disabled="draft.busy" @click="emit('back')">
      <ArrowLeft />返回任务列表，保留草稿
    </button>
    <header class="checks-heading">
      <div>
        <h1 ref="heading" tabindex="-1">创建查询任务</h1>
        <p class="checks-hint">选择应用和地区，导入号码即可创建。任务名称自动生成。</p>
      </div>
    </header>
    <SubmissionRecovery
      v-if="draft.uncertain"
      :draft="draft"
      :source="source"
      @back="emit('back')"
      @end="emit('endUnconfirmed')"
    />
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
            >{{ appLabel(submission.appId) }} · {{ countryLabel(submission.country) }} ·
            {{ quantity(submission.expectedCount) }} 个号码</strong
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
    <form
      id="app-check-create"
      class="checks-surface checks-create-form"
      @submit.prevent="submit"
    >
      <fieldset :disabled="blocked" class="checks-fieldset">
        <div class="checks-two-columns">
          <label class="checks-field"
            >查询应用<select v-model="draft.appId" aria-label="应用">
              <option v-for="app in catalog.apps" :key="app.appId" :value="app.appId">
                {{ appLabel(app.appId) }}
              </option>
            </select></label
          >
          <label class="checks-field"
            >号码地区<select
              v-model="draft.country"
              aria-label="号码国家"
              @change="configurationChanged"
            >
              <option
                v-for="country in catalog.countries"
                :key="country"
                :value="country"
              >
                {{ countryLabel(country) }}
              </option>
            </select></label
          >
        </div>
        <div class="checks-section-heading">
          <div>
            <h2>导入号码</h2>
            <p class="checks-hint">
              每批最多 {{ quantity(catalog.limits.numbersPerTask) }} 个号码 ·
              保留国家码，开头的 + 可省略
            </p>
          </div>
          <button type="button" class="checks-link" @click="downloadTemplate">
            下载 CSV 模板
          </button>
        </div>
        <div class="checks-segments" aria-label="号码输入方式">
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
            粘贴号码
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
              ? "正在校验号码…"
              : draft.fileName ||
                (draft.text.trim()
                  ? "选择文件以替换当前粘贴内容"
                  : "拖拽 TXT 或 CSV 文件到这里")
          }}</strong>
          <button type="button" class="checks-link" @click="fileInput?.click()">
            {{ draft.fileName ? "更换文件" : "选择文件" }}
          </button>
          <span>UTF-8 · 最大 {{ fileLimit / 1024 / 1024 }} MiB · TXT 每行一个号码</span>
          <input
            ref="fileInput"
            type="file"
            accept=".txt,.csv,text/plain,text/csv"
            aria-label="号码文件"
            class="checks-file-input"
            @change="choose"
          />
        </div>
        <label v-else class="checks-field"
          >每行一个带国家码的号码，+ 可省略<textarea
            v-model="draft.text"
            aria-label="号码列表"
            rows="5"
            :placeholder="exampleNumber"
            @input="textChanged"
          ></textarea>
        </label>
        <div v-if="draft.format === 'csv'" class="checks-csv-options">
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
          <span>当前号码来源</span><strong>{{ draft.fileName || "粘贴号码" }}</strong>
          <small v-if="draft.inputMode === 'file' && !draft.fileName"
            >当前仍为已粘贴的内容，选择新文件前不会更换来源。</small
          >
        </div>
        <template v-if="report && !reading">
          <ImportSummary :summary="report" :final-count="report.numbers.length" />
          <p v-if="report.blocked" role="alert" class="checks-error">
            整批校验未通过：{{
              quantity(report.invalidCount)
            }}
            个号码格式或国家码不符。<template v-if="firstInvalid"
              >首个错误在第 {{ firstInvalid.line }} 行：{{
                firstInvalid.issue
              }}。</template
            >请修正后重新导入。
          </p>
          <p v-else-if="report.overLimit" role="alert" class="checks-error">
            去重后超过
            {{ quantity(catalog.limits.numbersPerTask) }} 个号码，请减少后重新导入。
          </p>
          <p v-else-if="!report.numbers.length" role="alert" class="checks-error">
            没有可提交的号码，请重新导入。
          </p>
          <p v-else class="checks-hint">
            校验通过，可创建任务。{{
              preview
                ? "创建后进入待审核，确认启动后再查询。"
                : "当前 API 创建成功后立即开始查询。"
            }}
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
            >{{ preview ? "创建待审核任务" : "创建并开始查询" }}</el-button
          >
        </div>
      </footer>
    </form>
  </section>
</template>
