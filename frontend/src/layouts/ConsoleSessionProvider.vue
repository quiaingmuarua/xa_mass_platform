<script setup lang="ts">
import ConsoleLayout from "./ConsoleLayout.vue";
import { computed, provide, onBeforeUnmount } from "vue";
import { getActivePinia } from "pinia";
import { captureTaskCaller } from "@/console-identity";
import { useRoute } from "vue-router";
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
import { createTaskSession, taskSessionKey } from "@/task-workbench/session";
import { createProjectSource } from "@/task-management/projects";

const configResult = parseRuntimeViewerConfig(import.meta.env);
const route = useRoute();
const caller = captureTaskCaller();
const pinia = getActivePinia()!;
const needsRuntime = computed(() =>
  ["Runtime", "Workbench"].includes(String(route.meta.section))
);
if (configResult.ok) {
  const dataSource = createRuntimeViewerDataSource(configResult.value, caller);
  const runtimeViewerStore = createRuntimeViewerStore(configResult.value, dataSource);
  const taskManagementStore = createTaskManagementStore(
    configResult.value.mode,
    new HttpFiniteTaskClient(configResult.value.apiBaseUrl)
  );
  const taskCallClient = new HttpTaskCallDebugClient(configResult.value.apiBaseUrl);
  const taskCallDebugStore = createTaskCallDebugStore(
    taskCallClient,
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
  const taskSession = createTaskSession(
    createProjectSource(configResult.value.apiBaseUrl, configResult.value.mode, caller),
    taskCallClient,
    configResult.value.mode
  );
  provide(taskSessionKey, taskSession);
  onBeforeUnmount(() => {
    taskSession.dispose();
    runtimeViewerStore.dispose();
    workerStatusStore.dispose();
    taskManagementStore.dispose();
    for (const store of [
      runtimeViewerStore,
      taskManagementStore,
      taskCallDebugStore,
      workerDirectDebugStore,
      workerStatusStore
    ]) {
      store.$dispose();
      delete pinia.state.value[store.$id];
    }
  });
}
</script>
<template>
  <ConsoleLayout
    :runtime-error="!configResult.ok && needsRuntime ? configResult.error : undefined"
    ><slot><router-view /></slot
  ></ConsoleLayout>
</template>
