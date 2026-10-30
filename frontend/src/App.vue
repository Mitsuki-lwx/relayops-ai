<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { api, ApiError, refreshCsrf } from "./api";

type Workspace = { id: string; name: string; role: string };
type Job = {
  jobId: string;
  title: string;
  status: string;
  applicationId: string;
  modelProfile: string;
  errorCode: string | null;
  createdAt: string;
  payloadExpiresAt: string;
  modelCalls: number;
  version: number;
  parentJobId: string | null;
};
type Profile = {
  profileId: string;
  promptVersion: string;
  schemaVersion: string;
  billable: boolean;
  creditsPerJob: number;
  provider: string;
  modelName: string;
};
type Command = {
  workspaceId: string;
  jobId: string;
  operation: "cancel" | "retry";
  key: string;
  body: {
    expectedVersion: number;
    reason?: string;
    acknowledgeDuplicateCost?: boolean;
  };
};
type Claim = {
  text: string;
  evidence: { paragraphId: string; quote: string }[];
};
type ResultBody = {
  data: {
    title: string;
    summary: Claim;
    keyPoints: Claim[];
    constraints: Claim[];
    unknowns: string[];
  };
  paragraphs: { paragraphId: string; text: string }[];
};
type Draft = {
  workspaceId: string;
  key: string;
  body: {
    applicationId: string;
    title: string;
    inputText: string;
    destinationIds: string[];
    outputLanguage: string;
    acknowledgeExternalProcessing?: boolean;
  };
};

const me = ref<{ username: string; workspaces: Workspace[] } | null>(null);
const username = ref("owner.a"),
  password = ref(""),
  workspaceId = ref(""),
  applicationId = ref("");
const applications = ref<{ id: string; name: string }[]>([]),
  jobs = ref<Job[]>([]);
const selected = ref<Job | null>(null),
  result = ref<ResultBody | null>(null);
const credits = ref({ dailyLimit: 20, reserved: 0, spent: 0, uncertain: 0 });
const title = ref("技术文档提取"),
  input = ref(""),
  error = ref(""),
  traceId = ref(""),
  busy = ref(true);
const pending = ref<Draft | null>(null),
  resultBusy = ref(false);
const commandPending = ref<Command | null>(null);
const profile = ref<Profile | null>(null);
const externalAcknowledged = ref(false);
const retryReason = ref(""),
  retryAcknowledged = ref(false);
const reasonLength = computed(
  () => Array.from(retryReason.value.trim().normalize("NFC")).length,
);
const workspace = computed(() =>
  me.value?.workspaces.find((w) => w.id === workspaceId.value),
);
const viewer = computed(() => workspace.value?.role === "VIEWER");
const characters = computed(() => Array.from(input.value).length);
const bytes = computed(() => new TextEncoder().encode(input.value).length);
const terminal = (status: string) =>
  ["SUCCEEDED", "FAILED", "UNKNOWN", "CANCELLED"].includes(status);
const states: Record<string, string> = {
  QUEUED: "排队中",
  RUNNING: "处理中",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  UNKNOWN: "结果未知",
  CANCELLED: "已取消",
};
let poll: ReturnType<typeof setTimeout> | undefined;
let expiryTimer: ReturnType<typeof setTimeout> | undefined;
let generation = 0;

