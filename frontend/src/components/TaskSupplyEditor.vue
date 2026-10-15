<script setup lang="ts">
import { ref } from "vue";
import { ElInputNumber, ElRadioGroup, ElRadioButton } from "element-plus";
import "element-plus/es/components/input-number/style/css";
import "element-plus/es/components/radio-group/style/css";
import "element-plus/es/components/radio-button/style/css";
import { readSupply, type SupplyRow } from "@/task-management/supply";
const props = defineProps<{ pools: string[]; disabled: boolean }>();
const mode = defineModel<"rows" | "json">("mode", { required: true });
const text = defineModel<string>("text", { required: true });
const rows = defineModel<SupplyRow[]>("rows", { required: true });
const error = ref("");
function change(value: string | number | boolean | undefined) {
  if (props.disabled) return;
  try {
    const supply = readSupply(
      { refillMode: mode.value, refillText: text.value, supplyRows: rows.value },
      props.pools
    );
    if (value === "json") text.value = JSON.stringify(supply, null, 2);
    else
      rows.value = supply.map((row) => ({
        poolName: row.poolName,
        targetText: JSON.stringify(row.target),
        count: row.count
      }));
    mode.value = value === "json" ? "json" : "rows";
    error.value = "";
  } catch {
    error.value = "请先修正供给配置，再切换编辑方式。";
  }
}
</script>
<template>
  <div class="task-supply-editor">
    <el-radio-group
      :model-value="mode"
      :disabled="disabled"
      size="small"
      aria-label="供给编辑方式"
      @change="change"
    >
      <el-radio-button value="rows">供给行</el-radio-button
      ><el-radio-button value="json">完整 JSON</el-radio-button>
    </el-radio-group>
    <template v-if="mode === 'rows'">
      <div v-for="(row, index) in rows" :key="index" class="task-supply-row">
        <el-select
          v-model="row.poolName"
          filterable
          placeholder="Pool"
          :disabled="disabled"
          :aria-label="'供给 Pool ' + (index + 1)"
        >
          <el-option v-for="pool in pools" :key="pool" :value="pool" :label="pool" />
        </el-select>
        <el-input
          v-model="row.targetText"
          :disabled="disabled"
          placeholder="target JSON"
          :aria-label="'供给 target ' + (index + 1)"
        />
        <el-input-number
          v-model="row.count"
          :min="1"
          :disabled="disabled"
          :aria-label="'供给目标 ' + (index + 1)"
        />
        <el-button :disabled="disabled" @click="rows.splice(index, 1)">移除</el-button>
      </div>
      <el-button
        size="small"
        :disabled="disabled || !pools.length || rows.length >= 100"
        @click="rows.push({ poolName: '', targetText: '{}', count: 100 })"
        >添加供给</el-button
      >
    </template>
    <el-input
      v-else
      v-model="text"
      type="textarea"
      :rows="6"
      :disabled="disabled"
      aria-label="Task 供给声明"
    />
    <p class="raw-muted">
      供给目标是共享补给水位，不是并发数或独占额度。供给与每次调用的查询独立；{}
      仅为参数初值，[] 表示空供给。
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
  </div>
</template>
<style scoped>
.task-supply-editor {
  display: grid;
  gap: 12px;
}
.task-supply-editor > .el-button {
  justify-self: start;
}
.task-supply-editor > p {
  margin: 0;
}
.task-supply-row {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) minmax(200px, 2fr) 160px auto;
  gap: 10px;
}
.task-supply-editor :deep(textarea),
.task-supply-row :deep(input) {
  font-family: monospace;
}
</style>
