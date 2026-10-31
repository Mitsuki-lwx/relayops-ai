<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from "vue";
import { api, ApiError } from "./api";
const props = defineProps<{ workspaceId: string; owner: boolean; disabled: boolean }>();
type Queue = { pending: number; claimed: number; retried: number; oldestPendingAt: string | null };
type Overview = {
  observedAt: string; jobs: Record<string, number>; deliveries: Record<string, number>;
  aiOutbox: Queue; deliveryOutbox: Queue; pendingLimit: number;
  recovery: { expiredModelLeases: number; expiredDeliveryLeases: number };
  retention: { enabled: boolean; expiredJobs: number; expiredDeliveries: number; clearedJobs: number; clearedDeliveries: number };
};
type Event = { id: string; operation: string; resourceId: string; actorId: string | null; actorName: string | null; createdAt: string };
type AuditPage = { items: Event[]; nextBeforeId: string | null };
const overview = ref<Overview | null>(null), events = ref<Event[]>([]);
const busy = ref(false), auditBusy = ref(false), error = ref(""), auditError = ref("");
const auditForbidden = ref(false), denied = ref(false), nextBefore = ref<string | null>(null), pageNo = ref(1);
const operation = ref(""), resourceId = ref("");
let applied = { operation: "", resourceId: "" }, epoch = 0, overviewSequence = 0, auditSequence = 0;
let timer: ReturnType<typeof setTimeout> | undefined, controller: AbortController | undefined, auditController: AbortController | undefined;
const labels: Record<string, string> = {
  QUEUED: "排队中", RUNNING: "处理中", SUCCEEDED: "已成功", FAILED: "失败", UNKNOWN: "结果未知", CANCELLED: "已取消",
  PENDING: "等待投递", IN_FLIGHT: "正在投递", RETRY_WAIT: "等待重试", DEAD: "已停止",
};
const auditLabels: Record<string, string> = {
  JOB_ACCEPTED: "任务受理", JOB_SUCCEEDED: "任务成功", JOB_FAILED: "任务失败", JOB_CANCELLED: "取消任务",
  JOB_RERUN_REQUESTED: "明确重跑", RECOVER_UNKNOWN: "恢复为结果未知", RESULT_READ: "读取结果正文",
  DESTINATION_CREATED: "创建目的地", DESTINATION_ENABLED: "启用目的地", DESTINATION_DISABLED: "停用目的地",
  DESTINATION_KEY_REVEALED: "读取签名密钥", DELIVERY_REPLAY_PREVIEWED: "预览补投", DELIVERY_REPLAY_CONFIRMED: "确认补投",
  JOB_PAYLOAD_CLEARED: "清理任务正文", DELIVERY_PAYLOAD_CLEARED: "清理交付正文",
};
const total = (values: Record<string, number>) => Object.values(values).reduce((sum, n) => sum + n, 0);
const pendingTotal = computed(() => overview.value ? overview.value.aiOutbox.pending + overview.value.deliveryOutbox.pending : 0);
const date = (value: string | null) => value === null ? "—" : new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", dateStyle: "short", timeStyle: "medium" }).format(new Date(value));
const prefix = () => `/api/v1/workspaces/${props.workspaceId}`;
function stop() { if (timer) clearTimeout(timer); timer = undefined; }
function clearAudit() { auditSequence++; auditController?.abort(); events.value = []; nextBefore.value = null; pageNo.value = 1; auditBusy.value = false; }
async function loadOverview() {
  if (busy.value || document.hidden) return false;
  const e = epoch, sequence = ++overviewSequence;
  stop(); controller?.abort(); controller = new AbortController(); busy.value = true; error.value = ""; denied.value = false;
  try {
    const data = await api<Overview>(prefix() + "/operations", { signal: controller.signal });
    if (e !== epoch || sequence !== overviewSequence) return false;
    overview.value = data; return true;
  } catch (ex) {
    if (e === epoch && sequence === overviewSequence) {
      overview.value = null; clearAudit();
      denied.value = ex instanceof ApiError && [401, 403, 404].includes(ex.status);
      error.value = ex instanceof Error ? ex.message : "运行概览读取失败";
    }
    return false;
  } finally {
    if (e === epoch && sequence === overviewSequence) {
      busy.value = false;
      if (!document.hidden && !denied.value) timer = setTimeout(() => void loadOverview(), 15000);
    }
  }
}
async function loadAudit(before: string | null = null) {
  if (!props.owner || auditForbidden.value || auditBusy.value || denied.value) return;
  const e = epoch, sequence = ++auditSequence;
  auditController?.abort(); auditController = new AbortController(); auditBusy.value = true; auditError.value = "";
  const query = new URLSearchParams({ limit: "25" });
  if (applied.operation) query.set("operation", applied.operation);
  if (applied.resourceId) query.set("resourceId", applied.resourceId);
  if (before) query.set("beforeId", before);
  try {
    const data = await api<AuditPage>(prefix() + "/audit-events?" + query, { signal: auditController.signal });
    if (e !== epoch || sequence !== auditSequence) return;
    events.value = data.items; nextBefore.value = data.nextBeforeId; pageNo.value = before ? pageNo.value + 1 : 1;
  } catch (ex) {
    if (e === epoch && sequence === auditSequence) {
      events.value = []; nextBefore.value = null;
      auditForbidden.value = ex instanceof ApiError && [401, 403, 404].includes(ex.status);
      auditError.value = ex instanceof Error ? ex.message : "审计读取失败";
    }
  } finally { if (e === epoch && sequence === auditSequence) auditBusy.value = false; }
}
async function refresh() { if (await loadOverview()) await loadAudit(); }
async function filterAudit() {
  if (auditBusy.value) return;
  applied = { operation: operation.value.trim(), resourceId: resourceId.value.trim() };
  await loadAudit();
}
function visibility() {
  if (document.hidden) { stop(); overviewSequence++; controller?.abort(); busy.value = false; }
  else if (overview.value) void loadOverview();
  else void refresh();
}
watch(() => [props.workspaceId, props.owner], () => {
  epoch++; overviewSequence++; stop(); controller?.abort(); clearAudit();
  overview.value = null; busy.value = false; error.value = ""; auditError.value = ""; auditForbidden.value = false; denied.value = false;
  operation.value = ""; resourceId.value = ""; applied = { operation: "", resourceId: "" };
  void refresh();
}, { immediate: true });
document.addEventListener("visibilitychange", visibility);
onUnmounted(() => { epoch++; stop(); controller?.abort(); auditController?.abort(); document.removeEventListener("visibilitychange", visibility); });
</script>

