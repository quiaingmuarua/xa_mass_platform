<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref } from "vue";
import { ElDialog } from "element-plus";
import "element-plus/es/components/dialog/style/css";
import { RefreshRight, Plus, Search } from "@element-plus/icons-vue";
import { consoleIdentity } from "@/console-identity";
import { consoleMockKey } from "@/console-mock";
import { useRuntimeViewerConfig } from "@/runtime-context";
import {
  createWorkerGroupManagementSource,
  type WorkerGroupManagedView
} from "@/runtime-viewer/worker-group-management";

const emit = defineEmits<{
  openWorkers: [workerGroupId: string];
  openBindings: [workerGroupId: string];
}>();

const config = useRuntimeViewerConfig();
const consoleMock = inject(consoleMockKey, undefined);
const source = computed(() =>
  createWorkerGroupManagementSource(config, consoleMock?.workerGroupManagement)
);

const currentUser = computed(() => consoleIdentity.current.value);
const isAdmin = computed(() => currentUser.value?.role === "admin");

const groups = ref<WorkerGroupManagedView[]>([]);
const nextCursor = ref("0");
const unreadableCount = ref(0);
const lastReadAt = ref("");
const loading = ref(false);
const loadingMore = ref(false);
const error = ref("");
const notice = ref("");
const searchText = ref("");
const sourceFilter = ref<"all" | "startup_seeded" | "startup_bound" | "dynamic">("all");

let activeRequest: AbortController | undefined;

const filteredGroups = computed(() => {
  const query = searchText.value.trim().toLowerCase();
  return groups.value.filter((item) => {
    if (sourceFilter.value !== "all" && item.source !== sourceFilter.value) {
      return false;
    }
    if (!query) return true;
    return (
      item.workerGroupId.toLowerCase().includes(query) ||
      item.eventCodes.some((code) => code.toLowerCase().includes(query)) ||
      item.declaredProjects.some((proj) => proj.toLowerCase().includes(query))
    );
  });
});

function sourceLabel(sourceKind: WorkerGroupManagedView["source"]): string {
  if (sourceKind === "startup_seeded") return "启动托管";
  if (sourceKind === "startup_bound") return "启动绑定";
  return "动态注册";
}

function sourceTagType(sourceKind: WorkerGroupManagedView["source"]) {
  if (sourceKind === "startup_seeded") return "warning";
  if (sourceKind === "startup_bound") return "info";
  return "success";
}

function formatProtectionReason(reason: string): string {
  if (reason === "host_seed") return "启动种子配置 (group-config-json)";
  if (reason === "matching") return "Matching 启动配置";
  if (reason === "fixed_window") return "固定窗口 Pool 绑定";
  if (reason.startsWith("project:")) return `Project 声明 (${reason.slice(8)})`;
  return reason;
}

function mergePages(
  existing: WorkerGroupManagedView[],
  incoming: WorkerGroupManagedView[]
): WorkerGroupManagedView[] {
  const byId = new Map<string, WorkerGroupManagedView>();
  for (const item of existing) byId.set(item.workerGroupId, item);
  for (const item of incoming) byId.set(item.workerGroupId, item);
  return [...byId.values()].sort((a, b) =>
    a.workerGroupId.localeCompare(b.workerGroupId)
  );
}

async function loadDirectory(append = false) {
  if (!isAdmin.value) return;
  activeRequest?.abort();
  const controller = new AbortController();
  activeRequest = controller;
  if (append) loadingMore.value = true;
  else loading.value = true;
  error.value = "";
  try {
    const caller = consoleIdentity.requireCaller();
    const cursor = append ? nextCursor.value : "0";
    const page = await source.value.list(caller, cursor, 100, controller.signal);
    if (activeRequest !== controller) return;
    groups.value = append ? mergePages(groups.value, page.groups) : page.groups;
    nextCursor.value = page.nextCursor;
    unreadableCount.value = append
      ? unreadableCount.value + page.unreadableCount
      : page.unreadableCount;
    lastReadAt.value = page.readAt;
  } catch (cause) {
    if (activeRequest !== controller || controller.signal.aborted) return;
    error.value = cause instanceof Error ? cause.message : "读取 WorkerGroup 目录失败";
  } finally {
    if (activeRequest === controller) {
      loading.value = false;
      loadingMore.value = false;
      activeRequest = undefined;
    }
  }
}

