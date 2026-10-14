<script setup lang="ts">
import { ElDropdown, ElDropdownMenu, ElDropdownItem } from "element-plus";
import "element-plus/es/components/dropdown/style/css";
import "element-plus/es/components/dropdown-menu/style/css";
import "element-plus/es/components/dropdown-item/style/css";
import type { TaskRow } from "@/components/RawTaskTable.vue";
defineProps<{ row: TaskRow; writable: boolean; busy: boolean }>();
defineEmits<{ action: [action: string] }>();
</script>
<template>
  <el-dropdown trigger="click" @command="$emit('action', String($event))">
    <el-button link :disabled="busy">操作 ▾</el-button>
    <template #dropdown
      ><el-dropdown-menu>
        <el-dropdown-item command="view">查看任务</el-dropdown-item>
        <el-dropdown-item
          v-if="row.scoreBand === 'pre_review'"
          command="append"
          :disabled="!writable || row.task?.metadata['server.inputOwner'] !== 'raw'"
          >追加 Items</el-dropdown-item
        >
        <el-dropdown-item
          v-if="row.scoreBand === 'pre_review'"
          command="approve"
          :disabled="!writable"
          >核对并批准</el-dropdown-item
        >
        <el-dropdown-item
          v-if="row.scoreBand && row.scoreBand !== 'terminal'"
          command="close"
          :disabled="!writable"
          >关闭任务</el-dropdown-item
        >
        <el-dropdown-item v-if="row.scoreBand === 'terminal'" command="export"
          >查看并导出结果</el-dropdown-item
        >
      </el-dropdown-menu></template
    >
  </el-dropdown>
</template>