function showError(ex: unknown) {
  error.value = ex instanceof Error ? ex.message : "请求失败";
  traceId.value = ex instanceof ApiError ? (ex.traceId ?? "") : "";
  if (ex instanceof ApiError && [403, 404, 410].includes(ex.status))
    result.value = null;
  if (ex instanceof ApiError && ex.status === 401) {
    generation++;
    stopPoll();
    me.value = null;
    selected.value = null;
    result.value = null;
    jobs.value = [];
    credits.value = { dailyLimit: 20, reserved: 0, spent: 0, uncertain: 0 };
    pending.value = null;
    commandPending.value = null;
    input.value = "";
    retryReason.value = "";
    retryAcknowledged.value = false;
    externalAcknowledged.value = false;
    profile.value = null;
  }
}
function path(suffix: string) {
  return `/api/v1/workspaces/${workspaceId.value}${suffix}`;
}
function stopPoll() {
  if (poll) clearTimeout(poll);
  poll = undefined;
}
function schedule() {
  stopPoll();
  if (!document.hidden && selected.value && !terminal(selected.value.status))
    poll = setTimeout(() => {
      void refreshSelected();
    }, 5000);
}
async function refreshList() {
  const current = generation,
    id = workspaceId.value;
  const [list, budget] = await Promise.all([
    api<Job[]>(path("/jobs")),
    api<{ credits: typeof credits.value }>(path("/budget")),
  ]);
  if (current !== generation || id !== workspaceId.value) return;
  jobs.value = list;
  credits.value = budget.credits;
}
async function refreshSelected() {
  if (!selected.value) return;
  const current = generation,
    id = selected.value.jobId;
  try {
    const job = await api<Job>(path(`/jobs/${id}`));
    if (current !== generation || selected.value?.jobId !== id) return;
    selected.value = job;
    await refreshList();
  } catch (ex) {
    showError(ex);
  } finally {
    schedule();
  }
}
async function select(job: Job) {
  generation++;
  stopPoll();
  result.value = null;
  selected.value = job;
  retryReason.value = "";
  retryAcknowledged.value = false;
  error.value = "";
  traceId.value = "";
  await refreshSelected();
}
async function readResult() {
  if (!selected.value) return;
  const current = generation,
    id = selected.value.jobId;
  resultBusy.value = true;
  error.value = "";
  result.value = null;
  try {
    const body = await api<ResultBody>(path(`/jobs/${id}/result`));
    if (current === generation && selected.value?.jobId === id) {
      result.value = body;
      if (expiryTimer) clearTimeout(expiryTimer);
      const remaining =
        new Date(selected.value.payloadExpiresAt).getTime() - Date.now();
      // Browser timers have a signed 32-bit delay ceiling; payload TTL is seven days.
      if (remaining <= 0) result.value = null;
      else
        expiryTimer = setTimeout(
          () => {
            result.value = null;
          },
          Math.min(remaining, 2147483647),
        );
    }
  } catch (ex) {
    showError(ex);
  } finally {
    resultBusy.value = false;
  }
}
async function switchWorkspace() {
  generation++;
  stopPoll();
  selected.value = null;
  result.value = null;
  jobs.value = [];
  profile.value = null;
  externalAcknowledged.value = false;
  error.value = "";
  traceId.value = "";
  const current = generation;
  try {
    const [apps, currentProfile] = await Promise.all([
      api<typeof applications.value>(path("/applications")),
      api<Profile>(path("/model-profile")),
    ]);
    if (current !== generation) return;
    applications.value = apps;
    profile.value = currentProfile;
    applicationId.value = apps[0]?.id ?? "";
    await refreshList();
  } catch (ex) {
    showError(ex);
  }
}
async function loadMe() {
  me.value = await api("/api/v1/me");
  workspaceId.value = me.value!.workspaces[0]?.id ?? "";
  if (workspaceId.value) await switchWorkspace();
}
async function restoreIntents() {
  const actor = me.value!.username;
  pending.value = null;
  commandPending.value = null;
  input.value = "";
  title.value = "技术文档提取";
  const savedDraft = sessionStorage.getItem(`relayops.pending.${actor}`);
  const savedCommand = sessionStorage.getItem(`relayops.command.${actor}`);
  if (savedDraft) {
    pending.value = JSON.parse(savedDraft);
    if (pending.value) {
      title.value = pending.value.body.title;
      input.value = pending.value.body.inputText;
    }
  }
  if (savedCommand) commandPending.value = JSON.parse(savedCommand);
  const target =
    commandPending.value?.workspaceId ?? pending.value?.workspaceId;
  if (
    target &&
    target !== workspaceId.value &&
    me.value!.workspaces.some((w) => w.id === target)
  ) {
    workspaceId.value = target;
    await switchWorkspace();
  }
  if (
    commandPending.value &&
    commandPending.value.workspaceId === workspaceId.value
  ) {
    await select(await api<Job>(path(`/jobs/${commandPending.value.jobId}`)));
  }
}
async function login() {
  busy.value = true;
  error.value = "";
  traceId.value = "";
  try {
    await refreshCsrf();
    await api("/auth/login", {
      method: "POST",
      body: new URLSearchParams({
        username: username.value,
        password: password.value,
      }),
    });
    password.value = "";
    await refreshCsrf();
    await loadMe();
    await restoreIntents();
  } catch (ex) {
    showError(ex);
  } finally {
    busy.value = false;
  }
}
async function logout() {
  try {
    await api("/auth/logout", { method: "POST" });
    if (me.value) {
      sessionStorage.removeItem(`relayops.pending.${me.value.username}`);
      sessionStorage.removeItem(`relayops.command.${me.value.username}`);
    }
    generation++;
    stopPoll();
    me.value = null;
    selected.value = null;
    result.value = null;
    jobs.value = [];
    pending.value = null;
    commandPending.value = null;
    retryReason.value = "";
    retryAcknowledged.value = false;
    input.value = "";
    password.value = "";
    externalAcknowledged.value = false;
    profile.value = null;
    await refreshCsrf();
  } catch (ex) {
    showError(ex);
  }
}
async function submit() {
  if (commandPending.value) return;
  busy.value = true;
  error.value = "";
  traceId.value = "";
  try {
    if (!pending.value) {
      pending.value = {
        workspaceId: workspaceId.value,
        key: crypto.randomUUID(),
        body: {
          applicationId: applicationId.value,
          title: title.value,
          inputText: input.value,
          destinationIds: [],
          outputLanguage: "zh-CN",
          acknowledgeExternalProcessing: externalAcknowledged.value,
        },
      };
      sessionStorage.setItem(
        `relayops.pending.${me.value!.username}`,
        JSON.stringify(pending.value),
      );
    }
    const draft = pending.value;
    const accepted = await api<{ jobId: string }>(
      `/api/v1/workspaces/${draft.workspaceId}/jobs`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": draft.key,
        },
        body: JSON.stringify(draft.body),
      },
    );
    pending.value = null;
    sessionStorage.removeItem(`relayops.pending.${me.value!.username}`);
    externalAcknowledged.value = false;
    await refreshList();
    const job = jobs.value.find((j) => j.jobId === accepted.jobId);
    if (job) await select(job);
  } catch (ex) {
    showError(ex);
  } finally {
    busy.value = false;
  }
}
async function startCommand(operation: "cancel" | "retry") {
  if (
    !selected.value ||
    !me.value ||
    pending.value ||
    commandPending.value ||
    busy.value
  )
    return;
  commandPending.value = {
    workspaceId: workspaceId.value,
    jobId: selected.value.jobId,
    operation,
    key: crypto.randomUUID(),
    body:
      operation === "cancel"
        ? { expectedVersion: selected.value.version }
        : {
            expectedVersion: selected.value.version,
            reason: retryReason.value,
            acknowledgeDuplicateCost: retryAcknowledged.value,
          },
  };
  sessionStorage.setItem(
    `relayops.command.${me.value.username}`,
    JSON.stringify(commandPending.value),
  );
  await sendCommand();
}
async function sendCommand() {
  if (!commandPending.value || !me.value || busy.value) return;
  const command = commandPending.value,
    actor = me.value.username;
  busy.value = true;
  error.value = "";
  traceId.value = "";
  try {
    const accepted = await api<{ jobId: string }>(
      `/api/v1/workspaces/${command.workspaceId}/jobs/${command.jobId}/${command.operation}`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": command.key,
        },
        body: JSON.stringify(command.body),
      },
    );
    sessionStorage.removeItem(`relayops.command.${actor}`);
    commandPending.value = null;
    if (me.value?.username !== actor) return;
    await refreshList();
    await select(
      await api<Job>(
        `/api/v1/workspaces/${command.workspaceId}/jobs/${accepted.jobId}`,
      ),
    );
  } catch (ex) {
    // Keep the original version, body and key. A new form review is a new intention.
    if (
      ex instanceof ApiError &&
      ex.status === 409 &&
      selected.value?.jobId === command.jobId
    )
      await refreshSelected();
    showError(ex);
  } finally {
    busy.value = false;
  }
}
function clearCommand() {
  if (me.value)
    sessionStorage.removeItem(`relayops.command.${me.value.username}`);
  commandPending.value = null;
  retryReason.value = "";
  retryAcknowledged.value = false;
  error.value =
    "已放弃本地操作记录；服务端已经提交的取消或新任务仍然有效，请刷新任务列表核对。";
}
async function viewParent() {
  if (!selected.value?.parentJobId) return;
  try {
    await select(await api<Job>(path(`/jobs/${selected.value.parentJobId}`)));
  } catch (ex) {
    showError(ex);
  }
}
function clearDraft() {
  pending.value = null;
  if (me.value)
    sessionStorage.removeItem(`relayops.pending.${me.value.username}`);
  error.value = "已放弃本地重试记录；若原请求已受理，任务仍会出现在列表中。";
}
function visibility() {
  if (document.hidden) {
    stopPoll();
    result.value = null;
  } else if (selected.value) void refreshSelected();
}
const formatTime = (text: string) =>
  new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    dateStyle: "short",
    timeStyle: "short",
  }).format(new Date(text));
