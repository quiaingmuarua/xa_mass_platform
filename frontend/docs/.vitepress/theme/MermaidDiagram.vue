<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useData } from "vitepress";

const props = defineProps<{ encoded: string }>();
const { isDark } = useData();
const element = ref<HTMLElement>();
const error = ref("");
const source = computed(() =>
  new TextDecoder().decode(Uint8Array.from(atob(props.encoded), (c) => c.charCodeAt(0)))
);
let generation = 0;
async function render() {
  const current = ++generation;
  error.value = "";
  try {
    const { default: mermaid } = await import("mermaid");
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: "strict",
      theme: isDark.value ? "dark" : "default"
    });
    const id = `xa-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
    const { svg } = await mermaid.render(id, source.value);
    if (current === generation && element.value) {
      element.value.innerHTML = svg;
      const diagram = element.value.querySelector("svg");
      if (diagram?.viewBox.baseVal.width) {
        diagram.style.width = `${diagram.viewBox.baseVal.width}px`;
        diagram.style.maxWidth = "none";
      }
    }
  } catch (failure) {
    if (current === generation) {
      error.value = failure instanceof Error ? failure.message : "图示渲染失败";
    }
  }
}
onMounted(render);
watch([source, isDark], render);
onBeforeUnmount(() => generation++);
</script>
<template>
  <figure class="xa-diagram">
    <div ref="element" role="img" aria-label="架构关系图"></div>
    <details v-if="error" open>
      <summary>图示渲染失败：{{ error }}</summary>
      <pre>{{ source }}</pre>
    </details>
    <noscript>启用 JavaScript 可查看图示；正文保留相同机制的文字说明。</noscript>
  </figure>
</template>
<style scoped>
.xa-diagram {
  margin: 1.5rem 0;
  overflow-x: auto;
}
.xa-diagram :deep(svg) {
  display: block;
  margin: auto;
}
</style>
