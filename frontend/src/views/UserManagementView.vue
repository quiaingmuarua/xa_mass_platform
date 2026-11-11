<script setup lang="ts">
import { consoleMockKey } from "@/console-mock";
import { computed, onBeforeUnmount, onMounted, reactive, ref, inject } from "vue";
import { ElDialog, ElDrawer, ElMessageBox } from "element-plus";
import "element-plus/es/components/drawer/style/css";
import "element-plus/es/components/message-box/style/css";
import CreditAccountPanel from "@/credits/CreditAccountPanel.vue";
import { can } from "@/access-policy";
import { normalizeTaskUserId } from "@/task-user-id";
import "element-plus/es/components/dialog/style/css";
import { consoleIdentity } from "@/console-identity";
import { projectEntrypoints, projectScope, roles, type RoleId } from "@/access-policy";
import type { ConsoleUser } from "@/development-users";
import { captureTaskCaller } from "@/console-identity";
import { createProjectSource, type Project } from "@/task-management/projects";
import { parseRuntimeViewerConfig } from "@/runtime-viewer/config";

const config = parseRuntimeViewerConfig(import.meta.env);
const source = config.ok
  ? createProjectSource(
      config.value.apiBaseUrl,
      config.value.mode,
      captureTaskCaller(),
      inject(consoleMockKey, undefined)?.directory
    )
  : undefined;
