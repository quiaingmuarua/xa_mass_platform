<script setup lang="ts">
import type { ProjectGroup } from "@/task-management/projects";

defineProps<{
  group?: ProjectGroup | null;
  groupId?: string;
  compact?: boolean;
}>();
</script>
<template>
  <section
    class="tw-card tw-capabilities"
    :class="{ 'tw-capabilities--compact': compact }"
  >
    <header class="tw-card__header">
      <div class="tw-capabilities__title">
        <h3>Group 能力参考</h3>
        <code v-if="group?.workerGroupId || groupId">{{
          group?.workerGroupId || groupId
        }}</code>
        <el-tag
          v-if="group"
          :type="group.workerGroup ? 'success' : 'warning'"
          effect="light"
          size="small"
        >
          {{ group.workerGroup ? "已注册" : "未注册" }}
        </el-tag>
      </div>
      <router-link
        v-if="group?.workerGroupId || groupId"
        class="tw-link"
        :to="{
          path: '/runtime/workers',
          query: { view: 'bindings', group: group?.workerGroupId || groupId }
        }"
      >
        查看资源绑定
      </router-link>
    </header>
    <p v-if="!group" class="raw-muted tw-card__empty">
      尚未读取到该 Group 的能力快照，请先选择 Group 或刷新。
    </p>
    <template v-else>
      <div class="tw-capabilities__block">
        <span class="tw-capabilities__label">已声明事件（eventCodes）</span>
        <div v-if="group.workerGroup?.eventCodes.length" class="tw-tag-list">
          <code
            v-for="eventCode in group.workerGroup.eventCodes"
            :key="eventCode"
            class="tw-code-pill"
          >
            {{ eventCode }}
          </code>
        </div>
        <span v-else class="raw-muted">{{
          group.workerGroup ? "未声明事件" : "Group 尚未注册"
        }}</span>
      </div>

      <div class="tw-capabilities__block">
        <span class="tw-capabilities__label">已启用 Pool</span>
        <div v-if="group.matching.pools.length" class="tw-tag-list">
          <el-tag
            v-for="pool in group.matching.pools"
            :key="pool"
            type="info"
            effect="plain"
            size="small"
          >
            {{ pool }}
          </el-tag>
        </div>
        <span v-else class="raw-muted">无启用 Pool（仅支持独立身份查询）</span>
      </div>

      <div class="tw-capabilities__block">
        <span class="tw-capabilities__label">查询函数与 Pool 映射</span>
        <table v-if="group.matching.functions.length" class="tw-mini-table">
          <thead>
            <tr>
              <th>查询函数（executorName）</th>
              <th>消费资源</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="fn in group.matching.functions" :key="fn.name">
              <td>
                <code>{{ fn.name }}</code>
              </td>
              <td>
                <el-tag v-if="fn.poolName" type="primary" effect="light" size="small">
                  Pool · {{ fn.poolName }}
                </el-tag>
                <span v-else class="raw-muted">独立直查（不消费 Pool）</span>
              </td>
            </tr>
          </tbody>
        </table>
        <span v-else class="raw-muted">无可用查询函数</span>
      </div>
      <p class="raw-muted tw-capabilities__note">
        能力来自 Group 声明与当前 Matching 配置，不保证 Worker 在线、已安装 Handler
        或已有库存。
      </p>
    </template>
  </section>
</template>
