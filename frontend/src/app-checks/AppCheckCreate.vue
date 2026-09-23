<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from "vue";
import { ElForm, ElFormItem } from "element-plus";
import "element-plus/theme-chalk/el-form.css";
import "element-plus/theme-chalk/el-form-item.css";
import { inspectRecipients, readRecipientFile } from "@/files/phone-numbers";
import {
  checkName,
  parseSimulation,
  rangeExamples,
  type Catalog,
  type CreateCheckTask
} from "./model";
import { AppCheckCreationUnconfirmed, type AppCheckTaskSource } from "./task-source";

const open = defineModel<boolean>({ required: true });
const props = defineProps<{ source: AppCheckTaskSource; catalog: Catalog }>();
const emit = defineEmits<{ created: [taskId: string] }>();
const fresh = () => ({
  appId:
    props.catalog.apps.find((app) => app.appId === "app-a")?.appId ??
    props.catalog.apps[0].appId,
  country: props.catalog.countries.includes("CN")
    ? ("CN" as const)
    : props.catalog.countries[0],
  numbers: "",
  simulation: JSON.stringify(props.catalog.simulationExample, null, 2)
});
const draft = reactive(fresh());
const busy = ref(false),
  uncertain = ref(false),
  reading = ref(false);
const error = ref(""),
  fileError = ref(""),
  fileName = ref("");
