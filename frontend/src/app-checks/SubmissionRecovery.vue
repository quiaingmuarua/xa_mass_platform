<script setup lang="ts">
import { onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { ElDialog } from "element-plus";
import "element-plus/es/components/dialog/style/css";
import { appLabel, countryLabel, type CheckTask } from "./model";
import type { AppCheckTaskSource } from "./task-source";
import { quantity, timestamp, type CheckDraft } from "./workbench";
import TaskStatus from "./TaskStatus.vue";

const props = defineProps<{ draft: CheckDraft; source: AppCheckTaskSource }>();
const emit = defineEmits<{ back: []; end: [] }>();
const observed = shallowRef<CheckTask>();
const reading = ref(false),
  error = ref(""),
  readAt = ref(0);
const confirming = ref(false),
  acknowledged = ref(false);
let generation = 0;
watch(
  () => [props.draft.requestId, props.draft.knownTaskId],
  () => {
    generation++;
    observed.value = undefined;
    readAt.value = 0;
    reading.value = false;
    error.value = "";
    confirming.value = false;
    acknowledged.value = false;
  }
);
watch(confirming, () => {
  acknowledged.value = false;
});
async function readTask() {
  const id = props.draft.knownTaskId;
  if (!id || reading.value || props.draft.busy) return;
  const token = ++generation;
  reading.value = true;
  error.value = "";
  try {
    const detail = await props.source.loadTask(id);
    if (token !== generation) return;
    observed.value = detail.task;
    readAt.value = Date.now();
  } catch (failure) {
    if (token === generation) {
      observed.value = undefined;
      error.value = failure instanceof Error ? failure.message : "关联任务读取失败";
    }
  } finally {
    if (token === generation) reading.value = false;
  }
}
function finish() {
  if (
    !acknowledged.value ||
    props.draft.busy ||
    reading.value ||
    !props.draft.uncertain
  )
    return;
  emit("end");
}
onBeforeUnmount(() => {
  generation++;
});
</script>

<template>
  <section class="checks-surface checks-recovery" aria-label="处理未确认提交">
    <h2>本次提交尚未确认</h2>
    <p role="alert">{{ draft.error }}</p>
    <p class="checks-hint">
      草稿已冻结，不会再次提交。请核对号码数量与任务状态；查到任务不代表所有号码已写入或已启动。
    </p>
    <dl class="checks-description">
      <div>
        <dt>本次提交</dt>
        <dd>
          {{ appLabel(draft.appId) }} · {{ countryLabel(draft.country) }} ·
          {{ quantity(draft.report?.numbers.length) }} 个号码
        </dd>
      </div>
      <div>
        <dt>请求编号</dt>
        <dd>{{ draft.requestId }}</dd>
      </div>
      <div>
        <dt>关联任务</dt>
        <dd>
          <router-link
            v-if="draft.knownTaskId"
            :to="{
              path: '/app-checks/tasks/' + encodeURIComponent(draft.knownTaskId),
              query: { view: 'create' }
            }"
            >打开已知任务</router-link
          ><span v-else>暂未取得任务编号；列表中没有匹配项也不能确认创建失败。</span>
        </dd>
      </div>
    </dl>
    <div class="checks-actions">
      <el-button
        v-if="draft.knownTaskId"
        :loading="reading"
        :disabled="draft.busy"
        @click="readTask"
        >读取关联任务</el-button
      >
      <el-button :disabled="draft.busy" @click="emit('back')">核对任务列表</el-button>
      <el-button :disabled="draft.busy || reading" @click="confirming = true"
        >结束本次草稿</el-button
      >
    </div>
    <p v-if="draft.busy" role="status" class="checks-hint">
      提交仍在等待回复，暂不能结束草稿。
    </p>
    <p v-if="error" class="checks-error" role="alert">
      {{ error }}。未确认提交是否完整，请保留关联信息后继续核对。
    </p>
    <div v-if="observed" class="checks-recovery-observation" role="status">
      <strong>任务已记录 {{ quantity(observed.totalCount) }} 个号码</strong>
      <TaskStatus :task="observed" :preview="source.previewState?.(observed.taskId)" />
      <p class="checks-hint">
        读取于 {{ timestamp(readAt) }}。以上为任务快照，本次提交仍待人工核对。
      </p>
    </div>
    <ElDialog v-model="confirming" title="结束本次草稿？" width="520px">
      <p>这只会清空本地输入并打开空白草稿，不会重试创建、取消或补全服务端任务。</p>
      <p class="checks-hint">
        原请求编号和已知任务编号会保留在当前会话的关联信息中。本次提交仍可能只写入了部分号码，需要单独核对处理。
      </p>
      <label class="checks-recovery-ack"
        ><input
          v-model="acknowledged"
          type="checkbox"
          aria-label="已了解原提交仍需单独处理"
        />我已核对或记录本次提交，了解原提交仍需单独处理。</label
      >
      <template #footer>
        <el-button @click="confirming = false">继续核对</el-button>
        <el-button
          type="primary"
          :disabled="!acknowledged || draft.busy || reading"
          @click="finish"
          >确认结束草稿</el-button
        >
      </template>
    </ElDialog>
  </section>
</template>
