<script setup lang="ts">
import { computed, ref } from "vue";
import { ArrowDown } from "@element-plus/icons-vue";
import {
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  type ButtonInstance
} from "element-plus";
import "element-plus/es/components/dropdown/style/css";
import "element-plus/es/components/dropdown-menu/style/css";
import "element-plus/es/components/dropdown-item/style/css";
import type { MessageTask } from "./task-source";
import { actionReason, isBusiness, taskStage, type MessageAction } from "./workbench";
const props = defineProps<{
  task: MessageTask;
  blocked?: boolean;
  hidePreview?: boolean;
}>();
const emit = defineEmits<{ action: [action: MessageAction, trigger?: HTMLElement] }>();
const trigger = ref<ButtonInstance>();
const actions = computed(() => {
  const stage = taskStage(props.task);
  const values: MessageAction[] = !isBusiness(props.task)
    ? ["preview"]
    : stage === "pre_review"
      ? ["import", "approve", "close"]
      : stage === "running"
        ? ["preview", "close"]
        : ["preview"];
  return props.hidePreview ? values.filter((value) => value !== "preview") : values;
});
const labels = {
  preview: "预览结果",
  import: "导入收件人",
  approve: "核对并启动",
  close: "中止任务"
};
const reason = (action: MessageAction) =>
  actionReason(props.task, action, !!props.blocked);
</script>
<template>
  <ElDropdown
    v-if="actions.length"
    trigger="click"
    @command="
      (action: MessageAction) => !reason(action) && emit('action', action, trigger?.$el)
    "
  >
    <el-button
      ref="trigger"
      size="small"
      :aria-label="'任务操作：' + (task.name || task.taskId)"
      >操作 <ArrowDown class="message-menu-arrow"
    /></el-button>
    <template #dropdown
      ><ElDropdownMenu
        ><ElDropdownItem
          v-for="action in actions"
          :key="action"
          :command="action"
          :disabled="!!reason(action)"
          >{{
            action === "close" && task.state === "pre_review"
              ? "取消任务"
              : labels[action]
          }}{{ reason(action) ? " · " + reason(action) : "" }}</ElDropdownItem
        ></ElDropdownMenu
      ></template
    >
  </ElDropdown>
</template>
