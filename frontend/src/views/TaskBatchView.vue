<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import {
  Download,
  Files,
  Timer,
  UploadFilled,
  VideoPlay,
  Warning
} from "@element-plus/icons-vue";

import { useRuntimeViewerStore, useTaskBatchStore } from "@/runtime-context";
import type { TaskBatchRunRecord } from "@/task-batch/types";

const runtimeStore = useRuntimeViewerStore();
const store = useTaskBatchStore();
const workerGroupId = ref("");
const eventCode = ref("");
const payloadKey = ref("");
const selectedFile = ref<File>();
const maximumWaitMillis = ref(30_000);

const availableEntries = computed(() =>
  runtimeStore.entries.filter((entry) => entry.workerGroup !== null)
);
const selectedEntry = computed(() =>
  availableEntries.value.find((entry) => entry.workerGroupId === workerGroupId.value)
);
const eventCodes = computed(() => selectedEntry.value?.workerGroup?.eventCodes ?? []);
const maximumWaitValid = computed(
  () =>
    Number.isInteger(maximumWaitMillis.value) &&
    maximumWaitMillis.value > 0 &&
    maximumWaitMillis.value <= 300_000
);
const canExecute = computed(
  () =>
    store.available &&
    runtimeStore.resourceLoadStatus === "ready" &&
    selectedEntry.value !== undefined &&
    eventCodes.value.includes(eventCode.value) &&
    payloadKey.value.trim().length > 0 &&
    selectedFile.value !== undefined &&
    maximumWaitValid.value &&
    !store.isExecuting
);
const executionLabel = computed(() => {
  if (store.executionPhase === "uploading") {
    return "Uploading input";
  }
  if (store.executionPhase === "running") {
    return "Running Task Batch";
  }
  return "Run Task Batch";
});

watch(
  availableEntries,
  (entries) => {
    if (!entries.some((entry) => entry.workerGroupId === workerGroupId.value)) {
      workerGroupId.value = entries[0]?.workerGroupId ?? "";
    }
  },
  { immediate: true }
);

watch(workerGroupId, () => {
  eventCode.value = eventCodes.value[0] ?? "";
});

onMounted(() => {
  void runtimeStore.initialize();
});

function selectFile(event: Event): void {
  const input = event.target as HTMLInputElement;
  selectedFile.value = input.files?.item(0) ?? undefined;
  store.clearError();
}

function execute(): void {
  if (selectedFile.value === undefined) {
    return;
  }
  void store.execute({
    workerGroupId: workerGroupId.value,
    eventCode: eventCode.value,
    payloadKey: payloadKey.value,
    file: selectedFile.value,
    maximumWaitMillis: maximumWaitMillis.value
  });
}