const projects = ref<Project[]>([]);
const loading = ref(false);
const directoryError = ref("");
const notice = ref("");
const creditTarget = ref("");
const creditLookup = ref("");
const creditOpen = ref(false);
const creditUnsettled = ref(false);
function openCredits(userId: string) {
  try {
    creditTarget.value = normalizeTaskUserId(userId);
    creditOpen.value = true;
  } catch {
    notice.value = "请输入 4～9 位小写字母或数字的 userId";
  }
}
const saving = ref(false);
const keyBusy = ref("");
// The only copy of a newly issued key; cleared when its dialog closes and never stored.
const issued = ref<{ userId: string; accessKey: string } | null>(null);
const copied = ref(false);
const mock = import.meta.env.VITE_RUNTIME_DATA_SOURCE === "mock";
const opened = ref(false);
const editing = ref<string>();
const error = ref("");
const form = reactive({
  userId: "",
  displayName: "",
  role: "operator" as RoleId,
  projectIds: [] as string[]
});
const allProjects = computed(() => projectScope(form) === "all");
const roleOptions = Object.entries(roles).map(([value, role]) => ({
  value,
  label: role.label
}));
const projectOptions = computed(() => [
  ...new Set([
    ...projects.value.map((p) => p.projectId),
    ...(editing.value ? form.projectIds : [])
  ])
]);
let active = true;
let controller: AbortController | undefined;
async function loadProjects() {
  controller?.abort();
  const request = new AbortController();
  controller = request;
  loading.value = true;
  directoryError.value = "";
  try {
    if (!source) throw new Error("Project 目录配置不可用");
    const value = await source.list(request.signal);
    if (active && controller === request) projects.value = value;
  } catch (cause) {
    if (active && controller === request)
      directoryError.value =
        cause instanceof Error ? cause.message : "无法读取 Project 目录";
  } finally {
    if (active && controller === request) loading.value = false;
  }
}
onMounted(() => {
  void loadProjects();
  void consoleIdentity.load(true);
});
onBeforeUnmount(() => {
  active = false;
  controller?.abort();
});
function open(user?: ConsoleUser) {
  editing.value = user?.userId;
  Object.assign(form, {
    userId: user?.userId ?? "",
    displayName: user?.displayName ?? "",
    role: user?.role ?? "operator",
    projectIds: [...(user?.projectIds ?? [])]
  });
  error.value = "";
  notice.value = "";
  opened.value = true;
}
async function save() {
  if (saving.value) return;
  saving.value = true;
  try {
    await consoleIdentity.saveUser(
      { ...form, projectIds: allProjects.value ? [] : [...form.projectIds] },
      editing.value
    );
    if (!active) return;
    notice.value = editing.value
      ? "用户已更新，新权限用于后续请求；页面入口请刷新或重新登录。"
      : "用户已新增；签发访问密钥后该用户即可登录。";
    opened.value = false;
  } catch (cause) {
    if (active) error.value = cause instanceof Error ? cause.message : "无法保存用户";
  } finally {
    if (active) saving.value = false;
  }
}
async function issueKey(user: ConsoleUser) {
  if (keyBusy.value) return;
  notice.value = "";
  error.value = "";
  try {
    if (user.hasAccessKey)
      await ElMessageBox.confirm(
        `为 ${user.displayName}（${user.userId}）重新签发访问密钥后，旧密钥立即失效。`,
        "重新签发访问密钥",
        { confirmButtonText: "重新签发", cancelButtonText: "取消", type: "warning" }
      );
    keyBusy.value = user.userId;
    const accessKey = await consoleIdentity.issueAccessKey(user.userId);
    if (active) {
      copied.value = false;
      issued.value = { userId: user.userId, accessKey };
    }
  } catch (cause) {
    if (active && cause instanceof Error) notice.value = cause.message;
  } finally {
    if (active) keyBusy.value = "";
  }
}
async function clearKey(user: ConsoleUser) {
  if (keyBusy.value) return;
  notice.value = "";
  try {
    await ElMessageBox.confirm(
      `清除后 ${user.displayName}（${user.userId}）当前的访问密钥立即失效，需重新签发才能登录。`,
      "清除访问密钥",
      { confirmButtonText: "清除", cancelButtonText: "取消", type: "warning" }
    );
    keyBusy.value = user.userId;
    await consoleIdentity.clearAccessKey(user.userId);
    if (active) notice.value = `已清除 ${user.userId} 的访问密钥。`;
  } catch (cause) {
    if (active && cause instanceof Error) notice.value = cause.message;
  } finally {
    if (active) keyBusy.value = "";
  }
}
async function copyIssued() {
  if (!issued.value) return;
  try {
    await navigator.clipboard.writeText(issued.value.accessKey);
    copied.value = true;
  } catch {
    copied.value = false;
  }
}
function projectLabel(projectId: string) {
  const entry = projectEntrypoints.find((item) => item.projectId === projectId);
  return entry ? projectId : `${projectId}（暂无普通用户入口）`;
}
</script>
<template>
  <section class="user-management">
    <header class="user-management__heading">
      <div>
        <h1>用户管理</h1>
        <p>配置用户的角色、可使用的 Project 和访问密钥。</p>
      </div>
      <div>
        <el-button
          :loading="consoleIdentity.loading.value"
          @click="consoleIdentity.load(true)"
          >刷新用户</el-button
        ><el-button type="primary" @click="open()">新增用户</el-button>
      </div>
    </header>
    <el-alert
      :title="
        mock
          ? 'Mock 用户数据仅用于前端演示，刷新后重置。'
          : '用户、角色与访问密钥哈希保存在 Server 用户目录文件中，修改立即用于后续请求；密钥只在签发时显示一次。'
      "
      type="info"
      :closable="false"
    />
    <el-alert
      v-if="notice"
      :title="notice"
      type="success"
      :closable="false"
      role="status"
    />
    <div v-if="directoryError" role="alert" class="user-management__error">
      {{ directoryError }}
      <el-button @click="loadProjects">重试 Project 目录</el-button>
    </div>
    <el-alert
      v-if="consoleIdentity.error.value"
      :title="consoleIdentity.error.value"
      type="error"
      :closable="false"
      role="alert"
    />
    <el-table :data="consoleIdentity.users" row-key="userId" aria-label="用户列表">
      <el-table-column prop="userId" label="userId" min-width="100" />
      <el-table-column prop="displayName" label="名称" min-width="130" />
      <el-table-column label="角色" min-width="140"
        ><template #default="{ row }"
          >{{ roles[(row as ConsoleUser).role].label }} · {{ row.role }}</template
        ></el-table-column
      >
      <el-table-column label="支持的 Project" min-width="260"
        ><template #default="{ row }">
          <span v-if="projectScope(row) === 'all'">全部 Project</span>
          <template v-else-if="row.projectIds.length"
            ><el-tag
              v-for="id in row.projectIds"
              :key="id"
              class="user-management__project"
              >{{ id }}</el-tag
            ></template
          >
          <span v-else>未分配</span>
        </template></el-table-column
      >
      <el-table-column label="访问密钥" min-width="110"
        ><template #default="{ row }">{{
          (row as ConsoleUser).hasAccessKey ? "已签发" : "未签发"
        }}</template></el-table-column
      >
      <el-table-column label="操作" width="360"
        ><template #default="{ row }">
          <span v-if="row.userId === consoleIdentity.current.value?.userId"
            >当前登录用户</span
          >
          <template v-else>
            <el-button :aria-label="`编辑用户 ${row.userId}`" @click="open(row)"
              >编辑</el-button
            >
            <el-button
              :aria-label="`签发访问密钥 ${row.userId}`"
              :loading="keyBusy === row.userId"
              @click="issueKey(row)"
              >{{ row.hasAccessKey ? "重新签发" : "签发密钥" }}</el-button
            >
            <el-button
              v-if="row.hasAccessKey"
              :aria-label="`清除访问密钥 ${row.userId}`"
              :disabled="keyBusy === row.userId"
              @click="clearKey(row)"
              >清除密钥</el-button
            >
          </template>
          <el-button
            v-if="can(consoleIdentity.current.value, 'credits.manage')"
            :aria-label="`积分账户 ${row.userId}`"
            @click="openCredits(row.userId)"
            >积分</el-button
          >
        </template></el-table-column
      >
    </el-table>
    <form
      v-if="can(consoleIdentity.current.value, 'credits.manage')"
      class="credit-lookup"
      @submit.prevent="openCredits(creditLookup)"
    >
      <el-input
        v-model="creditLookup"
        aria-label="查询积分 userId"
        placeholder="按 userId 查询账户（包括目录外旧账户）"
      />
      <el-button native-type="submit">查询积分账户</el-button>
    </form>
    <el-drawer
      v-model="creditOpen"
      title="用户积分账户"
      size="min(100vw, 1000px)"
      destroy-on-close
      :close-on-click-modal="!creditUnsettled"
      :close-on-press-escape="!creditUnsettled"
      :show-close="!creditUnsettled"
    >
      <CreditAccountPanel
        v-if="creditOpen"
        :key="creditTarget"
        :user-id="creditTarget"
        @unsettled="creditUnsettled = $event"
      />
    </el-drawer>
    <p class="user-management__note">
      管理员可使用全部 Project；普通用户仅能在获分配的业务入口管理本人任务。Project
      不会授予配置管理等管理员权限。
    </p>
    <el-dialog
      :model-value="issued !== null"
      title="访问密钥已签发"
      width="560px"
      destroy-on-close
      :close-on-click-modal="false"
      @close="issued = null"
    >
      <div v-if="issued" class="user-management__issued">
        <p>
          请把下面的密钥私下交给 <strong>{{ issued.userId }}</strong
          >。关闭后无法再次查看；旧密钥（如有）已失效。
        </p>
        <el-input :model-value="issued.accessKey" readonly aria-label="新访问密钥" />
        <el-button @click="copyIssued">{{ copied ? "已复制" : "复制密钥" }}</el-button>
      </div>
      <template #footer
        ><el-button type="primary" @click="issued = null"
          >我已保存，关闭</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      v-model="opened"
      :title="editing ? '编辑用户' : '新增用户'"
      width="520px"
      destroy-on-close
      :close-on-click-modal="!saving"
      :close-on-press-escape="!saving"
      :show-close="!saving"
    >
      <form id="console-user-form" class="user-management__form" @submit.prevent="save">
        <label
          >userId<el-input
            v-model="form.userId"
            aria-label="userId"
            :disabled="!!editing"
            placeholder="4～9 位小写字母或数字"
        /></label>
        <label
          >名称<el-input
            v-model="form.displayName"
            aria-label="用户名称"
            :maxlength="80"
        /></label>
        <label
          >角色<el-select v-model="form.role" aria-label="用户角色"
            ><el-option
              v-for="role in roleOptions"
              :key="role.value"
              :value="role.value"
              :label="`${role.label} · ${role.value}`" /></el-select
        ></label>
        <label
          >支持的 Project
          <span v-if="allProjects">全部 Project</span>
          <el-select
            v-else
            v-model="form.projectIds"
            multiple
            aria-label="支持的 Project"
            :loading="loading"
            :disabled="loading || !!directoryError"
            placeholder="选择 Project，可暂不分配"
          >
            <el-option
              v-for="id in projectOptions"
              :key="id"
              :value="id"
              :label="projectLabel(id)"
            />
          </el-select>
        </label>
        <p>角色决定操作权限，Project 决定可用的业务入口。SMS 仍需管理员角色。</p>
        <el-alert
          v-if="error"
          :title="error"
          type="error"
          :closable="false"
          role="alert"
        />
      </form>
      <template #footer
        ><el-button :disabled="saving" @click="opened = false">取消</el-button
        ><el-button
          type="primary"
          native-type="submit"
          form="console-user-form"
          :loading="saving"
          :disabled="!allProjects && (loading || !!directoryError)"
          >保存用户</el-button
        ></template
      >
    </el-dialog>
  </section>
</template>
<style scoped>
.user-management {
  display: grid;
  gap: 20px;
}
.credit-lookup {
  display: flex;
  gap: 12px;
  max-width: 600px;
}
.user-management__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.user-management__heading h1 {
  margin: 0 0 8px;
  font-size: 24px;
}
.user-management__heading p,
.user-management__note {
  margin: 0;
  color: var(--rv-muted);
}
.user-management__project {
  margin: 3px 6px 3px 0;
}
.user-management__form {
  display: grid;
  gap: 20px;
}
.user-management__form label {
  display: grid;
  gap: 8px;
}
.user-management__form p {
  margin: 0;
  color: var(--rv-muted);
  line-height: 1.6;
}
.user-management__issued {
  display: grid;
  gap: 12px;
}
.user-management__issued p {
  margin: 0;
  line-height: 1.6;
}
.user-management__error {
  color: var(--el-color-danger);
}
</style>
