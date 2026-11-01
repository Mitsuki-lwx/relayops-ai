<script setup lang="ts">
import { onUnmounted, ref, watch } from "vue";
import { api, ApiError } from "./api";

const props = defineProps<{ tenantId: string; disabled: boolean }>();
type Event = { id: string; operation: string; resourceId: string; actorId: string; actorName: string | null; createdAt: string };
type Page = { items: Event[]; nextBeforeId: string | null };
const open = ref(false), busy = ref(false), denied = ref(false), error = ref("");
const events = ref<Event[]>([]), next = ref<string | null>(null), pageNo = ref(1);
const operation = ref(""), resource = ref("");
const applied = ref({ operation: "", resource: "" });
const labels: Record<string, string> = { UNIT_CREATED: "创建部门或小组", UNIT_MEMBERSHIP_SET: "变更组织成员", WORKSPACE_CREATED: "创建空间", WORKSPACE_GRANT_SET: "变更空间授权" };
let sequence = 0, controller: AbortController | undefined;
const date = (value: string) => new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", dateStyle: "short", timeStyle: "medium" }).format(new Date(value));
function clear() {
  sequence++; controller?.abort(); controller = undefined;
  events.value = []; next.value = null; pageNo.value = 1; busy.value = false;
}
async function load(before: string | null = null) {
  if (!open.value || busy.value || denied.value || props.disabled || document.hidden) return;
  const id = props.tenantId, current = ++sequence;
  controller?.abort(); controller = new AbortController(); busy.value = true; error.value = "";
  events.value = []; next.value = null;
  const query = new URLSearchParams({ limit: "25" });
  if (applied.value.operation) query.set("operation", applied.value.operation);
  if (applied.value.resource) query.set("resourceId", applied.value.resource);
  if (before) query.set("beforeId", before);
  try {
    const data = await api<Page>(`/api/v1/organizations/${id}/audit-events?${query}`, { signal: controller.signal });
    if (current !== sequence || id !== props.tenantId || document.hidden) return;
    events.value = data.items; next.value = data.nextBeforeId; pageNo.value = before ? pageNo.value + 1 : 1;
  } catch (ex) {
    if (current !== sequence || id !== props.tenantId) return;
    events.value = []; next.value = null;
    denied.value = ex instanceof ApiError && [401, 403, 404].includes(ex.status);
    error.value = ex instanceof Error ? ex.message : "组织审计读取失败";
  } finally { if (current === sequence) busy.value = false; }
}
function filter(reset = false) {
  if (reset) { operation.value = ""; resource.value = ""; }
  applied.value = { operation: operation.value.trim(), resource: resource.value.trim() };
  void load();
}
function toggle() {
  open.value = !open.value; clear(); error.value = ""; denied.value = false;
  if (open.value) void load();
}
function visibility() { clear(); if (!document.hidden) void load(); }
watch(() => props.tenantId, () => {
  clear(); open.value = false; denied.value = false; error.value = "";
  operation.value = ""; resource.value = ""; applied.value = { operation: "", resource: "" };
});
document.addEventListener("visibilitychange", visibility);
onUnmounted(() => { clear(); document.removeEventListener("visibilitychange", visibility); });
</script>

<template>
  <section class="organization-audit" aria-label="公司组织审计">
    <div class="section-heading">
      <div><h3>公司组织审计</h3><p class="muted">核对组织、成员和空间授权变更 · 仅当前公司管理员</p></div>
      <button class="quiet" :disabled="disabled" :aria-expanded="open" @click="toggle">{{ open ? '收起组织审计' : '查看组织审计' }}</button>
    </div>
    <template v-if="open">
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <template v-if="!denied">
        <form class="audit-filters" @submit.prevent="filter()">
          <label>组织事件类型<input v-model="operation" list="organization-audit-operations" maxlength="40" placeholder="全部类型" :disabled="busy || disabled" /></label>
          <datalist id="organization-audit-operations"><option v-for="(label, code) in labels" :key="code" :value="code">{{ label }}</option></datalist>
          <label>组织资源ID<input v-model="resource" maxlength="36" placeholder="完整UUID，可留空" :disabled="busy || disabled" /></label>
          <button class="secondary" :disabled="busy || disabled">查询组织审计</button>
          <button type="button" class="quiet" :disabled="busy || disabled" @click="filter(true)">清空组织筛选</button>
        </form>
        <p class="muted">已应用：{{ applied.operation || '全部类型' }} · {{ applied.resource || '全部资源' }}。第 {{ pageNo }} 页 · {{ events.length }} 条</p>
        <p class="muted">仅安全元数据，不包含变更详情、操作原因或正文；用户名为当前账户名称。</p>
        <p v-if="busy" role="status" class="muted">正在读取组织审计…</p>
        <p v-else-if="!events.length && !error" class="muted">当前筛选下没有组织审计记录。</p>
        <ol class="audit-list" :aria-busy="busy"><li v-for="event in events" :key="event.id">
          <div><strong>{{ labels[event.operation] ?? event.operation }}</strong><small>{{ event.operation }}</small></div>
          <time>{{ date(event.createdAt) }}</time>
          <div><span class="muted">资源</span><span>{{ event.resourceId }}</span></div>
          <div><span class="muted">操作者</span><span>{{ event.actorName ?? event.actorId }}</span></div>
        </li></ol>
        <div class="audit-pagination">
          <button class="quiet" :disabled="busy || disabled" @click="load()">返回最新组织记录</button>
          <button class="secondary" :disabled="busy || disabled || !next" @click="load(next)">更早组织记录</button>
        </div>
      </template>
    </template>
  </section>
</template>
