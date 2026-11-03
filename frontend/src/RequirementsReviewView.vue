<script setup lang="ts">
import EvidenceList from "./EvidenceList.vue";
import type { Review, ResultBody } from "./resultTypes";
defineProps<{ review: Review; paragraphs: ResultBody["paragraphs"] }>();
const labels = { GAP: "疑似缺漏", AMBIGUITY: "疑似歧义", CONFLICT: "疑似冲突" };
</script>
<template>
  <section class="result-section" data-result-section="reviewFindings">
    <h3>需求评审 <span class="count">{{ review.findings.length }} 项待确认</span></h3>
    <p class="muted">模型可能遗漏或误判。关联原文用于定位背景，不证明某项要求缺失。</p>
    <p v-if="!review.findings.length" class="muted">模型未列出问题，不代表需求完整。</p>
    <div v-for="(finding, index) in review.findings" :key="index" class="claim">
      <p class="muted">{{ labels[finding.kind] }}</p>
      <p>{{ finding.text }}</p>
      <p><strong>待澄清：</strong>{{ finding.question }}</p>
      <EvidenceList :evidence="finding.evidence" :paragraphs="paragraphs" />
    </div>
  </section>
  <section class="result-section" data-result-section="acceptanceCriteria">
    <h3>验收草稿 <span class="count">{{ review.acceptanceCriteria.length }} 项</span></h3>
    <p class="muted">生成建议，待人工确认后用于测试规划。</p>
    <p v-if="!review.acceptanceCriteria.length" class="muted">未生成可核对的验收草稿，请补充需求后另行提交。</p>
    <div v-for="(criterion, index) in review.acceptanceCriteria" :key="index" class="claim">
      <h4>{{ index + 1 }}. {{ criterion.title }}</h4>
      <p><strong>前置条件（Given）：</strong>{{ criterion.given }}</p>
      <p><strong>执行操作（When）：</strong>{{ criterion.when }}</p>
      <p><strong>预期行为（Then）：</strong>{{ criterion.then }}</p>
      <p class="muted">待确认假设</p>
      <ul v-if="criterion.assumptions.length"><li v-for="(assumption, i) in criterion.assumptions" :key="i">{{ assumption }}</li></ul>
      <p v-else class="muted">模型未列出额外假设，仍需人工审核。</p>
      <EvidenceList :evidence="criterion.evidence" :paragraphs="paragraphs" />
    </div>
  </section>
</template>
