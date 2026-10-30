<script setup lang="ts">
import { ref, watch, onUnmounted } from "vue";
import { api } from "./api";
import ReplayPanel from "./ReplayPanel.vue";
const props = defineProps<{
  workspaceId: string;
  jobId: string;
  jobStatus: string;
  actor: string;
  canReplay: boolean;
}>();
type Attempt = {
  id: string;
  attemptNo: number;
  status: string;
  httpStatus: number | null;
  errorCode: string | null;
  startedAt: string;
};
type Delivery = {
  id: string;
  destinationName: string;
  runId: string;
  status: string;
  attemptCount: number;
  errorCode: string | null;
  nextAttemptAt: string;
  attempts: Attempt[];
  version: number;
  runNo: number;
  runs: { id: string; runNo: number; status: string; attemptCount: number; errorCode: string | null; nextAttemptAt: string; attempts: Attempt[] }[];
};
const items = ref<Delivery[]>([]),
  error = ref(""),
  busy = ref(false);
let timer: ReturnType<typeof setTimeout> | undefined,
  generation = 0;
const names: Record<string, string> = {
  PENDING: "等待投递",
  IN_FLIGHT: "正在投递",
  RETRY_WAIT: "等待重试",
  SUCCEEDED: "已确认接收",
  DEAD: "已停止",
  STARTED: "请求已领取",
  FAILED: "失败",
  UNKNOWN: "结果未知",
};
const format = (text: string) =>
  new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    dateStyle: "short",
    timeStyle: "medium",
  }).format(new Date(text));
async function load() {
  const g = generation;
  busy.value = true;
  error.value = "";
  if (timer) clearTimeout(timer);
  try {
    const data = await api<Delivery[]>(
      `/api/v1/workspaces/${props.workspaceId}/jobs/${props.jobId}/deliveries`,
    );
    if (g === generation) items.value = data;
  } catch (ex) {
    if (g === generation) {
      items.value = [];
      error.value = ex instanceof Error ? ex.message : "交付状态读取失败";
    }
  } finally {
    if (g === generation) {
      busy.value = false;
      if (
        !document.hidden &&
        (items.value.some((d) => !["SUCCEEDED", "DEAD"].includes(d.status)) ||
          ["QUEUED", "RUNNING"].includes(props.jobStatus))
      )
        timer = setTimeout(load, 3000);
    }
  }
}
watch(
  () => [props.workspaceId, props.jobId, props.jobStatus],
  () => {
    generation++;
    items.value = [];
    void load();
  },
  { immediate: true },
);
onUnmounted(() => {
  generation++;
  if (timer) clearTimeout(timer);
});
</script>
<template>
  <section class="delivery-panel" aria-label="结果交付">
    <div class="section-heading">
      <h3>结果交付</h3>
      <button class="quiet" :disabled="busy" @click="load">刷新交付</button>
    </div>
    <p class="muted">
      模型执行与回调分别结算。接收端须按交付 ID 去重，2xx 仅代表传输确认。
    </p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-else-if="!items.length" class="muted">
      {{
        jobStatus === "SUCCEEDED"
          ? "此任务未选择回调目的地。"
          : "尚无交付记录，成功结果保存后才建立交付。"
      }}
    </p>
    <article v-for="item in items" :key="item.id" class="delivery-item">
      <div class="section-heading">
        <strong>{{ item.destinationName }}</strong
        ><span>{{ names[item.status] ?? item.status }}</span>
      </div>
      <p class="muted">交付 ID：{{ item.id }}</p>
      <div v-for="run in item.runs" :key="run.id" class="delivery-run">
      <h4>第 {{ run.runNo }} 轮 · {{ names[run.status] ?? run.status }}</h4>
      <p>{{ run.attemptCount }} / 6 次尝试<span v-if="run.errorCode"> · {{ run.errorCode }}</span></p>
      <p v-if="run.status === 'RETRY_WAIT'" class="muted">下次最早：{{ format(run.nextAttemptAt) }}</p>
      <ol class="attempt-list">
        <li v-for="attempt in run.attempts" :key="attempt.id">
          <span
            >第 {{ attempt.attemptNo }} 次 ·
            {{ names[attempt.status] ?? attempt.status }}</span
          ><span>{{
            attempt.httpStatus === null
              ? "未取得HTTP确认"
              : `HTTP ${attempt.httpStatus}`
          }}</span
          ><time>{{ format(attempt.startedAt) }}</time
          ><span v-if="attempt.errorCode" class="muted">{{
            attempt.errorCode
          }}</span>
        </li>
      </ol>
      </div>
      <p v-if="item.status === 'DEAD'" class="muted">
        本轮已停止，不会自动继续；有权限的操作者可预览并确认补投。
      </p>
      <ReplayPanel v-if="canReplay" :workspace-id="workspaceId" :job-id="jobId" :delivery-id="item.id" :version="item.version" :status="item.status" :actor="actor" @changed="load" />
    </article>
  </section>
</template>
