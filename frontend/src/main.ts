import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import "@fontsource/inter/400.css";
import "@fontsource/inter/500.css";
import "@fontsource/inter/600.css";
import "@fontsource/inter/700.css";

import App from "./App.vue";
import { router } from "./router";
import { runtimeViewerConfigKey, runtimeViewerStoreKey } from "./runtime-context";
import { parseRuntimeViewerConfig } from "./runtime-viewer/config";
import { createRuntimeViewerDataSource } from "./runtime-viewer/data-source";
import { createRuntimeViewerStore } from "./stores/runtime-viewer";
import "element-plus/dist/index.css";
import "element-plus/theme-chalk/dark/css-vars.css";
import "./styles/index.css";

const configResult = parseRuntimeViewerConfig(import.meta.env);
const app = createApp(App, { configResult });
const pinia = createPinia();

app.use(pinia);
app.use(router);
app.use(ElementPlus, { locale: zhCn });

if (configResult.ok) {
  const dataSource = createRuntimeViewerDataSource(configResult.value);
  const runtimeViewerStore = createRuntimeViewerStore(configResult.value, dataSource);
  app.provide(runtimeViewerConfigKey, configResult.value);
  app.provide(runtimeViewerStoreKey, runtimeViewerStore);
}

await router.isReady();
app.mount("#app");