onMounted(() => {
  void loadDirectory(false);
});
onBeforeUnmount(() => {
  activeRequest?.abort();
  activeRequest = undefined;
});

// Register / Edit dialog state
const editorOpen = ref(false);
const editorMode = ref<"register" | "edit">("register");
const editorGroupId = ref("");
const editorEventCodesText = ref("");
const editorAttributesText = ref("{}");
const editorSubmitting = ref(false);
const editorError = ref("");

function openRegisterDialog() {
  editorMode.value = "register";
  editorGroupId.value = "";
  editorEventCodesText.value = "";
  editorAttributesText.value = "{}";
  editorError.value = "";
  editorOpen.value = true;
}

function openEditDialog(row: WorkerGroupManagedView) {
  if (!row.editable) return;
  editorMode.value = "edit";
  editorGroupId.value = row.workerGroupId;
  editorEventCodesText.value = row.eventCodes.join("\n");
  editorAttributesText.value = JSON.stringify(row.attributes ?? {}, null, 2);
  editorError.value = "";
  editorOpen.value = true;
}

function parseEventCodes(raw: string): string[] {
  const entries = raw
    .split(/[\r\n,]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
  const seen = new Set<string>();
  const unique: string[] = [];
  for (const item of entries) {
    if (!seen.has(item)) {
      seen.add(item);
      unique.push(item);
    }
  }
  return unique;
}

function parseAttributesJson(raw: string): Record<string, unknown> {
  const trimmed = raw.trim();
  if (!trimmed) return {};
  const parsed: unknown = JSON.parse(trimmed);
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("attributes 必须是 JSON 对象");
  }
  return parsed as Record<string, unknown>;
}

async function submitEditor() {
  editorError.value = "";
  const groupId = editorGroupId.value.trim();
  if (!groupId) {
    editorError.value = "请输入非空的 WorkerGroup ID";
    return;
  }
  let attributes: Record<string, unknown>;
  try {
    attributes = parseAttributesJson(editorAttributesText.value);
  } catch (cause) {
    editorError.value =
      cause instanceof Error
        ? `attributes JSON 无效：${cause.message}`
        : "attributes JSON 无效";
    return;
  }
  const eventCodes = parseEventCodes(editorEventCodesText.value);
  editorSubmitting.value = true;
  try {
    const caller = consoleIdentity.requireCaller();
    if (editorMode.value === "register") {
      const result = await source.value.register(
        caller,
        groupId,
        attributes,
        eventCodes
      );
      notice.value =
        result.status === "already_registered"
          ? `WorkerGroup ${result.workerGroupId} 已存在且描述一致（幂等通过）。`
          : `WorkerGroup ${result.workerGroupId} 注册成功。`;
    } else {
      const result = await source.value.update(caller, groupId, attributes, eventCodes);
      notice.value =
        result.status === "unchanged"
          ? `WorkerGroup ${result.workerGroupId} 元数据无变更。`
          : `WorkerGroup ${result.workerGroupId} 元数据已更新。`;
    }
    editorOpen.value = false;
    await loadDirectory(false);
  } catch (cause) {
    editorError.value = cause instanceof Error ? cause.message : "提交失败";
  } finally {
    editorSubmitting.value = false;
  }
}

// Safe Delete dialog state
const deleteOpen = ref(false);
const deleteTarget = ref<WorkerGroupManagedView>();
const deleteConfirmInput = ref("");
const deleteSubmitting = ref(false);
const deleteError = ref("");

const deleteBlockedReasons = computed(() => {
  const row = deleteTarget.value;
  if (!row) return [];
  const blockers: string[] = [];
  if (!row.deletableByConfig) {
    blockers.push(
      `受启动配置保护：${row.protectionReasons.map(formatProtectionReason).join("、")}`
    );
  }
  if (row.workerCounts.activeTotal > 0) {
    blockers.push(
      `仍有 ${row.workerCounts.activeTotal} 个活跃 Worker（HOT ${row.workerCounts.activeHot} / 恢复中 ${row.workerCounts.recovery} / 暂停活跃 ${row.workerCounts.pausedActive}），请先由 Worker 控制方断开或迁移`
    );
  }
  return blockers;
});

