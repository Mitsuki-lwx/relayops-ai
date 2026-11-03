<script setup lang="ts">
import EvidenceList from "./EvidenceList.vue";
import type { ResultBody, VersionDiff } from "./resultTypes";
defineProps<{ diff: VersionDiff; paragraphs: ResultBody["paragraphs"] }>();
const labels = { ADDED: "新增", REMOVED: "删除", MODIFIED: "修改" };
</script>
<template>
  <section class="result-section" data-result-section="versionChanges">
    <h3>版本变化 <span class="count">{{ diff.changes.length }} 项</span></h3>
    <p class="muted">生成建议，待人工确认。变化分类和影响范围可能遗漏，不应直接作为合并依据。</p>
    <p v-if="!diff.changes.length" class="muted">模型未列出变化，不代表两个版本相同。</p>
    <div v-for="(change, index) in diff.changes" :key="index" class="claim">
      <p class="muted">{{ labels[change.kind] }} · {{ change.text }}</p>
      <template v-if="change.before.length">
        <p><strong>基线原文</strong></p>
        <EvidenceList :evidence="change.before" :paragraphs="paragraphs" />
      </template>
      <template v-if="change.after.length">
        <p><strong>修订原文</strong></p>
        <EvidenceList :evidence="change.after" :paragraphs="paragraphs" />
      </template>
    </div>
  </section>
  <section class="result-section" data-result-section="versionImpact">
    <h3>受影响约束 <span class="count">{{ diff.impactedConstraints.length }} 项</span></h3>
    <p v-if="!diff.impactedConstraints.length" class="muted">模型未列出受影响约束。</p>
    <div v-for="(impact, index) in diff.impactedConstraints" :key="index" class="claim">
      <p>{{ impact.text }}</p>
      <EvidenceList :evidence="impact.evidence" :paragraphs="paragraphs" />
    </div>
  </section>
  <section class="result-section" data-result-section="versionCriteria">
    <h3>受影响验收草稿 <span class="count">{{ diff.impactedAcceptanceCriteria.length }} 项</span></h3>
    <p class="muted">草稿待人工确认，不自动执行测试。</p>
    <p v-if="!diff.impactedAcceptanceCriteria.length" class="muted">模型未列出受影响的验收项。</p>
    <div v-for="(criterion, index) in diff.impactedAcceptanceCriteria" :key="index" class="claim">
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