onMounted(async () => {
  document.addEventListener("visibilitychange", visibility);
  try {
    await refreshCsrf();
    await loadMe();
    await restoreIntents();
  } catch (ex) {
    if (!(ex instanceof ApiError && ex.status === 401)) showError(ex);
  } finally {
    busy.value = false;
  }
});
onUnmounted(() => {
  stopPoll();
  if (expiryTimer) clearTimeout(expiryTimer);
  document.removeEventListener("visibilitychange", visibility);
});
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <a class="brand" href="/" aria-label="RelayOps 首页"
        ><span class="brand-mark">R</span> RelayOps
        <span class="stage">开发预览</span></a
      ><span class="top-note">从任务受理，到可核对的结果</span
      ><button v-if="me" class="quiet" :disabled="busy" @click="logout">
        {{ me.username }} · 退出
      </button>
    </header>
    <main>
      <div v-if="error" class="error" role="alert">
        {{ error }}<span v-if="traceId"> · traceId: {{ traceId }}</span>
      </div>
      <div v-if="me && commandPending" class="notice" role="status">
        待确认操作：{{
          commandPending.operation === "cancel" ? "取消排队" : "明确重跑"
        }}。重试沿用原请求、原版本和原幂等键。
        <div class="lifecycle-actions">
          <button class="secondary" :disabled="busy" @click="sendCommand">
            沿用原操作重试
          </button>
          <button class="quiet" :disabled="busy" @click="clearCommand">
            放弃本地操作记录
          </button>
        </div>
      </div>
      <section v-if="!me" class="login-layout">
        <div class="intro">
          <p class="eyebrow">AI TASK OPERATIONS</p>
          <h1>每个任务，<br />都有清楚的去向。</h1>
          <p>提交技术文档，追踪处理状态，查看带原文依据的结构化结果。</p>
          <div class="flow">
            <span>提交文档</span><span>→</span><span>后台处理</span
            ><span>→</span><span>验证结果</span>
          </div>
          <p class="muted">登录后可查看当前模型模式及文档处理范围。</p>
        </div>
        <form class="panel login" @submit.prevent="login">
          <p class="eyebrow">工作台登录</p>
          <h2>开始你的第一个任务</h2>
          <label for="username">账号</label
          ><input
            id="username"
            v-model="username"
            autocomplete="username"
            maxlength="80"
            required
          /><label for="password">密码</label
          ><input
            id="password"
            v-model="password"
            type="password"
            autocomplete="current-password"
            required
          /><button class="primary" :disabled="busy">
            {{ busy ? "登录中…" : "登录工作台 →" }}
          </button>
          <p class="muted">
            本地账号：owner.a / operator.a / viewer.a<br />密码使用启动时配置的演示密码。
          </p>
        </form>
      </section>
      <template v-else>
        <div class="page-heading">
          <div>
            <p class="eyebrow">WORKSPACE / DOCUMENT EXTRACT</p>
            <h1>AI 任务工作台</h1>
            <p class="muted">把文档转成有依据的结果，先跑通每一步。</p>
          </div>
          <div>
            <label for="workspace">当前空间</label
            ><select
              id="workspace"
              v-model="workspaceId"
              :disabled="busy || !!pending || !!commandPending"
              @change="switchWorkspace"
            >
              <option v-for="w in me.workspaces" :key="w.id" :value="w.id">
                {{ w.name }} · {{ w.role }}
              </option>
            </select>
          </div>
        </div>
        <div class="metrics">
          <div>
            <span>当日可用额度</span
            ><strong
              >{{
                credits.dailyLimit -
                credits.reserved -
                credits.spent -
                credits.uncertain
              }}<small> / {{ credits.dailyLimit }}</small></strong
            >
          </div>
          <div>
            <span>预留 / 已使用 / 不确定</span
            ><strong
              >{{ credits.reserved }} <small>/</small> {{ credits.spent }}
              <small>/</small> {{ credits.uncertain }}</strong
            >
          </div>
          <div>
            <span>模型配置</span
            ><strong class="model">{{ profile?.modelName ?? "加载中…" }} <small>{{ profile?.billable ? "供应商模型" : "开发假模型" }}</small></strong>
          </div>
        </div>
        <div class="work-grid">
          <section class="panel composer">
            <div class="section-heading">
              <h2>新建文档任务</h2>
              <span class="tag">仅工作台结果</span>
            </div>
            <p v-if="viewer" class="muted">
              当前为观察者，可查看任务状态，无权提交或读取正文。
            </p>
            <form v-else @submit.prevent="submit">
              <fieldset :disabled="busy || !!pending || !!commandPending">
                <label for="application">应用</label
                ><select id="application" v-model="applicationId" required>
                  <option
                    v-for="app in applications"
                    :key="app.id"
                    :value="app.id"
                  >
                    {{ app.name }}
                  </option></select
                ><label for="title">任务标题</label
                ><input
                  id="title"
                  v-model="title"
                  maxlength="100"
                  required
                /><label for="input"
                  >技术文档 <span class="muted">文本或 Markdown</span></label
                ><textarea
                  id="input"
                  v-model="input"
                  rows="12"
                  required
                  placeholder="粘贴技术说明、接口约束或设计笔记…"
                ></textarea>
                <div
                  class="input-count"
                  :class="{ invalid: characters > 6000 || bytes > 24576 }"
                >
                  {{ characters }} / 6000 字符 · {{ bytes }} / 24576 字节
                </div>
              </fieldset>
              <p class="muted">
                每个任务预留 1 次额度。{{ profile?.billable ? "文档将发送给服务端配置的模型供应商，可能产生费用。" : "假模型只摘录原文，不发起外部模型调用。" }}输入和结果保留 7 天。
              </p>
              <label v-if="profile?.billable" class="risk-check" for="external-ack"><input
                id="external-ack" v-model="externalAcknowledged" type="checkbox" :disabled="busy || !!pending || !!commandPending"
              />我同意将本文档发送给已配置的模型供应商，并理解可能产生费用。</label>
              <div v-if="pending" class="notice">
                <!-- The stored request retains its original external-processing acknowledgement. -->
                已有待确认的提交意图。重试将沿用原始请求及幂等键。
              </div>
              <button
                class="primary"
                :disabled="
                  busy ||
                  !!commandPending ||
                  (!pending &&
                    (!profile ||
                      (profile.billable && !externalAcknowledged) ||
                      !input.trim() ||
                      !applicationId ||
                      characters > 6000 ||
                      bytes > 24576))
                "
              >
                {{
                  busy
                    ? "正在提交…"
                    : pending
                      ? "使用原幂等键重试 →"
                      : "提交任务 →"
                }}</button
              ><button
                v-if="pending"
                type="button"
                class="quiet"
                :disabled="busy"
                @click="clearDraft"
              >
                放弃本地重试记录
              </button>
            </form>
          </section>
          <div class="right-column">
            <section class="panel">
              <div class="section-heading">
                <h2>
                  最近任务 <span class="muted">{{ jobs.length }}</span>
                </h2>
                <button class="quiet" @click="refreshList().catch(showError)">
                  刷新列表 ↻
                </button>
              </div>
              <div v-if="!jobs.length" class="empty">
                <span>↗</span>
                <h3>第一份结果，从一段文档开始</h3>
                <p>提交后，这里将展示任务状态。</p>
              </div>
              <div v-else class="job-list">
                <button
                  v-for="job in jobs"
                  :key="job.jobId"
                  :disabled="busy"
                  class="job-row"
                  :class="{ active: selected?.jobId === job.jobId }"
                  @click="select(job)"
                >
                  <div>
                    <strong>{{ job.title }}</strong
                    ><small>{{ formatTime(job.createdAt) }}</small>
                  </div>
                  <span class="status" :data-status="job.status">{{
                    states[job.status] ?? job.status
                  }}</span>
                </button>
              </div>
            </section>
            <section v-if="selected" class="panel detail">
              <div class="section-heading">
                <h2>任务详情</h2>
                <span class="status" :data-status="selected.status">{{
                  states[selected.status]
                }}</span>
              </div>
              <h3>{{ selected.title }}</h3>
              <p class="job-id">{{ selected.jobId }}</p>
              <button
                v-if="selected.parentJobId"
                class="quiet parent-link"
                :disabled="busy"
                @click="viewParent"
              >
                来源任务：{{ selected.parentJobId }}
              </button>
              <div class="detail-meta">
                <span>调用边界记录：{{ selected.modelCalls }} 次</span
                ><span
                  >正文到期：{{ formatTime(selected.payloadExpiresAt) }}</span
                >
              </div>
              <div v-if="selected.status === 'UNKNOWN'" class="notice">
                请求可能已在供应商执行，本平台未取得可验证结果；再次执行可能产生额外费用。当前版本不提供自动重跑。
              </div>
              <p v-if="selected.errorCode" class="error-text">
                {{ selected.errorCode }}
              </p>
              <p v-if="!terminal(selected.status)" class="muted" role="status">
                后台处理中，每 5 秒刷新一次…
              </p>
              <div
                v-if="selected.status === 'QUEUED' && !viewer"
                class="lifecycle-actions"
              >
                <button
                  class="secondary"
                  :disabled="busy || !!pending || !!commandPending"
                  @click="startCommand('cancel')"
                >
                  取消排队
                </button>
                <span class="muted">成功取消后释放该任务的预留额度。</span>
              </div>
              <form
                v-if="
                  ['FAILED', 'UNKNOWN'].includes(selected.status) && !viewer
                "
                class="rerun-form"
                @submit.prevent="startCommand('retry')"
              >
                <h3>明确重跑</h3>
                <p>创建新的关联任务，保留原任务和原用量记录。</p>
                <dl class="profile-comparison">
                  <div>
                    <dt>原模型配置</dt>
                    <dd>{{ selected.modelProfile }}</dd>
                  </div>
                  <div>
                    <dt>新模型配置</dt>
                    <dd>{{ profile?.profileId ?? "配置加载中…" }}</dd>
                  </div>
                </dl>
                <p class="muted">
                  {{
                    profile?.billable
                      ? "新调用可能产生额外费用。"
                      : "当前使用开发假模型，不收费。"
                  }}新任务预留 {{ profile?.creditsPerJob ?? 1 }} 次额度。
                </p>
                <fieldset :disabled="busy || !!pending || !!commandPending">
                  <label for="retry-reason">重跑原因</label>
                  <textarea
                    id="retry-reason"
                    v-model="retryReason"
                    rows="3"
                    maxlength="600"
                    required
                    placeholder="说明为什么需要再次运行"
                  ></textarea>
                  <p
                    class="input-count"
                    :class="{ invalid: reasonLength > 300 }"
                  >
                    {{ reasonLength }} / 300 字符
                  </p>
                  <label class="risk-check" for="retry-ack"
                    ><input
                      id="retry-ack"
                      v-model="retryAcknowledged"
                      type="checkbox"
                      required
                    />我确认这是一次新的模型调用，同意按新配置处理和外发文档，理解再次执行可能产生额外费用。</label
                  >
                </fieldset>
                <button
                  class="secondary"
                  :disabled="
                    busy ||
                    !!pending ||
                    !!commandPending ||
                    !profile ||
                    !retryAcknowledged ||
                    reasonLength < 1 ||
                    reasonLength > 300
                  "
                >
                  确认创建新任务
                </button>
              </form>
              <template v-if="selected.status === 'SUCCEEDED' && !viewer"
                ><button
                  class="secondary"
                  :disabled="resultBusy"
                  @click="readResult"
                >
                  {{
                    resultBusy
                      ? "读取中…"
                      : result
                        ? "重新授权读取结果"
                        : "查看结果与原文依据"
                  }}
                </button>
                <p class="muted">读取正文会留下审计记录。</p></template
              >
              <div v-if="result" class="result">
                <h3>摘要</h3>
                <p>{{ result.data.summary.text }}</p>
                <h3>关键结论</h3>
                <div
                  v-for="(point, index) in result.data.keyPoints"
                  :key="index"
                  class="claim"
                >
                  <p>{{ point.text }}</p>
                  <details v-for="(evidence, n) in point.evidence" :key="n">
                    <summary>原文依据 · {{ evidence.paragraphId }}</summary>
                    <blockquote>{{ evidence.quote }}</blockquote>
                    <p class="muted">
                      {{
                        result.paragraphs.find(
                          (p) => p.paragraphId === evidence.paragraphId,
                        )?.text
                      }}
                    </p>
                  </details>
                </div>
                <h3>未确定信息</h3>
                <p
                  v-for="unknown in result.data.unknowns"
                  :key="unknown"
                  class="muted"
                >
                  {{ unknown }}
                </p>
                <p class="disclaimer">
                  引用匹配仅说明原文可定位，不代表事实判断完全正确。
                </p>
              </div>
            </section>
            <section v-else class="selection-hint">
              选择一个任务，查看状态与处理结果。
            </section>
          </div>
        </div>
      </template>
    </main>
    <footer>
      RelayOps · 任务工作台 <span>{{ profile?.billable ? "供应商模型 · 注意外发与费用" : "开发预览" }} · 仅工作台结果</span>
    </footer>
  </div>
</template>