<template>
  <section class="operations-panel" aria-label="空间运行概览">
    <div class="section-heading">
      <p class="muted">全部保留记录 · 每15秒刷新，页面隐藏时暂停</p>
      <button class="quiet" :disabled="busy || disabled" @click="refresh">刷新运行概览</button>
    </div>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="!overview && !error" class="muted">正在读取当前空间运行数据…</p>
    <template v-if="overview">
      <p class="muted">观察时间：{{ date(overview.observedAt) }}</p>
      <div class="operations-grid">
        <section class="panel" aria-label="任务状态统计">
          <div class="section-heading"><h2>任务状态</h2><strong>{{ total(overview.jobs) }} 个任务</strong></div>
          <dl class="operation-counts"><div v-for="(value, state) in overview.jobs" :key="state"><dt>{{ labels[state] ?? state }}</dt><dd>{{ value }}</dd></div></dl>
          <p class="muted">包含全部保留任务，区别于任务队列最多载入50条的统计。</p>
        </section>
        <section class="panel" aria-label="交付状态统计">
          <div class="section-heading"><h2>最新交付状态</h2><strong>{{ total(overview.deliveries) }} 个交付</strong></div>
          <dl class="operation-counts"><div v-for="(value, state) in overview.deliveries" :key="state"><dt>{{ labels[state] ?? state }}</dt><dd>{{ value }}</dd></div></dl>
          <p class="muted">每个交付仅统计最新轮次，历史失败保留在交付详情。</p>
        </section>
        <section class="panel" aria-label="待发布消息">
          <div class="section-heading"><h2>待发布消息</h2><strong>{{ pendingTotal }} / {{ overview.pendingLimit }}</strong></div>
          <p class="muted">达到共享水位会限制新的任务受理。</p>
          <div v-for="(queue, name) in { AI: overview.aiOutbox, 交付: overview.deliveryOutbox }" :key="name" class="outbox-summary">
            <h3>{{ name }} 待发布：{{ queue.pending }}</h3>
            <p>其中发布中 {{ queue.claimed }} · 已有发布尝试 {{ queue.retried }}</p>
            <p class="muted">最早记录：{{ date(queue.oldestPendingAt) }}</p>
          </div>
          <p class="muted">按数据库待发布记录统计，不代表Broker实时队列长度。</p>
        </section>
        <section class="panel" aria-label="恢复与清理">
          <h2>恢复与清理</h2>
          <dl class="operation-counts">
            <div><dt>模型租约待恢复</dt><dd>{{ overview.recovery.expiredModelLeases }}</dd></div>
            <div><dt>交付租约待恢复</dt><dd>{{ overview.recovery.expiredDeliveryLeases }}</dd></div>
            <div><dt>任务正文待清理</dt><dd>{{ overview.retention.expiredJobs }}</dd></div>
            <div><dt>交付正文待清理</dt><dd>{{ overview.retention.expiredDeliveries }}</dd></div>
            <div><dt>任务正文已清理</dt><dd>{{ overview.retention.clearedJobs }}</dd></div>
            <div><dt>交付正文已清理</dt><dd>{{ overview.retention.clearedDeliveries }}</dd></div>
          </dl>
          <p>自动清理：{{ overview.retention.enabled ? "已启用" : "未启用" }}</p>
          <p class="muted">待清理包含仍需结算的到期责任；记录数量不是执行耗时或恢复SLO。</p>
        </section>
      </div>
      <section class="panel audit-panel" aria-label="空间审计">
        <div class="section-heading"><h2>安全审计</h2><span class="muted">仅空间所有者可查询</span></div>
        <p v-if="!owner" class="muted">当前角色可以查看运行概览，审计查询需空间所有者权限。</p>
        <template v-else>
          <p v-if="auditError" role="alert" class="error">{{ auditError }}</p>
          <template v-if="!auditForbidden">
            <form class="audit-filters" @submit.prevent="filterAudit">
              <label>事件类型<input v-model="operation" list="audit-operations" maxlength="40" placeholder="全部类型" :disabled="auditBusy || disabled" /></label>
              <datalist id="audit-operations"><option v-for="(label, code) in auditLabels" :key="code" :value="code">{{ label }}</option></datalist>
              <label>资源ID<input v-model="resourceId" maxlength="36" placeholder="完整UUID，可留空" :disabled="auditBusy || disabled" /></label>
              <button class="secondary" :disabled="auditBusy || disabled">查询审计</button>
            </form>
            <p class="muted">只显示安全元数据，不包含操作原因、正文或密钥。第 {{ pageNo }} 页 · {{ events.length }} 条</p>
            <p v-if="!events.length && !auditBusy" class="muted">当前筛选下没有审计记录。</p>
            <ol class="audit-list"><li v-for="event in events" :key="event.id">
              <div><strong>{{ auditLabels[event.operation] ?? event.operation }}</strong><small>{{ event.operation }}</small></div>
              <time>{{ date(event.createdAt) }}</time>
              <div><span class="muted">资源</span><span>{{ event.resourceId }}</span></div>
              <div><span class="muted">操作者</span><span>{{ event.actorName ?? (event.actorId ?? '系统') }}</span></div>
            </li></ol>
            <div class="audit-pagination">
              <button class="quiet" :disabled="auditBusy || disabled" @click="loadAudit()">返回最新记录</button>
              <button class="secondary" :disabled="auditBusy || disabled || !nextBefore" @click="loadAudit(nextBefore)">更早记录</button>
            </div>
          </template>
        </template>
      </section>
    </template>
  </section>
</template>