const canConfirmDelete = computed(
  () =>
    deleteTarget.value !== undefined &&
    deleteBlockedReasons.value.length === 0 &&
    deleteConfirmInput.value.trim() === deleteTarget.value.workerGroupId &&
    !deleteSubmitting.value
);

function openDeleteDialog(row: WorkerGroupManagedView) {
  deleteTarget.value = row;
  deleteConfirmInput.value = "";
  deleteError.value = "";
  deleteOpen.value = true;
}

async function submitDelete() {
  if (!deleteTarget.value || !canConfirmDelete.value) return;
  deleteError.value = "";
  deleteSubmitting.value = true;
  try {
    const caller = consoleIdentity.requireCaller();
    const result = await source.value.deleteGroup(
      caller,
      deleteTarget.value.workerGroupId
    );
    notice.value = `已移除 WorkerGroup ${result.workerGroupId}，级联清理 ${result.removedWorkers} 个离线 Worker Runtime 记录。`;
    deleteOpen.value = false;
    await loadDirectory(false);
  } catch (cause) {
    deleteError.value =
      cause instanceof Error ? cause.message : "移除 WorkerGroup 失败";
  } finally {
    deleteSubmitting.value = false;
  }
}
</script>

<template>
  <section
    class="wgm-page"
    aria-label="WorkerGroup 管理"
    data-testid="worker-group-management"
  >
    <header class="wgm-header">
      <div>
        <h1>WorkerGroup 资源管理</h1>
        <p>
          分页浏览完整注册目录，执行注册、元数据编辑与安全移除（要求零活跃 Worker
          且无非终态 Task；SQLite 历史记录不受影响）。
        </p>
      </div>
      <div v-if="isAdmin" class="wgm-header__actions">
        <el-button
          :icon="RefreshRight"
          :loading="loading"
          @click="loadDirectory(false)"
        >
          刷新目录
        </el-button>
        <el-button
          type="primary"
          :icon="Plus"
          data-testid="open-register-group"
          @click="openRegisterDialog"
        >
          注册 WorkerGroup
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="!isAdmin"
      type="warning"
      :closable="false"
      show-icon
      title="当前会话没有管理员权限，无法访问 WorkerGroup 管理目录或执行变更操作。"
    />

    <template v-else>
      <div class="wgm-toolbar">
        <el-input
          v-model="searchText"
          class="wgm-search"
          clearable
          :prefix-icon="Search"
          aria-label="筛选 WorkerGroup"
          placeholder="按 Group ID、Event Code 或 Project 筛选已加载项"
        />
        <el-select v-model="sourceFilter" class="wgm-filter" aria-label="按来源筛选">
          <el-option label="全部来源" value="all" />
          <el-option label="启动托管 (startup_seeded)" value="startup_seeded" />
          <el-option label="启动绑定 (startup_bound)" value="startup_bound" />
          <el-option label="动态注册 (dynamic)" value="dynamic" />
        </el-select>
        <span v-if="lastReadAt" class="wgm-meta">
          已加载 {{ groups.length }} 项 · 最近读取
          {{ new Date(lastReadAt).toLocaleString() }}
        </span>
      </div>

      <el-alert
        v-if="notice"
        type="success"
        show-icon
        :title="notice"
        class="wgm-alert"
        @close="notice = ''"
      />
      <el-alert
        v-if="error"
        type="error"
        :closable="false"
        show-icon
        :title="error"
        class="wgm-alert"
      />
      <el-alert
        v-if="unreadableCount > 0"
        type="warning"
        :closable="false"
        show-icon
        :title="`目录中存在 ${unreadableCount} 条无法解码的 Group 脏记录，已在列表中忽略。`"
        class="wgm-alert"
      />

      <el-table
        :data="filteredGroups"
        stripe
        border
        row-key="workerGroupId"
        :empty-text="
          loading ? '正在加载 WorkerGroup 目录...' : '暂无匹配的 WorkerGroup'
        "
      >
        <el-table-column label="WorkerGroup ID" min-width="220">
          <template #default="{ row }">
            <code class="wgm-code">{{ row.workerGroupId }}</code>
          </template>
        </el-table-column>

        <el-table-column label="来源" width="120">
          <template #default="{ row }">
            <el-tag :type="sourceTagType(row.source)" effect="light" size="small">
              {{ sourceLabel(row.source) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="归属 Project" min-width="170">
          <template #default="{ row }">
            <div v-if="row.declaredProjects.length > 0" class="wgm-tags">
              <el-tag
                v-for="project in row.declaredProjects"
                :key="project"
                size="small"
                effect="plain"
              >
                {{ project }}
              </el-tag>
            </div>
            <span v-else class="wgm-muted">开放注册范围</span>
          </template>
        </el-table-column>

        <el-table-column label="Worker 状态计数" min-width="230">
          <template #default="{ row }">
            <div class="wgm-counts">
              <el-tag size="small" effect="plain"
                >总计 {{ row.workerCounts.total }}</el-tag
              >
              <el-tag
                size="small"
                :type="row.workerCounts.activeTotal > 0 ? 'success' : 'info'"
                effect="light"
              >
                活跃 {{ row.workerCounts.activeTotal }}
              </el-tag>
              <el-tag
                v-if="row.workerCounts.recovery > 0"
                size="small"
                type="warning"
                effect="light"
              >
                恢复中 {{ row.workerCounts.recovery }}
              </el-tag>
              <el-tag size="small" type="info" effect="plain">
                离线 {{ row.workerCounts.cold + row.workerCounts.pausedOffline }}
              </el-tag>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="Event Codes" min-width="220">
          <template #default="{ row }">
            <div v-if="row.eventCodes.length > 0" class="wgm-events">
              <code
                v-for="code in row.eventCodes.slice(0, 2)"
                :key="code"
                class="wgm-event-pill"
              >
                {{ code }}
              </code>
              <span v-if="row.eventCodes.length > 2" class="wgm-muted">
                +{{ row.eventCodes.length - 2 }}
              </span>
            </div>
            <span v-else class="wgm-muted">无声明事件</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <div class="wgm-row-actions">
              <el-button
                size="small"
                :disabled="!row.editable"
                :title="
                  row.editable
                    ? '编辑 attributes 与 eventCodes'
                    : '启动种子托管的 Group 不允许运行时编辑'
                "
                @click="openEditDialog(row)"
              >
                编辑
              </el-button>
              <el-button size="small" @click="emit('openWorkers', row.workerGroupId)">
                观察
              </el-button>
              <el-button size="small" @click="emit('openBindings', row.workerGroupId)">
                绑定
              </el-button>
              <el-button
                size="small"
                type="danger"
                plain
                :disabled="!row.deletableByConfig"
                :title="
                  row.deletableByConfig
                    ? '检查活跃状态并安全移除该 WorkerGroup'
                    : `受启动配置保护：${row.protectionReasons.join(', ')}`
                "
                @click="openDeleteDialog(row)"
              >
                移除
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="nextCursor !== '0'" class="wgm-pagination">
        <el-button :loading="loadingMore" @click="loadDirectory(true)">
          加载下一页 (cursor={{ nextCursor }})
        </el-button>
      </div>
    </template>

    <el-dialog
      v-model="editorOpen"
      :title="
        editorMode === 'register' ? '注册 WorkerGroup' : `编辑元数据：${editorGroupId}`
      "
      width="560px"
      destroy-on-close
    >
      <div class="wgm-dialog-body">
        <el-alert
          v-if="editorMode === 'edit'"
          type="info"
          :closable="false"
          show-icon
          title="仅更新 Catalog 中的 attributes 与 eventCodes 元数据，不会对在线 Worker 动态安装或卸载 Handler。"
        />
        <label class="wgm-field">
          <span>WorkerGroup ID</span>
          <el-input
            v-model="editorGroupId"
            :disabled="editorMode === 'edit'"
            placeholder="例如：ops-crawler-workers"
            aria-label="编辑 WorkerGroup ID"
          />
        </label>
        <label class="wgm-field">
          <span>Event Codes（每行一个或逗号分隔）</span>
          <el-input
            v-model="editorEventCodesText"
            type="textarea"
            :rows="4"
            placeholder="extension.worker.sample.echo"
            aria-label="编辑 Event Codes"
          />
        </label>
        <label class="wgm-field">
          <span>Attributes（JSON 对象）</span>
          <el-input
            v-model="editorAttributesText"
            type="textarea"
            :rows="4"
            placeholder='{"env": "prod"}'
            aria-label="编辑 Attributes JSON"
          />
        </label>
        <p v-if="editorError" class="wgm-error" role="alert">{{ editorError }}</p>
      </div>
      <template #footer>
        <el-button :disabled="editorSubmitting" @click="editorOpen = false"
          >取消</el-button
        >
        <el-button
          type="primary"
          :loading="editorSubmitting"
          data-testid="submit-group-editor"
          @click="submitEditor"
        >
          {{ editorMode === "register" ? "确认注册" : "保存元数据" }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="deleteOpen"
      :title="
        deleteTarget
          ? `安全移除 WorkerGroup：${deleteTarget.workerGroupId}`
          : '安全移除 WorkerGroup'
      "
      width="560px"
      destroy-on-close
    >
      <div v-if="deleteTarget" class="wgm-dialog-body">
        <el-alert
          v-if="deleteBlockedReasons.length > 0"
          type="error"
          :closable="false"
          show-icon
          title="当前不满足安全移除前提条件"
        >
          <ul class="wgm-blocker-list">
            <li v-for="reason in deleteBlockedReasons" :key="reason">{{ reason }}</li>
          </ul>
        </el-alert>
        <el-alert
          v-else
          type="warning"
          :closable="false"
          show-icon
          title="将清除该 Group 的全部 Runtime Key（Catalog、Bindings、Score、Identity、接入控制、Matching 索引），并级联清理历史离线 Worker。SQLite Task 历史记录保持不变。"
        />

        <dl class="wgm-precheck">
          <div>
            <dt>配置保护</dt>
            <dd>
              {{
                deleteTarget.deletableByConfig
                  ? "通过（动态注册，无启动配置引用）"
                  : "拦截"
              }}
            </dd>
          </div>
          <div>
            <dt>活跃 Worker 数</dt>
            <dd>{{ deleteTarget.workerCounts.activeTotal }}（要求为 0）</dd>
          </div>
          <div>
            <dt>待级联清理的离线 Worker</dt>
            <dd>
              {{
                deleteTarget.workerCounts.cold + deleteTarget.workerCounts.pausedOffline
              }}
            </dd>
          </div>
          <div>
            <dt>非终态 Task 检查</dt>
            <dd>提交时由服务端实时校验（存在未结束或休眠 Task 将被安全拦截）</dd>
          </div>
        </dl>

        <label v-if="deleteBlockedReasons.length === 0" class="wgm-field">
          <span
            >请输入完整 WorkerGroup ID（<code>{{ deleteTarget.workerGroupId }}</code
            >）以确认移除</span
          >
          <el-input
            v-model="deleteConfirmInput"
            :placeholder="deleteTarget.workerGroupId"
            aria-label="确认移除 WorkerGroup ID"
          />
        </label>
        <p v-if="deleteError" class="wgm-error" role="alert">{{ deleteError }}</p>
      </div>
      <template #footer>
        <el-button :disabled="deleteSubmitting" @click="deleteOpen = false"
          >取消</el-button
        >
        <el-button
          type="danger"
          :loading="deleteSubmitting"
          :disabled="!canConfirmDelete"
          data-testid="confirm-delete-group"
          @click="submitDelete"
        >
          确认移除 WorkerGroup
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.wgm-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.wgm-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}
.wgm-header h1 {
  margin: 0 0 6px;
}
.wgm-header p {
  margin: 0;
  color: var(--el-text-color-secondary);
}
.wgm-header__actions {
  display: flex;
  gap: 10px;
}
.wgm-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.wgm-search {
  width: 340px;
}
.wgm-filter {
  width: 220px;
}
.wgm-meta,
.wgm-muted {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.wgm-alert {
  margin-bottom: 4px;
}
.wgm-code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
}
.wgm-tags,
.wgm-counts,
.wgm-events,
.wgm-row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.wgm-event-pill {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-size: 12px;
}
.wgm-pagination {
  display: flex;
  justify-content: center;
  padding-top: 8px;
}
.wgm-dialog-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.wgm-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
}
.wgm-precheck {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin: 0;
  padding: 12px;
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-lighter);
}
.wgm-precheck dt {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.wgm-precheck dd {
  margin: 2px 0 0;
  font-size: 13px;
  font-weight: 500;
}
.wgm-blocker-list {
  margin: 6px 0 0;
  padding-left: 18px;
}
.wgm-error {
  margin: 0;
  color: var(--el-color-danger);
  font-size: 13px;
}
</style>
