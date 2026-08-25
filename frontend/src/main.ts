import { createApp } from "vue";
import { createPinia } from "pinia";
import {
  ElAlert,
  ElBreadcrumb,
  ElBreadcrumbItem,
  ElButton,
  ElConfigProvider,
  ElDrawer,
  ElIcon,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  ElTag
} from "element-plus";
import App from "./App.vue";
import { router } from "./router";
import {
  runtimeViewerConfigKey,
  runtimeViewerStoreKey,
  taskCallDebugStoreKey,
  taskManagementStoreKey,
  workerDirectDebugStoreKey,
  workerStatusStoreKey
} from "./runtime-context";
import { parseRuntimeViewerConfig } from "./runtime-viewer/config";
import { createRuntimeViewerDataSource } from "./runtime-viewer/data-source";
import { HttpTaskCallDebugClient } from "./task-call-debug/http-client";
import { HttpFiniteTaskClient } from "./task-management/http-client";
import { createRuntimeViewerStore } from "./stores/runtime-viewer";
import { createTaskCallDebugStore } from "./stores/task-call-debug";
import { createTaskManagementStore } from "./stores/task-management";
import { createWorkerDirectDebugStore } from "./stores/worker-direct-debug";
import { createWorkerStatusStore } from "./stores/worker-status";
import { createWorkerStatusDataSource } from "./worker-status/data-source";
import { HttpWorkerDirectCallClient } from "./worker-direct-call/http-client";
import "element-plus/es/components/alert/style/css";
import "element-plus/es/components/breadcrumb/style/css";
import "element-plus/es/components/breadcrumb-item/style/css";
import "element-plus/es/components/button/style/css";
import "element-plus/es/components/config-provider/style/css";
import "element-plus/es/components/drawer/style/css";
import "element-plus/es/components/icon/style/css";
import "element-plus/es/components/input/style/css";
import "element-plus/es/components/message/style/css";
import "element-plus/es/components/option/style/css";
import "element-plus/es/components/select/style/css";
import "element-plus/es/components/table/style/css";
import "element-plus/es/components/table-column/style/css";
import "element-plus/es/components/tab-pane/style/css";
import "element-plus/es/components/tabs/style/css";
import "element-plus/es/components/tag/style/css";
import "element-plus/theme-chalk/dark/css-vars.css";
import "./styles/index.css";

const configResult = parseRuntimeViewerConfig(import.meta.env);
const app = createApp(App, { configResult });
const pinia = createPinia();

app.use(pinia);
app.use(router);
[
  ElAlert,
  ElBreadcrumb,
  ElBreadcrumbItem,
  ElButton,
  ElConfigProvider,
  ElDrawer,
  ElIcon,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  ElTag
].forEach((component) => app.use(component));

if (configResult.ok) {
  const dataSource = createRuntimeViewerDataSource(configResult.value);
  const runtimeViewerStore = createRuntimeViewerStore(configResult.value, dataSource);
  const taskManagementStore = createTaskManagementStore(
    runtimeViewerStore,
    new HttpFiniteTaskClient(configResult.value.apiBaseUrl)
  );
  const taskCallDebugStore = createTaskCallDebugStore(
    new HttpTaskCallDebugClient(configResult.value.apiBaseUrl),
    configResult.value.mode
  );
  const workerStatusStore = createWorkerStatusStore(
    createWorkerStatusDataSource(configResult.value)
  );
  const workerDirectDebugStore = createWorkerDirectDebugStore(
    new HttpWorkerDirectCallClient(configResult.value.apiBaseUrl),
    configResult.value.mode
  );
  app.provide(runtimeViewerConfigKey, configResult.value);
  app.provide(runtimeViewerStoreKey, runtimeViewerStore);
  app.provide(taskManagementStoreKey, taskManagementStore);
  app.provide(taskCallDebugStoreKey, taskCallDebugStore);
  app.provide(workerDirectDebugStoreKey, workerDirectDebugStore);
  app.provide(workerStatusStoreKey, workerStatusStore);
}

app.mount("#app");
