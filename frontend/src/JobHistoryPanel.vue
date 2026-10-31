<script setup lang="ts">
import { onUnmounted, ref, watch } from "vue";
import { api } from "./api";
type Job = { jobId: string; title: string; status: string; applicationId: string; modelProfile: string; errorCode: string | null; createdAt: string; payloadExpiresAt: string; modelCalls: number; version: number; parentJobId: string | null };
type Cursor = { createdAt: string; id: string };
type Page = { items: Job[]; nextCursor: Cursor | null };
const props = defineProps<{ workspaceId: string; disabled: boolean }>();
const emit = defineEmits<{ open: [job: Job] }>();
const query = ref(""), status = ref(""), items = ref<Job[]>([]), next = ref<Cursor | null>(null);
const busy = ref(false), error = ref(""), page = ref(1), applied = ref({ q: "", status: "" });
const labels: Record<string, string> = { QUEUED: "排队中", RUNNING: "处理中", SUCCEEDED: "已完成", FAILED: "失败", UNKNOWN: "结果未知", CANCELLED: "已取消" };
const date = (value: string) => new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", dateStyle: "short", timeStyle: "medium" }).format(new Date(value));
let sequence = 0, controller: AbortController | undefined;
async function load(cursor: Cursor | null = null) {
  const current = ++sequence, workspace = props.workspaceId;
  controller?.abort(); controller = new AbortController(); busy.value = true; error.value = "";
  items.value = []; next.value = null;
  const params = new URLSearchParams({ limit: "25" });
  if (applied.value.q) params.set("q", applied.value.q);
  if (applied.value.status) params.set("status", applied.value.status);
  if (cursor) { params.set("beforeCreatedAt", cursor.createdAt); params.set("beforeId", cursor.id); }
  try {
    const response = await api<Page>(`/api/v1/workspaces/${workspace}/job-history?${params}`, { signal: controller.signal });
    if (current !== sequence || workspace !== props.workspaceId) return;
    items.value = response.items; next.value = response.nextCursor; page.value = cursor ? page.value + 1 : 1;
  } catch (ex) {
    if (current === sequence && workspace === props.workspaceId) error.value = ex instanceof Error ? ex.message : "任务历史查询失败";
  } finally { if (current === sequence) busy.value = false; }
}
function search() { applied.value = { q: query.value.trim().normalize("NFC"), status: status.value }; void load(); }
function reset() { query.value = ""; status.value = ""; search(); }
watch(() => props.workspaceId, () => { query.value = ""; status.value = ""; applied.value = { q: "", status: "" }; page.value = 1; void load(); }, { immediate: true });
onUnmounted(() => { sequence++; controller?.abort(); });
</script>

<template>
  <section class="panel history-panel" aria-label="完整任务历史">
    <form class="history-filters" @submit.prevent="search">
      <label>历史搜索<input v-model="query" type="search" placeholder="搜索标题或任务ID，包含更早记录" :disabled="busy || disabled" /></label>
      <label>历史状态<select v-model="status" :disabled="busy || disabled">
        <option value="">全部状态</option><option value="ACTIVE">处理中（排队与运行）</option><option value="ATTENTION">需关注（失败与未知）</option>
        <option v-for="(label, state) in labels" :key="state" :value="state">{{ label }}</option>
      </select></label>
      <button class="secondary" :disabled="busy || disabled">查询历史</button>
      <button class="quiet" type="button" :disabled="busy || disabled" @click="reset">清空条件</button>
    </form>
    <p class="muted history-scope">已应用：{{ applied.q ? `关键词“${applied.q}”` : '全部标题与ID' }} · {{ labels[applied.status] ?? (applied.status === 'ACTIVE' ? '处理中' : applied.status === 'ATTENTION' ? '需关注' : '全部状态') }}。每页25条，按受理时间与ID倒序。</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-else-if="busy" class="muted" role="status">正在查询当前空间任务历史…</p>
    <div v-else-if="!items.length" class="empty"><h2>没有匹配的历史任务</h2><p>调整搜索词或状态后重新查询。</p></div>
    <template v-else>
      <div class="history-heading"><span>任务与受理时间</span><span>处理状态</span><span>操作</span></div>
      <ol class="history-list"><li v-for="job in items" :key="job.jobId">
        <div><strong>{{ job.title }}</strong><small>{{ date(job.createdAt) }} · {{ job.jobId }}</small></div>
        <span class="status" :data-status="job.status">{{ labels[job.status] ?? job.status }}</span>
        <button class="quiet" :disabled="disabled" :aria-label="`查看任务：${job.title}`" @click="emit('open', job)">查看任务</button>
      </li></ol>
    </template>
    <div class="history-pagination">
      <span class="muted">第 {{ page }} 页 · 本页 {{ items.length }} 条</span>
      <button class="quiet" :disabled="busy || disabled" @click="load()">刷新首屏</button>
      <button class="secondary" :disabled="busy || disabled || !next" @click="load(next)">更早任务</button>
    </div>
    <p class="muted">筛选查询全部保留的任务元数据。任务状态可能变化，刷新首屏可查看最新记录；正文仍受权限与保留期约束。</p>
  </section>
</template>
