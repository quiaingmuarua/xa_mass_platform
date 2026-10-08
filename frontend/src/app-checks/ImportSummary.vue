<script setup lang="ts">
import type { ImportSnapshot } from "./import-model";
import { quantity } from "./workbench";
defineProps<{ summary: ImportSnapshot["summary"]; finalCount: number }>();
</script>
<template>
  <div class="checks-import-summary" aria-label="导入校验摘要">
    <div>
      <span>读取号码</span
      ><strong>{{ quantity(summary.inputCount - summary.emptyCount) }}</strong>
    </div>
    <div>
      <span>自动去重</span><strong>{{ quantity(summary.duplicateCount) }}</strong>
    </div>
    <div>
      <span>最终提交</span
      ><strong>{{
        summary.blocked || summary.overLimit ? "未通过" : quantity(finalCount)
      }}</strong>
    </div>
  </div>
  <p class="checks-hint">空行与首尾空白已自动清理，重复号码仅保留一份。</p>
</template>
