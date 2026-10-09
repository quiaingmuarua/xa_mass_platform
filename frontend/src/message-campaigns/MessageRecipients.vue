<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, toRefs, watch } from "vue";
import { recipientEditor, type RecipientEditor } from "./workbench";
import {
  MESSAGE_IMPORT_LIMITS,
  type RecipientInput,
  type RecipientLimits
} from "@/files/phone-numbers";
import { createRecipientReader } from "./recipient-reader";
const props = withDefaults(
  defineProps<{
    country: string;
    disabled?: boolean;
    limits?: RecipientLimits;
    editor?: RecipientEditor;
  }>(),
  { limits: () => MESSAGE_IMPORT_LIMITS }
);
const emit = defineEmits<{
  change: [value: RecipientInput | undefined];
  busy: [value: boolean];
}>();
const state = props.editor ?? reactive(recipientEditor());
const { pasted, fileName, summary, error } = toRefs(state);
const reading = ref(false);
const reader = createRecipientReader();
let generation = 0;
async function validate() {
  const current = ++generation;
  reading.value = true;
  error.value = "";
  summary.value = undefined;
  emit("change", undefined);
  emit("busy", true);
  try {
    const value = await reader.read(state.source, props.country, props.limits);
    if (current !== generation) return;
    summary.value = value;
    emit("change", value);
  } catch (failure) {
    if (current === generation)
      error.value = failure instanceof Error ? failure.message : "号码读取失败";
  } finally {
    if (current === generation) {
      reading.value = false;
      emit("busy", false);
    }
  }
}
function choose(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) return;
  state.source = file;
  fileName.value = file.name;
  pasted.value = "";
  void validate();
}
function paste(value: string) {
  pasted.value = value;
  state.source = value;
  fileName.value = "";
  void validate();
}
function clear() {
  paste("");
}
watch(() => [props.country, props.limits], validate, { immediate: true });
onBeforeUnmount(() => {
  generation++;
  reader.cancel();
});
</script>
<template>
  <div class="message-recipients" :aria-busy="reading">
    <label
      >导入号码文件 · UTF-8 TXT · 最大 {{ limits.importFileBytes / 1024 / 1024 }} MiB
      <input
        type="file"
        accept=".txt,text/plain"
        aria-label="号码文件"
        :disabled="disabled"
        @change="choose"
      />
    </label>
    <p v-if="fileName">
      {{ fileName }}
      <el-button link :disabled="disabled" @click="clear">清除文件</el-button>
    </p>
    <el-input
      :model-value="pasted"
      :disabled="disabled"
      type="textarea"
      :rows="4"
      aria-label="收件号码"
      placeholder="每行一个号码，包含国家码，可省略 +；可留空后续导入"
      @update:model-value="paste"
    />
    <p v-if="reading" role="status">正在读取并校验…</p>
    <p v-if="summary" role="status">
      读取 {{ summary.inputCount.toLocaleString() }} 行 · 有效
      {{ summary.validCount.toLocaleString() }} · 重复
      {{ summary.duplicateCount.toLocaleString() }} · 无效
      {{ summary.invalidCount.toLocaleString() }}；最多
      {{ limits.recipientsPerImport.toLocaleString() }} 个去重号码。
    </p>
    <p v-if="summary?.duplicateCount">重复号码将自动跳过。</p>
    <p v-if="summary?.issues.length" role="alert">
      {{ summary.issues[0].line ? `第 ${summary.issues[0].line} 行：` : ""
      }}{{ summary.issues[0].message }}。请修正后重新导入，存在无效号码时整批拒绝。
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
  </div>
</template>
<style scoped>
.message-recipients {
  display: grid;
  gap: 10px;
  margin: 16px 0;
}
.message-recipients label {
  display: grid;
  gap: 8px;
  color: var(--rv-text-secondary);
  font-size: 12px;
}
.message-recipients p {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
}
.message-recipients [role="alert"] {
  color: var(--el-color-danger);
}
</style>
