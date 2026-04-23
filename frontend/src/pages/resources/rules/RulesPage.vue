<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Rules</h2>
        <p class="page-subtitle">
          Backend-owned matching and orchestration rules. The first replacement
          slice is read-only so rule semantics stay explicit and server-defined.
        </p>
      </div>
      <el-button @click="loadRules">Refresh</el-button>
    </header>

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="loadRules"
    />

    <el-card v-else class="page-card">
      <section class="metric-grid rule-metrics">
        <div class="metric-tile">
          <div class="metric-label">Rules</div>
          <div class="metric-value">{{ rules.length }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Enabled</div>
          <div class="metric-value">{{ enabledRuleCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Rule types</div>
          <div class="metric-value">{{ ruleTypeCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Evaluators</div>
          <div class="metric-value">{{ registeredEvaluatorCount }}</div>
        </div>
      </section>

      <PageSectionSkeleton v-if="loading" />

      <PageEmptyState
        v-else-if="rules.length === 0"
        description="No rule definitions are currently registered."
      />

      <el-table v-else :data="rules" row-key="ruleId">
        <el-table-column prop="ruleId" label="Rule" min-width="220">
          <template #default="{ row }">
            <div class="row-primary">{{ row.name || row.ruleId }}</div>
            <div class="row-secondary mono">{{ row.ruleId }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="type" label="Type" min-width="150">
          <template #default="{ row }">
            <el-tag type="primary">{{ row.type || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="Enabled" min-width="120">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">
              {{ row.enabled ? 'ENABLED' : 'DISABLED' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="Priority" min-width="110" />
        <el-table-column prop="description" label="Description" min-width="260">
          <template #default="{ row }">
            {{ row.description || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="content" label="Content" min-width="360">
          <template #default="{ row }">
            <pre class="json-inline">{{ row.content || '-' }}</pre>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {getRuleMeta, listRules} from '@/api/rules'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type {RuleListItem, RuleMetaResponse} from '@/types/rules'
import {toErrorMessage} from '@/utils/errors'

const loading = ref(false)
const rules = ref<RuleListItem[]>([])
const ruleMeta = ref<RuleMetaResponse>({
  ruleTypes: [],
  registeredEvaluatorTypes: [],
})
const errorMessage = ref('')

const enabledRuleCount = computed(
  () => rules.value.filter((rule) => rule.enabled).length,
)
const ruleTypeCount = computed(() => ruleMeta.value.ruleTypes.length)
const registeredEvaluatorCount = computed(
  () => ruleMeta.value.registeredEvaluatorTypes.length,
)

async function loadRules(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const [ruleResponse, metaResponse] = await Promise.all([
      listRules(),
      getRuleMeta(),
    ])
    rules.value = ruleResponse.items
    ruleMeta.value = metaResponse
  } catch (error) {
    rules.value = []
    ruleMeta.value = {
      ruleTypes: [],
      registeredEvaluatorTypes: [],
    }
    errorMessage.value = toErrorMessage(error, 'Failed to load rules.')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadRules()
})
</script>

<style scoped>
.rule-metrics {
  margin-bottom: 20px;
}
</style>
