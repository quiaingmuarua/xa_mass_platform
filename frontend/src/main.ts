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
  ElTable,
  ElTableColumn,
  ElTag
} from "element-plus";
import App from "./App.vue";
import { router } from "./router";
import {
  runtimeViewerConfigKey,
  runtimeViewerStoreKey,
  taskBatchStoreKey
} from "./runtime-context";
import { parseRuntimeViewerConfig } from "./runtime-viewer/config";
import { createRuntimeViewerDataSource } from "./runtime-viewer/data-source";
import { HttpTaskBatchClient } from "./task-batch/http-client";
import { createRuntimeViewerStore } from "./stores/runtime-viewer";
import { createTaskBatchStore } from "./stores/task-batch";
import "element-plus/es/components/alert/style/css";
import "element-plus/es/components/breadcrumb/style/css";
import "element-plus/es/components/breadcrumb-item/style/css";
import "element-plus/es/components/button/style/css";
import "element-plus/es/components/config-provider/style/css";
import "element-plus/es/components/drawer/style/css";
import "element-plus/es/components/icon/style/css";
import "element-plus/es/components/input/style/css";
import "element-plus/es/components/table/style/css";
import "element-plus/es/components/table-column/style/css";
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
  ElTable,
  ElTableColumn,
  ElTag
].forEach((component) => app.use(component));

if (configResult.ok) {
  const dataSource = createRuntimeViewerDataSource(configResult.value);
  const runtimeViewerStore = createRuntimeViewerStore(configResult.value, dataSource);
  const taskBatchStore = createTaskBatchStore(
    configResult.value,
    new HttpTaskBatchClient(configResult.value.apiBaseUrl),
    runtimeViewerStore
  );
  app.provide(runtimeViewerConfigKey, configResult.value);
  app.provide(runtimeViewerStoreKey, runtimeViewerStore);
  app.provide(taskBatchStoreKey, taskBatchStore);
}

app.mount("#app");
