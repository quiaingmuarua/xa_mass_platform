<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { Loading, Refresh, Search, Warning } from "@element-plus/icons-vue";

import {
  DiagnosticCodeLoadError,
  loadPlatformDiagnosticCodes
} from "@/diagnostic-codes/client";
import {
  filterDiagnosticCodes,
  flattenDiagnosticCodes,
  type DiagnosticCodeRow
} from "@/diagnostic-codes/model";
import type { PlatformDiagnosticCodes } from "@/diagnostic-codes/schema";

const generationCommand =
  ".\\gradlew.bat :distribution:server:generatePlatformDiagnosticCodes";
const dictionary = ref<PlatformDiagnosticCodes>();
const loading = ref(true);
const loadError = ref<DiagnosticCodeLoadError>();
const ownerFilter = ref("all");
const query = ref("");
let controller: AbortController | undefined;

const rows = computed(() =>
  dictionary.value === undefined ? [] : flattenDiagnosticCodes(dictionary.value)
);
const filteredRows = computed(() =>
  filterDiagnosticCodes(rows.value, ownerFilter.value, query.value)
);
const shortCommit = computed(() => dictionary.value?.gitCommit.slice(0, 12) ?? "");
const failureTitle = computed(() =>
  loadError.value?.kind === "incompatible"
    ? "Dictionary schema is incompatible"
    : "Dictionary is unavailable"
);

async function loadDictionary(): Promise<void> {
  controller?.abort();
  const requestController = new AbortController();
  controller = requestController;
  loading.value = true;
  loadError.value = undefined;
  try {
    dictionary.value = await loadPlatformDiagnosticCodes(requestController.signal);
    ownerFilter.value = "all";
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") return;
    dictionary.value = undefined;
    loadError.value =
      error instanceof DiagnosticCodeLoadError
        ? error
        : new DiagnosticCodeLoadError(
            "unavailable",
            "The current-build diagnostic dictionary could not be loaded.",
            { cause: error }
          );
  } finally {
    if (controller === requestController) loading.value = false;
  }
}

function diagnosticRowKey(row: DiagnosticCodeRow): string {
  return `${row.owner}:${row.code}`;
}

onMounted(() => void loadDictionary());
onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <section class="diagnostic-code-page" data-testid="diagnostic-code-page">
    <header class="worker-page__heading diagnostic-code-heading">
      <div>
        <p class="worker-page__eyebrow">REFERENCE / DIAGNOSTICS</p>
        <h1>Platform Diagnostic Codes</h1>
        <p>Search the producer-local diagnostic namespaces in this exact build.</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadDictionary">
        Reload
      </el-button>
    </header>

    <div v-if="loading" class="panel-state diagnostic-code-state" aria-live="polite">
      <el-icon class="diagnostic-code-state__loading"><Loading /></el-icon>
      <div>
        <strong>Loading current-build dictionary</strong>
        <p>No Runtime API is queried for this reference projection.</p>
      </div>
    </div>

    <div
      v-else-if="loadError !== undefined"
      class="panel-state panel-state--error diagnostic-code-state"
      role="alert"
    >
      <el-icon><Warning /></el-icon>
      <div>
        <strong>{{ failureTitle }}</strong>
        <p>{{ loadError.message }}</p>
        <p>Generate the current projection before starting the frontend:</p>
        <code>{{ generationCommand }}</code>
      </div>
      <el-button :icon="Refresh" @click="loadDictionary">Try again</el-button>
    </div>

    <template v-else-if="dictionary !== undefined">
      <section class="diagnostic-code-build" aria-label="Dictionary build">
        <div>
          <span>Build version</span>
          <strong>{{ dictionary.version }}</strong>
        </div>
        <div>
          <span>Git commit</span>
          <code :title="dictionary.gitCommit">{{ shortCommit }}</code>
        </div>
        <div>
          <span>Definitions</span>
          <strong>{{ rows.length }}</strong>
        </div>
      </section>

      <el-alert
        class="diagnostic-code-notice"
        type="warning"
        :closable="false"
        show-icon
        :title="dictionary.notice"
        description="Codes are looked up by producer owner. This dictionary does not say which API can return a code and does not promise cross-version stability."
      />

      <section class="diagnostic-code-panel">
        <div class="diagnostic-code-toolbar">
          <el-select
            v-model="ownerFilter"
            aria-label="Filter by diagnostic owner"
            class="diagnostic-code-owner-filter"
          >
            <el-option label="All owners" value="all" />
            <el-option
              v-for="owner in dictionary.owners"
              :key="owner.owner"
              :label="owner.owner"
              :value="owner.owner"
            />
          </el-select>
          <el-input
            v-model="query"
            clearable
            :prefix-icon="Search"
            aria-label="Search diagnostic codes"
            placeholder="Search code, symbol, meaning, or owner"
          />
          <span>{{ filteredRows.length }} of {{ rows.length }}</span>
        </div>

        <div v-if="filteredRows.length > 0" class="diagnostic-code-table">
          <el-table
            :data="filteredRows"
            :row-key="diagnosticRowKey"
            stripe
            table-layout="fixed"
          >
            <el-table-column label="Code" prop="code" width="112">
              <template #default="scope">
                <code class="diagnostic-code-number">{{ scope.row.code }}</code>
              </template>
            </el-table-column>
            <el-table-column label="Symbol" prop="name" min-width="250">
              <template #default="scope">
                <code class="diagnostic-code-symbol">{{ scope.row.name }}</code>
              </template>
            </el-table-column>
            <el-table-column label="Meaning" prop="meaning" min-width="300" />
            <el-table-column label="Owner" prop="owner" min-width="220">
              <template #default="scope">
                <el-tag effect="plain" type="info">{{ scope.row.owner }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div v-else class="panel-state panel-state--sample diagnostic-code-empty">
          <strong>No matching diagnostic codes</strong>
          <p>Change the owner filter or search text. An empty query shows all codes.</p>
        </div>
      </section>
    </template>
  </section>
</template>
