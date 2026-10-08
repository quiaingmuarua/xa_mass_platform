<script setup lang="ts">
import { computed, ref } from "vue";
import { ArrowDown } from "@element-plus/icons-vue";
import {
  ElDropdown,
  ElDropdownMenu,
  ElDropdownItem,
  type ButtonInstance
} from "element-plus";
import "element-plus/es/components/dropdown/style/css";
import "element-plus/es/components/dropdown-menu/style/css";
import "element-plus/es/components/dropdown-item/style/css";
import type { CheckTask } from "./model";
import type { AppCheckTaskSource } from "./task-source";
import { taskStage, type TaskAction } from "./workbench";
const props = defineProps<{
  task: CheckTask;
  source: AppCheckTaskSource;
  hidePreview?: boolean;
  blocked?: boolean;
}>();
const emit = defineEmits<{ action: [action: TaskAction, trigger?: HTMLElement] }>();
const trigger = ref<ButtonInstance>();
const stage = computed(() => taskStage(props.task));
const actions = computed<TaskAction[]>(() => {
  const items: TaskAction[] =
    props.task.managed || !props.task.appId
      ? ["preview"]
      : stage.value === "pre_review"
        ? ["import", "approve", "close"]
        : stage.value === "running"
          ? ["preview", "close"]
          : stage.value === "terminal"
            ? ["preview", "export"]
            : ["preview"];
  return props.hidePreview ? items.filter((item) => item !== "preview") : items;
});
const labels: Record<TaskAction, string> = {
  preview: "预览结果",
  import: "导入号码",
  approve: "核对并启动",
  close: "中止任务",
  export: "导出结果"
};
function unavailable(action: TaskAction) {
  if (action === "preview") return "";
  if (
    (action === "import" || action === "approve") &&
    props.task.inputUnavailableReason
  )
    return props.task.inputUnavailableReason;
  if (action === "import" && props.task.inputVersion !== "2")
    return "旧任务不支持追加导入";
  if (action === "approve" && !props.task.totalCount) return "请先导入号码";
  const supported =
    action === "import"
      ? !!props.source.importNumbers
      : action === "approve"
        ? !!props.source.approveTask
        : action === "close"
          ? !!props.source.closeTask
          : !!props.source.exportTask;
  if (!supported) return "暂未接入";
  return props.blocked ? "请先等待操作完成或刷新有效状态" : "";
}
function command(action: TaskAction) {
  if (!unavailable(action)) emit("action", action, trigger.value?.$el);
}
</script>
<template>
  <div v-if="actions.length" class="checks-action-group">
    <ElDropdown trigger="click" @command="command">
      <el-button
        ref="trigger"
        size="small"
        :aria-label="'任务操作：' + (task.name || task.taskId)"
      >
        操作 <ArrowDown class="checks-menu-arrow" aria-hidden="true" />
      </el-button>
      <template #dropdown>
        <ElDropdownMenu>
          <ElDropdownItem
            v-for="action in actions"
            :key="action"
            :command="action"
            :disabled="!!unavailable(action)"
          >
            {{
              action === "close" && stage === "pre_review"
                ? "取消任务"
                : labels[action]
            }}{{ unavailable(action) ? " · " + unavailable(action) : "" }}
          </ElDropdownItem>
        </ElDropdownMenu>
      </template>
    </ElDropdown>
  </div>
</template>
