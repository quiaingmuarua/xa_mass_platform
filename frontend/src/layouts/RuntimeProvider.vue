<script setup lang="ts">
import { provide, onBeforeUnmount } from "vue";
import ConfigErrorView from "@/views/ConfigErrorView.vue";
import RuntimeLayout from "./RuntimeLayout.vue";
import {
  runtimeViewerConfigKey,
  runtimeViewerStoreKey,
  taskCallDebugStoreKey,
  taskManagementStoreKey,
  workerDirectDebugStoreKey,
  workerStatusStoreKey
} from "../runtime-context";
import { parseRuntimeViewerConfig } from "../runtime-viewer/config";
import { createRuntimeViewerDataSource } from "../runtime-viewer/data-source";
import { HttpTaskCallDebugClient } from "../task-call-debug/http-client";
import { HttpFiniteTaskClient } from "../task-management/http-client";
import { createRuntimeViewerStore } from "../stores/runtime-viewer";
import { createTaskCallDebugStore } from "../stores/task-call-debug";
import { createTaskManagementStore } from "../stores/task-management";
import { createWorkerDirectDebugStore } from "../stores/worker-direct-debug";
import { createWorkerStatusStore } from "../stores/worker-status";
import { createWorkerStatusDataSource } from "../worker-status/data-source";
import { HttpWorkerDirectCallClient } from "../worker-direct-call/http-client";

const configResult = parseRuntimeViewerConfig(import.meta.env);
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
  provide(runtimeViewerConfigKey, configResult.value);
  provide(runtimeViewerStoreKey, runtimeViewerStore);
  provide(taskManagementStoreKey, taskManagementStore);
  provide(taskCallDebugStoreKey, taskCallDebugStore);
  provide(workerDirectDebugStoreKey, workerDirectDebugStore);
  provide(workerStatusStoreKey, workerStatusStore);
  onBeforeUnmount(() => {
    runtimeViewerStore.dispose();
    workerStatusStore.dispose();
  });
}
</script>
<template>
  <ConfigErrorView v-if="!configResult.ok" :error="configResult.error" />
  <RuntimeLayout v-else />
</template>
