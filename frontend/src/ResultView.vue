<script setup lang="ts">
import EvidenceList from "./EvidenceList.vue";
import type { ResultBody } from "./resultTypes";
defineProps<{ result: ResultBody }>();
</script>
<template>
  <div class="result">
    <section class="result-section" data-result-section="summary">
      <h3>摘要</h3>
      <p>{{ result.data.summary.text }}</p>
      <EvidenceList
        :evidence="result.data.summary.evidence"
        :paragraphs="result.paragraphs"
      />
    </section>
    <section class="result-section" data-result-section="keyPoints">
      <h3>
        关键结论
        <span class="count">{{ result.data.keyPoints.length }} 项</span>
      </h3>
      <p v-if="!result.data.keyPoints.length" class="muted">
        未提取到关键结论。
      </p>
      <div
        v-for="(claim, index) in result.data.keyPoints"
        :key="index"
        class="claim"
      >
        <p>{{ claim.text }}</p>
        <EvidenceList
          :evidence="claim.evidence"
          :paragraphs="result.paragraphs"
        />
      </div>
    </section>
    <section class="result-section" data-result-section="constraints">
      <h3>
        约束与要求
        <span class="count">{{ result.data.constraints.length }} 项</span>
      </h3>
      <p v-if="!result.data.constraints.length" class="muted">
        未提取到明确约束。
      </p>
      <div
        v-for="(claim, index) in result.data.constraints"
        :key="index"
        class="claim"
      >
        <p>{{ claim.text }}</p>
        <EvidenceList
          :evidence="claim.evidence"
          :paragraphs="result.paragraphs"
        />
      </div>
    </section>
    <section class="result-section" data-result-section="unknowns">
      <h3>未确定信息</h3>
      <p v-if="!result.data.unknowns.length" class="muted">
        模型未列出额外待确认项。
      </p>
      <ul v-else>
        <li v-for="(unknown, index) in result.data.unknowns" :key="index">
          {{ unknown }}
        </li>
      </ul>
    </section>
    <p class="disclaimer">引用匹配仅说明原文可定位，不代表事实判断完全正确。</p>
  </div>
</template>
