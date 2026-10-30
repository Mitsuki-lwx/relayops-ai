<script setup lang="ts">
import type { Claim, ResultBody } from "./resultTypes";
const props = defineProps<{
  evidence: Claim["evidence"];
  paragraphs: ResultBody["paragraphs"];
}>();
function source(id: string, quote: string) {
  const text = props.paragraphs.find((p) => p.paragraphId === id)?.text ?? "";
  const at = text.indexOf(quote);
  return at < 0
    ? { before: text || "原文段落不可用", quote: "", after: "" }
    : {
        before: text.slice(0, at),
        quote,
        after: text.slice(at + quote.length),
      };
}
</script>
<template>
  <div class="evidence-list">
    <details
      v-for="(item, index) in evidence"
      :key="`${item.paragraphId}-${index}`"
    >
      <summary>原文依据 · {{ item.paragraphId }}</summary>
      <blockquote>{{ item.quote }}</blockquote>
      <p class="muted">对应原文段落 · {{ item.paragraphId }}</p>
      <p class="source-paragraph">
        {{ source(item.paragraphId, item.quote).before
        }}<mark v-if="source(item.paragraphId, item.quote).quote">{{
          source(item.paragraphId, item.quote).quote
        }}</mark
        >{{ source(item.paragraphId, item.quote).after }}
      </p>
    </details>
  </div>
</template>