const knownTaskId = ref<string>();
const nameTime = ref(new Date());
const nameInput = ref<{ focus(): void }>();
function newRequestId(): string {
  return typeof globalThis.crypto?.randomUUID === "function"
    ? globalThis.crypto.randomUUID()
    : `app-check-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
let requestId = newRequestId();
let readGeneration = 0;
let disposed = false;
const blocked = computed(() => busy.value || uncertain.value);
const numbers = computed(() =>
  inspectRecipients(draft.numbers, draft.country, props.catalog.limits.numbersPerTask)
);
const name = computed(() =>
  checkName(draft.appId, draft.country, numbers.value.validCount, nameTime.value)
);
const group = computed(
  () =>
    props.catalog.apps.find((app) => app.appId === draft.appId)?.workerGroupId ?? "—"
);
const simulation = computed(() => {
  try {
    return { value: parseSimulation(draft.simulation), error: "" };
  } catch (error) {
    return {
      value: undefined,
      error: error instanceof Error ? error.message : "模拟描述无效"
    };
  }
});
const valid = computed(
  () =>
    !reading.value &&
    !blocked.value &&
    !!simulation.value.value &&
    numbers.value.recipients.length > 0 &&
    !numbers.value.issues.length
);
function example(label: string) {
  try {
    const current = JSON.parse(draft.simulation);
    // Only replace ranges; invalid delay must be repaired explicitly.
    const value = parseSimulation(
      JSON.stringify({ ranges: rangeExamples[label], delayMs: current.delayMs })
    );
    draft.simulation = JSON.stringify(value, null, 2);
    error.value = "";
  } catch {
    error.value = "请先提供合法 JSON 与 delayMs；范围示例不会重置延迟。";
  }
}
async function chooseFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  const generation = ++readGeneration;
  reading.value = true;
  fileError.value = "";
  try {
    const text = await readRecipientFile(file);
    if (!disposed && generation === readGeneration) {
      draft.numbers = text;
      fileName.value = file.name;
    }
  } catch (failure) {
    if (!disposed && generation === readGeneration)
      fileError.value = failure instanceof Error ? failure.message : "号码文件读取失败";
  } finally {
    input.value = "";
    if (!disposed && generation === readGeneration) reading.value = false;
  }
}
async function submit() {
  if (!valid.value || !simulation.value.value) return;
  nameTime.value = new Date();
  const input: CreateCheckTask = {
    requestId,
    name: name.value,
    appId: draft.appId,
    country: draft.country,
    numbers: [...numbers.value.recipients],
    simulation: structuredClone(simulation.value.value)
  };
  busy.value = true;
  error.value = "";
  try {
    const response = await props.source.createTask(input);
    if (disposed) return;
    Object.assign(draft, fresh());
    fileName.value = "";
    fileError.value = "";
    requestId = newRequestId();
    open.value = false;
    emit("created", response.taskId);
  } catch (failure) {
    if (disposed) return;
    uncertain.value = failure instanceof AppCheckCreationUnconfirmed;
    knownTaskId.value =
      failure instanceof AppCheckCreationUnconfirmed ? failure.taskId : undefined;
    error.value = failure instanceof Error ? failure.message : "创建失败，已保留草稿。";
    if (!uncertain.value) requestId = newRequestId();
  } finally {
    if (!disposed) busy.value = false;
  }
}
onBeforeUnmount(() => {
  disposed = true;
  readGeneration++;
});
</script>

<template>
  <el-drawer
    v-model="open"
    title="创建查询任务"
    size="min(680px, 100%)"
    class="checks-create"
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    @opened="nameInput?.focus()"
  >
    <p class="checks-hint">
      Project <strong>app-checks</strong>
      <span v-if="source.mode === 'mock'"> · Mock 数据</span>
    </p>
    <p>全部号码追加成功后自动批准，无需人工审核。</p>
    <el-form id="app-check-create" label-position="top" @submit.prevent="submit">
      <fieldset :disabled="blocked" class="checks-fieldset">
        <section class="checks-form-section">
          <h3>01 · 基本信息</h3>
          <div class="checks-columns">
            <el-form-item label="应用">
              <el-select v-model="draft.appId" aria-label="应用" :disabled="blocked">
                <el-option
                  v-for="app in catalog.apps"
                  :key="app.appId"
                  :value="app.appId"
                  :label="app.appId"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="号码国家">
              <el-select
                v-model="draft.country"
                aria-label="号码国家"
                :disabled="blocked"
              >
                <el-option
                  v-for="country in catalog.countries"
                  :key="country"
                  :value="country"
                  :label="country"
                />
              </el-select>
            </el-form-item>
          </div>
          <p class="checks-hint">WorkerGroup：{{ group }}。国家只约束号码。</p>
          <el-form-item label="任务名称（自动生成）"
            ><el-input
              ref="nameInput"
              :model-value="name"
              aria-label="任务名称"
              readonly
          /></el-form-item>
          <p class="checks-hint">
            按 App、国家、数量和提交时的本地时间生成显示名称；Task ID 与 salt
            由服务端生成。
          </p>
        </section>
        <section class="checks-form-section">
          <h3>02 · 号码输入</h3>
          <label for="check-numbers-file">导入号码文件</label>
          <p class="checks-hint">
            UTF-8 · 每行一个号码 · 最大 1 MiB · 最多
            {{ catalog.limits.numbersPerTask }} 个号码
          </p>
          <input
            id="check-numbers-file"
            type="file"
            accept=".txt,text/plain"
            aria-label="号码文件"
            :disabled="blocked || reading"
            @change="chooseFile"
          />
          <p v-if="fileName" class="checks-hint">
            已读取 {{ fileName }}，可以继续编辑。
          </p>
          <p v-if="fileError" role="alert" class="checks-error">{{ fileError }}</p>
          <el-form-item label="号码列表"
            ><el-input
              v-model="draft.numbers"
              type="textarea"
              :rows="6"
              aria-label="号码列表"
              placeholder="+8613800000001"
              :disabled="blocked || reading"
          /></el-form-item>
          <p class="checks-hint">
            有效号码 {{ numbers.validCount }} 个。只检查格式和国家前缀，不验证号码存在。
          </p>
          <ul v-if="numbers.issues.length" class="checks-error" role="alert">
            <li
              v-for="issue in numbers.issues.slice(0, 10)"
              :key="`${issue.line}-${issue.message}`"
            >
              第 {{ issue.line }} 行：{{ issue.message }}
            </li>
          </ul>
          <p v-if="numbers.issues.length > 10" class="checks-error">
            共 {{ numbers.issues.length }} 个错误，仅显示前 10 个。
          </p>
        </section>
        <section class="checks-form-section">
          <h3>03 · 模拟描述</h3>
          <div class="checks-actions">
            <el-button
              v-for="(_, label) in rangeExamples"
              :key="label"
              size="small"
              :disabled="blocked"
              @click="example(label)"
              >{{ label }}</el-button
            >
          </div>
          <el-form-item label="模拟 JSON"
            ><el-input
              v-model="draft.simulation"
              type="textarea"
              :rows="12"
              aria-label="模拟 JSON"
              :disabled="blocked"
          /></el-form-item>
          <p v-if="simulation.error" role="alert" class="checks-error">
            {{ simulation.error }}
          </p>
          <details class="checks-hint">
            <summary>区间、延迟与重试说明</summary>
            <p>
              三个左闭右开区间必须互斥并覆盖 0..1000，允许空区间。delayMs
              是闭区间，范围为 0..30000ms。
            </p>
            <p>
              已注册与未注册都属于执行成功；failed 区间会抛异常。超过 5
              秒可能遇到现有重试与迟到结果；换 Worker 后答案允许变化，不承诺整批比例。
            </p>
          </details>
        </section>
      </fieldset>
      <p v-if="error" role="alert" class="checks-error">{{ error }}</p>
      <router-link
        v-if="knownTaskId"
        :to="`/app-checks/tasks/${encodeURIComponent(knownTaskId)}`"
        @click="open = false"
        >查看已知任务 {{ knownTaskId }}</router-link
      >
    </el-form>
    <template #footer
      ><el-button :disabled="busy" @click="open = false">关闭并保留草稿</el-button
      ><el-button
        type="primary"
        form="app-check-create"
        native-type="submit"
        :loading="busy"
        :disabled="!valid"
        >创建并自动批准</el-button
      ></template
    >
  </el-drawer>
</template>