async function download(run: TaskBatchRunRecord): Promise<void> {
  const output = await store.downloadRun(run.runId);
  if (output === undefined) {
    return;
  }
  const url = URL.createObjectURL(output.blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = output.fileName;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

function formatBytes(bytes: number): string {
  return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KiB`;
}

function formatDuration(millis: number): string {
  return millis < 1000 ? `${millis} ms` : `${(millis / 1000).toFixed(2)} s`;
}
</script>

<template>
  <section class="task-batch-page" data-testid="task-batch-page">
    <header class="worker-page__heading">
      <div>
        <p class="worker-page__eyebrow">LAB / TASK BATCHES</p>
        <h1>Task Batch Lab</h1>
        <p>
          Turn every TXT line into one ordinary TaskItem and keep the JSONL results.
        </p>
      </div>
    </header>

    <el-alert
      v-if="!store.available"
      class="task-batch-profile-alert"
      type="warning"
      :closable="false"
      show-icon
    >
      <template #title>
        Task Batch is available only in API mode with the real
        <code>scenario-workers</code> Profile.
      </template>
    </el-alert>

    <el-alert
      v-else
      class="runtime-semantics"
      type="warning"
      :closable="false"
      show-icon
    >
      <template #title>
        This Lab appends ordinary Items to the selected WorkerGroup's Profile Task.
        WorkerGroup event codes are a selection catalog, not dispatch authorization.
      </template>
    </el-alert>

    <el-alert
      v-if="store.error"
      class="task-batch-error-alert"
      type="error"
      closable
      show-icon
      @close="store.clearError"
    >
      <template #title>{{ store.error.title }}</template>
      <p>{{ store.error.message }}</p>
      <small v-if="store.error.requestId"
        >Request ID: {{ store.error.requestId }}</small
      >
    </el-alert>

    <section class="task-batch-config-panel" aria-labelledby="task-batch-config-title">
      <div class="task-batch-panel-heading">
        <div>
          <span>CONFIGURATION</span>
          <h2 id="task-batch-config-title">Build and run</h2>
        </div>
        <el-icon aria-hidden="true"><VideoPlay /></el-icon>
      </div>

      <div
        v-if="runtimeStore.resourceLoadStatus === 'loading'"
        class="task-batch-inline-state"
      >
        Loading configured WorkerGroups...
      </div>
      <div
        v-else-if="runtimeStore.resourceLoadStatus === 'error'"
        class="task-batch-inline-state task-batch-inline-state--error"
      >
        <span>The configured resource directory is unavailable.</span>
        <el-button size="small" @click="runtimeStore.initialize">Retry</el-button>
      </div>

      <form v-else class="task-batch-form" @submit.prevent="execute">
        <label class="task-batch-field">
          <span>WorkerGroup</span>
          <select
            v-model="workerGroupId"
            :disabled="!store.available || store.isExecuting"
          >
            <option value="" disabled>Select a WorkerGroup</option>
            <option
              v-for="entry in availableEntries"
              :key="entry.workerGroupId"
              :value="entry.workerGroupId"
            >
              {{ entry.workerGroupId }}
            </option>
          </select>
          <small v-if="runtimeStore.missingWorkerGroupIds.length > 0">
            {{ runtimeStore.missingWorkerGroupIds.length }} configured Group descriptors
            are missing.
          </small>
        </label>

        <label class="task-batch-field">
          <span>EventCode</span>
          <select
            v-model="eventCode"
            :disabled="eventCodes.length === 0 || store.isExecuting"
          >
            <option value="" disabled>Select an EventCode</option>
            <option v-for="event in eventCodes" :key="event" :value="event">
              {{ event }}
            </option>
          </select>
          <small>Loaded from the selected WorkerGroup's advisory catalog.</small>
        </label>

        <label class="task-batch-field">
          <span>Payload Key</span>
          <input
            v-model="payloadKey"
            type="text"
            placeholder="value"
            :disabled="!store.available || store.isExecuting"
          />
          <small
            >Each line becomes
            <code>{ "{{ payloadKey || "key" }}": "line" }</code>.</small
          >
        </label>

        <label class="task-batch-field task-batch-file-field">
          <span>TXT input</span>
          <span class="task-batch-file-picker">
            <el-icon aria-hidden="true"><UploadFilled /></el-icon>
            <strong>{{ selectedFile?.name ?? "Choose one .txt file" }}</strong>
            <small v-if="selectedFile">{{ formatBytes(selectedFile.size) }}</small>
          </span>
          <input
            type="file"
            accept=".txt,text/plain"
            :disabled="!store.available || store.isExecuting"
            @change="selectFile"
          />
        </label>

        <label class="task-batch-field">
          <span>Maximum Wait (ms)</span>
          <input
            v-model.number="maximumWaitMillis"
            type="number"
            min="1"
            max="300000"
            step="1"
            :disabled="!store.available || store.isExecuting"
          />
          <small
            >Result loading stops as partial when this deadline is exhausted.</small
          >
        </label>

        <div
          class="task-batch-budget"
          :class="{ 'task-batch-budget--invalid': !maximumWaitValid }"
        >
          <el-icon aria-hidden="true"><Timer /></el-icon>
          <span
            >Maximum wait <strong>{{ formatDuration(maximumWaitMillis) }}</strong></span
          >
        </div>

        <el-button
          class="task-batch-submit"
          native-type="submit"
          type="primary"
          :icon="VideoPlay"
          :loading="store.isExecuting"
          :disabled="!canExecute"
          data-testid="execute-task-batch"
        >
          {{ executionLabel }}
        </el-button>
      </form>
    </section>

    <section
      class="task-batch-history-panel"
      aria-labelledby="task-batch-history-title"
    >
      <div class="task-batch-panel-heading">
        <div>
          <span>THIS BROWSER SESSION</span>
          <h2 id="task-batch-history-title">Completed runs</h2>
        </div>
        <span class="task-batch-history-count">{{ store.runs.length }} runs</span>
      </div>

      <div v-if="store.runs.length === 0" class="task-batch-empty-history">
        <el-icon aria-hidden="true"><Files /></el-icon>
        <div>
          <strong>No Task Batch has completed in this browser session.</strong>
          <p>Refreshing clears this table but does not delete Server files.</p>
        </div>
      </div>

      <div v-else class="task-batch-table-wrap">
        <el-table :data="store.runs" row-key="runId" class="task-batch-table">
          <el-table-column label="RUN" min-width="225">
            <template #default="{ row }"
              ><code>{{ row.runId }}</code></template
            >
          </el-table-column>
          <el-table-column label="WORKER GROUP" min-width="245">
            <template #default="{ row }"
              ><code>{{ row.workerGroupId }}</code></template
            >
          </el-table-column>
          <el-table-column label="EVENT / PAYLOAD" min-width="205">
            <template #default="{ row }">
              <div class="task-batch-identity-cell">
                <code>{{ row.eventCode }}</code
                ><span>{{ row.payloadKey }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="STATUS" width="105">
            <template #default="{ row }">
              <el-tag
                :type="row.status === 'partial' ? 'warning' : 'success'"
                effect="plain"
                size="small"
              >
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="RESULTS" width="118" align="center">
            <template #default="{ row }">
              {{ row.resultCount }} / {{ row.inputCount }}
              <small v-if="row.remainingCount > 0">{{ row.remainingCount }} left</small>
            </template>
          </el-table-column>
          <el-table-column label="ROUNDS" width="86" align="center">
            <template #default="{ row }">{{ row.loadRounds }}</template>
          </el-table-column>
          <el-table-column label="DURATION" width="105" align="right">
            <template #default="{ row }">{{
              formatDuration(row.durationMillis)
            }}</template>
          </el-table-column>
          <el-table-column label="ACTION" width="110" fixed="right" align="right">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :icon="Download"
                :loading="store.downloadingFile === row.outputFile"
                @click="download(row)"
                >Download</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </div>

      <footer v-if="store.runs.length > 0" class="task-batch-history-note">
        <el-icon aria-hidden="true"><Warning /></el-icon>
        Session memory is temporary; published input and output files remain in the Lab
        directory.
      </footer>
    </section>
  </section>
</template>
