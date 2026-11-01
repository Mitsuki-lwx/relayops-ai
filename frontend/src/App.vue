<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { api, ApiError, refreshCsrf } from "./api";
import OrganizationPanel from "./OrganizationPanel.vue";
import AppIcon from "./AppIcon.vue";
import ResultView from "./ResultView.vue";
import DestinationPanel, { type Destination } from "./DestinationPanel.vue";
import DeliveryPanel from "./DeliveryPanel.vue";
import OperationsPanel from "./OperationsPanel.vue";
import JobHistoryPanel from "./JobHistoryPanel.vue";
import { jobError } from "./jobErrors";
import type { ResultBody } from "./resultTypes";
import { analysisReport, reportFilename } from "./analysisReport";

type Workspace = {
  id: string;
  name: string;
  role: string;
  tenantId: string;
  tenantName: string;
};
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
const exporting = ref(false), reportNotice = ref("");
const commandPending = ref<Command | null>(null);
const profile = ref<Profile | null>(null);
const externalAcknowledged = ref(false);
const destinations = ref<Destination[]>([]),
  selectedDestinations = ref<string[]>([]);
async function refreshDestinations() {
  const id = workspaceId.value;
  const items = await api<Destination[]>(
    `/api/v1/workspaces/${id}/destinations`,
  );
  if (id === workspaceId.value) destinations.value = items;
}
type View = "tasks" | "new" | "organization" | "destinations" | "operations" | "history";
const activeView = ref<View>("tasks"),
  menuOpen = ref(false),
  hasOrganization = ref(false);
const search = ref(""),
  statusFilter = ref("all");
const visibleJobs = computed(() =>
  jobs.value.filter((job) => {
    const matches = `${job.title} ${job.jobId}`
      .toLowerCase()
      .includes(search.value.trim().toLowerCase());
    return (
      matches &&
      (statusFilter.value === "all" ||
        (statusFilter.value === "active"
          ? !terminal(job.status)
          : statusFilter.value === "attention"
            ? ["FAILED", "UNKNOWN"].includes(job.status)
            : job.status === statusFilter.value))
    );
  }),
);
const activeJobs = computed(
  () => jobs.value.filter((job) => !terminal(job.status)).length,
);
const attentionJobs = computed(
  () =>
    jobs.value.filter((job) => ["FAILED", "UNKNOWN"].includes(job.status))
      .length,
);
const usedCredits = computed(
  () => credits.value.reserved + credits.value.spent + credits.value.uncertain,
);
const usagePercent = computed(() =>
  Math.min(
    100,
    Math.max(
      0,
      (100 * usedCredits.value) / Math.max(1, credits.value.dailyLimit),
    ),
  ),
);
function navigate(view: View) {
  if (view !== "tasks") clearResult();
  activeView.value = view;
  menuOpen.value = false;
  window.location.hash = view;
}
function syncView() {
  const view = window.location.hash.slice(1);
  activeView.value = ["new", "organization", "destinations", "operations", "history"].includes(view)
    ? (view as View)
    : "tasks";
  if (activeView.value !== "tasks") clearResult();
  menuOpen.value = false;
}
function backToList() {
  generation++;
  stopPoll();
  selected.value = null;
  clearResult();
}
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
let resultSequence = 0, resultController: AbortController | undefined;

function clearResult() {
  resultSequence++;
  resultController?.abort(); resultController = undefined;
  result.value = null; resultBusy.value = false; exporting.value = false; reportNotice.value = "";
  if (expiryTimer) clearTimeout(expiryTimer);
  expiryTimer = undefined;
}

function showError(ex: unknown) {
  error.value = ex instanceof Error ? ex.message : "请求失败";
  traceId.value = ex instanceof ApiError ? (ex.traceId ?? "") : "";
  if (ex instanceof ApiError && [403, 404, 410].includes(ex.status))
    clearResult();
  if (ex instanceof ApiError && ex.status === 401) {
    menuOpen.value = false;
    generation++;
    stopPoll();
    me.value = null;
    selected.value = null;
    clearResult();
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
  clearResult();
  selected.value = job;
  retryReason.value = "";
  retryAcknowledged.value = false;
  error.value = "";
  traceId.value = "";
  await refreshSelected();
}
async function openHistory(job: Job) {
  const current = generation, space = workspaceId.value;
  busy.value = true;
  try {
    const latest = await api<Job>(path(`/jobs/${job.jobId}`));
    if (current !== generation || space !== workspaceId.value) return;
    navigate("tasks");
    await select(latest);
    if (error.value) backToList();
  } catch (ex) {
    if (current === generation && space === workspaceId.value) {
      navigate("tasks"); backToList(); showError(ex);
    }
  } finally { busy.value = false; }
}
async function readResult(download = false) {
  if (!selected.value || resultBusy.value || viewer.value || selected.value.status !== "SUCCEEDED" || activeView.value !== "tasks") return;
  clearResult();
  const current = generation, sequence = resultSequence, space = workspaceId.value,
    id = selected.value.jobId, actor = me.value?.username;
  const isCurrent = () => current === generation && sequence === resultSequence && workspaceId.value === space && me.value?.username === actor && selected.value?.jobId === id && activeView.value === "tasks" && !document.hidden && !viewer.value;
  resultController = new AbortController();
  resultBusy.value = true; exporting.value = download; error.value = ""; traceId.value = "";
  try {
    const body = await api<ResultBody>(path(`/jobs/${id}/result`), { signal: resultController.signal });
    if (!isCurrent()) return;
    const job = selected.value!, remaining = new Date(job.payloadExpiresAt).getTime() - Date.now();
    if (!(remaining > 0)) throw new ApiError(410, "PAYLOAD_EXPIRED", "正文已过期，无法读取或导出");
    result.value = body;
    // Browser timers have a signed 32-bit delay ceiling; payload TTL is seven days.
    expiryTimer = setTimeout(clearResult, Math.min(remaining, 2147483647));
    if (download) {
      const report = analysisReport(job, body, new Date());
      if (!isCurrent()) return;
      if (new Date(job.payloadExpiresAt).getTime() <= Date.now()) throw new ApiError(410, "PAYLOAD_EXPIRED", "正文已过期，无法导出");
      const url = URL.createObjectURL(new Blob([report], { type: "text/markdown;charset=utf-8" }));
      const link = document.createElement("a");
      link.href = url; link.download = reportFilename(id); link.hidden = true;
      document.body.appendChild(link);
      try { link.click(); reportNotice.value = "已开始下载分析报告，请在浏览器下载记录中确认保存。"; }
      finally { link.remove(); setTimeout(() => URL.revokeObjectURL(url), 0); }
    }
  } catch (ex) {
    if (isCurrent()) { clearResult(); showError(ex); }
  } finally {
    if (sequence === resultSequence) { resultBusy.value = false; exporting.value = false; }
  }
}
async function switchWorkspace() {
  generation++;
  stopPoll();
  selected.value = null;
  clearResult();
  jobs.value = [];
  profile.value = null;
  destinations.value = [];
  selectedDestinations.value = [];
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
    await refreshDestinations();
  } catch (ex) {
    showError(ex);
  }
}
async function loadMe() {
  me.value = await api("/api/v1/me");
  workspaceId.value = me.value!.workspaces[0]?.id ?? "";
  if (workspaceId.value) await switchWorkspace();
}
async function refreshAccess() {
  busy.value = true;
  try {
    me.value = await api("/api/v1/me");
    if (!me.value!.workspaces.some((w) => w.id === workspaceId.value))
      workspaceId.value = me.value!.workspaces[0]?.id ?? "";
    await switchWorkspace();
  } catch (ex) {
    showError(ex);
  } finally {
    busy.value = false;
  }
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
  selectedDestinations.value =
    pending.value?.workspaceId === workspaceId.value
      ? [...(pending.value.body.destinationIds ?? [])]
      : [];
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
    navigate(pending.value ? "new" : "tasks");
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
    clearResult();
    jobs.value = [];
    pending.value = null;
    commandPending.value = null;
    retryReason.value = "";
    retryAcknowledged.value = false;
    input.value = "";
    password.value = "";
    navigate("tasks");
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
          destinationIds: [...selectedDestinations.value],
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
    navigate("tasks");
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
    clearResult();
  } else if (selected.value) void refreshSelected();
}
const formatTime = (text: string) =>
  new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    dateStyle: "short",
    timeStyle: "short",
  }).format(new Date(text));
onMounted(async () => {
  syncView();
  window.addEventListener("hashchange", syncView);
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
  generation++; clearResult();
  window.removeEventListener("hashchange", syncView);
  stopPoll();
  if (expiryTimer) clearTimeout(expiryTimer);
  document.removeEventListener("visibilitychange", visibility);
});
</script>

<template>
  <div
    class="shell"
    :class="{ 'is-authenticated': !!me, 'navigation-open': menuOpen }"
  >
    <aside v-if="me" class="sidebar" aria-label="主导航">
      <a
        class="brand"
        href="#tasks"
        @click="navigate('tasks')"
        aria-label="RelayOps 首页"
        ><svg
          class="brand-mark"
          viewBox="0 0 32 32"
          fill="none"
          aria-hidden="true"
        >
          <path
            d="M7 8h9a9 9 0 0 1 9 9v7M7 8v16h9"
            stroke="currentColor"
            stroke-width="2.5"
          />
          <circle cx="7" cy="8" r="3" fill="currentColor" />
          <circle cx="25" cy="24" r="3" fill="currentColor" /></svg
        >RelayOps<span class="stage">开发版</span></a
      >
      <button
        class="icon-button menu-toggle"
        :aria-expanded="menuOpen"
        :aria-label="menuOpen ? '收起导航' : '展开导航'"
        @click="menuOpen = !menuOpen"
      >
        <AppIcon name="menu" />
      </button>
      <nav class="sidebar-nav">
        <span class="nav-label">工作空间</span>
        <button
          :aria-current="activeView === 'tasks' ? 'page' : undefined"
          @click="navigate('tasks')"
        >
          <AppIcon name="tasks" />任务队列<span
            v-if="attentionJobs"
            class="nav-count"
            >{{ attentionJobs }} 待关注</span
          >
        </button>
        <button
          :aria-current="activeView === 'new' ? 'page' : undefined"
          :disabled="viewer || busy || !!commandPending"
          @click="navigate('new')"
        >
          <AppIcon name="plus" />新建任务
        </button>
        <template v-if="hasOrganization"
          ><span class="nav-label">管理</span
          ><button
            :aria-current="activeView === 'organization' ? 'page' : undefined"
            :disabled="busy || !!pending || !!commandPending"
            @click="navigate('organization')"
          >
            <AppIcon name="team" />组织管理
          </button></template
        >
        <button
          :aria-current="activeView === 'destinations' ? 'page' : undefined"
          :disabled="busy || !!pending || !!commandPending"
          @click="navigate('destinations')"
        >
          <AppIcon name="arrow" />回调目的地
        </button>
        <button :aria-current="activeView === 'operations' ? 'page' : undefined" :disabled="busy || !!pending || !!commandPending" @click="navigate('operations')"><AppIcon name="tasks" />运行概览</button>
      </nav>
      <div class="sidebar-bottom">
        <span class="avatar">{{ me.username.slice(0, 1).toUpperCase() }}</span>
        <div>
          <strong>{{ me.username }}</strong
          ><span>{{ workspace?.role ?? "公司成员" }}</span>
        </div>
        <button
          class="icon-button"
          :disabled="busy"
          aria-label="退出登录"
          @click="logout"
        >
          <AppIcon name="logout" />
        </button>
      </div>
    </aside>
    <div class="app-body">
      <header class="topbar">
        <a v-if="!me" class="brand" href="/" aria-label="RelayOps 首页"
          ><svg
            class="brand-mark"
            viewBox="0 0 32 32"
            fill="none"
            aria-hidden="true"
          >
            <path
              d="M7 8h9a9 9 0 0 1 9 9v7M7 8v16h9"
              stroke="currentColor"
              stroke-width="2.5"
            />
            <circle cx="7" cy="8" r="3" fill="currentColor" />
            <circle cx="25" cy="24" r="3" fill="currentColor" /></svg
          >RelayOps</a
        >
        <template v-else
          ><div class="breadcrumb">
            <span>{{ workspace?.tenantName ?? "RelayOps" }}</span
            ><span aria-hidden="true">/</span
            ><strong>{{
              activeView === "history" ? "任务历史" : activeView === "operations" ? "运行概览" : activeView === "destinations"
                ? "回调目的地"
                : activeView === "organization"
                  ? "组织管理"
                  : activeView === "new"
                    ? "新建文档"
                    : "任务队列"
            }}</strong>
          </div>
          <div class="scope-control">
            <label for="workspace">当前空间</label
            ><select
              id="workspace"
              v-model="workspaceId"
              :disabled="busy || !!pending || !!commandPending"
              @change="switchWorkspace"
            >
              <option v-for="w in me.workspaces" :key="w.id" :value="w.id">
                {{ w.tenantName }} / {{ w.name }} · {{ w.role }}
              </option>
            </select>
          </div>
          <span class="environment"
            ><i></i>{{ profile?.billable ? "供应商模型" : "本地开发" }}</span
          ></template
        >
      </header>
      <main class="canvas">
        <div v-if="error" class="error" role="alert">
          {{ error }}<span v-if="traceId"> · traceId: {{ traceId }}</span>
        </div>
        <div v-if="me && commandPending" class="notice" role="status">
          待确认操作：{{
            commandPending.operation === "cancel" ? "取消排队" : "明确重跑"
          }}。重试沿用原请求、原版本和原幂等键。
          <div class="lifecycle-actions">
            <button class="secondary" :disabled="busy" @click="sendCommand">
              沿用原操作重试</button
            ><button class="quiet" :disabled="busy" @click="clearCommand">
              放弃本地操作记录
            </button>
          </div>
        </div>
        <section v-if="!me" class="login-layout">
          <div class="intro">
            <span class="login-product">RelayOps / 文档任务平台</span>
            <h1>让每次处理，<br />都能回到原文。</h1>
            <p>
              从一段技术文档开始，提交任务、跟踪处理，<br
                class="desktop-only"
              />在结果中核对每一条依据。
            </p>
            <div class="login-process">
              <div><span>提交</span><strong>技术文档</strong></div>
              <AppIcon name="arrow" />
              <div><span>处理</span><strong>结构化提取</strong></div>
              <AppIcon name="arrow" />
              <div><span>核对</span><strong>原文依据</strong></div>
            </div>
            <p class="login-footnote">
              空间授权控制访问范围，处理记录保留每次任务的去向。
            </p>
          </div>
          <form class="panel login" @submit.prevent="login">
            <h2>登录工作台</h2>
            <p class="muted">使用你的 RelayOps 账号继续。</p>
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
              {{ busy ? "登录中…" : "登录工作台" }}<AppIcon name="arrow" />
            </button>
            <p class="login-help">
              本地账号：owner.a / operator.a / viewer.a<br />密码使用启动时配置的演示密码。
            </p>
          </form>
        </section>
        <template v-else>
          <div class="page-heading">
            <div>
              <h1>
                {{
                  activeView === "history" ? "完整任务历史" : activeView === "operations" ? "空间运行概览" : activeView === "destinations"
                    ? "回调目的地"
                    : activeView === "organization"
                      ? "公司与组织管理"
                      : activeView === "new"
                        ? "新建文档任务"
                        : "AI 任务工作台"
                }}
              </h1>
              <p class="muted">
                {{
                  activeView === "history" ? "搜索当前空间的历史任务，继续查看处理与结果。" : activeView === "operations" ? "核对全部保留任务、当前交付与消息责任。" : activeView === "destinations"
                    ? "配置受控接收端，交付结果并跟踪每次尝试。"
                    : activeView === "organization"
                      ? "管理部门、小组和空间访问权限。"
                      : activeView === "new"
                        ? "提交文档，获得可追溯原文的结构化结果。"
                        : `已载入任务中：${activeJobs} 个处理中，${attentionJobs} 个需关注。`
                }}
              </p>
            </div>
            <button
              v-if="activeView === 'tasks' && !viewer"
              class="primary new-task"
              :disabled="busy || !!commandPending"
              @click="navigate('new')"
            >
              <AppIcon name="plus" />新建文档</button
            ><button
              v-else-if="activeView === 'new' || activeView === 'history'"
              class="quiet"
              @click="navigate('tasks')"
            >
              <AppIcon name="back" />返回任务队列
            </button>
          </div>
          <OrganizationPanel
            v-show="activeView === 'organization'"
            :active="activeView === 'organization'"
            :disabled="busy || !!pending || !!commandPending"
            :tenant-id="workspace?.tenantId"
            @available="hasOrganization = $event"
            @changed="refreshAccess"
          />
          <OperationsPanel v-if="activeView === 'operations'" :workspace-id="workspaceId" :owner="workspace?.role === 'OWNER'" :disabled="busy || !!pending || !!commandPending" />
          <JobHistoryPanel v-if="activeView === 'history'" :workspace-id="workspaceId" :disabled="busy || !!pending || !!commandPending" @open="openHistory" />
          <DestinationPanel
            v-if="activeView === 'destinations'"
            :workspace-id="workspaceId"
            :owner="workspace?.role === 'OWNER'"
            :disabled="busy || !!pending || !!commandPending"
            @changed="refreshDestinations"
          />
          <template
            v-if="
              activeView !== 'organization' && activeView !== 'destinations' && activeView !== 'operations' && activeView !== 'history'
            "
          >
            <div class="metrics">
              <div class="usage-metric">
                <span>当日可用额度</span
                ><strong
                  >{{ credits.dailyLimit - usedCredits
                  }}<small> / {{ credits.dailyLimit }} 次</small></strong
                >
                <div
                  class="usage-bar"
                  :aria-label="`当日已占用 ${usedCredits} / ${credits.dailyLimit} 次额度`"
                >
                  <span :style="{ width: usagePercent + '%' }"></span>
                </div>
              </div>
              <div>
                <span>预留 / 已使用 / 不确定</span
                ><strong
                  >{{ credits.reserved }}<small> / </small>{{ credits.spent
                  }}<small> / </small>{{ credits.uncertain }}</strong
                >
                <p>按当前空间统计</p>
              </div>
              <div>
                <span>模型配置</span
                ><strong class="model">{{
                  profile?.modelName ?? "加载中…"
                }}</strong
                ><small>{{
                  profile?.billable
                    ? "外发文档前需确认费用"
                    : "开发假模型 · 不发起外部调用"
                }}</small>
              </div>
            </div>
            <div v-if="activeView === 'new'" class="compose-grid">
              <section class="panel composer">
                <div class="section-heading">
                  <h2>文档内容</h2>
                  <span class="tag">文本 / Markdown</span>
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
                      >技术文档
                      <span class="muted">文本或 Markdown</span></label
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
                  <fieldset
                    class="destination-choice"
                    :disabled="busy || !!pending || !!commandPending"
                  >
                    <legend>
                      结果回调 <span class="muted">可选，最多3个</span>
                    </legend>
                    <p class="muted">
                      选中的目的地会接收结构化结果，不包含输入全文；不选则仅在工作台查看。
                    </p>
                    <label
                      v-for="destination in destinations.filter(
                        (d) => d.enabled,
                      )"
                      :key="destination.id"
                      class="risk-check"
                      ><input
                        v-model="selectedDestinations"
                        type="checkbox"
                        :value="destination.id"
                        :disabled="
                          selectedDestinations.length >= 3 &&
                          !selectedDestinations.includes(destination.id)
                        "
                      />{{ destination.name }}</label
                    >
                    <p
                      v-if="!destinations.some((d) => d.enabled)"
                      class="muted"
                    >
                      暂无启用的回调目的地，可在侧栏配置。
                    </p>
                  </fieldset>
                  <p class="muted">
                    每个任务预留 1 次额度。{{
                      profile?.billable
                        ? "文档将发送给服务端配置的模型供应商，可能产生费用。"
                        : "假模型只摘录原文，不发起外部模型调用。"
                    }}输入和结果保留 7 天。
                  </p>
                  <label
                    v-if="profile?.billable"
                    class="risk-check"
                    for="external-ack"
                    ><input
                      id="external-ack"
                      v-model="externalAcknowledged"
                      type="checkbox"
                      :disabled="busy || !!pending || !!commandPending"
                    />我同意将本文档发送给已配置的模型供应商，并理解可能产生费用。</label
                  >
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
              <aside class="submission-guide">
                <h2>提交前核对</h2>
                <dl>
                  <div>
                    <dt>处理范围</dt>
                    <dd>{{ workspace?.name }}</dd>
                  </div>
                  <div>
                    <dt>当前角色</dt>
                    <dd>{{ workspace?.role }}</dd>
                  </div>
                  <div>
                    <dt>额度预留</dt>
                    <dd>每任务 {{ profile?.creditsPerJob ?? 1 }} 次</dd>
                  </div>
                  <div>
                    <dt>正文与结果</dt>
                    <dd>保留 7 天</dd>
                  </div>
                </dl>
                <p>
                  摘要、关键结论和约束附带原文引用。结果未知时，需要人工确认后才能创建新的调用。
                </p>
                <p v-if="pending" class="notice">
                  已有提交待确认，请沿用原始请求恢复。
                </p>
              </aside>
            </div>
            <div
              v-else
              class="work-grid"
              :class="{ 'has-selection': !!selected }"
            >
              <section class="panel task-panel">
                <div class="section-heading">
                  <h2>
                    最近任务 <span class="count">{{ jobs.length }}</span>
                  </h2>
                  <button
                    class="quiet"
                    :disabled="busy"
                    @click="refreshList().catch(showError)"
                  >
                    <AppIcon name="refresh" />刷新列表
                  </button>
                </div>
                <div class="task-tabs" aria-label="任务状态筛选">
                  <button
                    :aria-pressed="statusFilter === 'all'"
                    @click="statusFilter = 'all'"
                  >
                    全部 <span>{{ jobs.length }}</span></button
                  ><button
                    :aria-pressed="statusFilter === 'active'"
                    @click="statusFilter = 'active'"
                  >
                    处理中 <span>{{ activeJobs }}</span></button
                  ><button
                    :aria-pressed="statusFilter === 'attention'"
                    @click="statusFilter = 'attention'"
                  >
                    需关注 <span>{{ attentionJobs }}</span>
                  </button>
                </div>
                <div class="search-field">
                  <AppIcon name="search" /><input
                    v-model="search"
                    type="search"
                    aria-label="搜索任务"
                    placeholder="搜索标题或任务 ID"
                  />
                </div>
                <div class="list-heading">
                  <span>任务名称</span><span>处理状态</span>
                </div>
                <div v-if="!visibleJobs.length" class="empty">
                  <h3>
                    {{ jobs.length ? "没有匹配的任务" : "还没有文档任务" }}
                  </h3>
                  <p>
                    {{
                      jobs.length
                        ? "调整搜索词或状态筛选。"
                        : "新建任务后，在这里跟踪处理与结果。"
                    }}
                  </p>
                </div>
                <div v-else class="job-list">
                  <button
                    v-for="job in visibleJobs"
                    :key="job.jobId"
                    :disabled="busy"
                    class="job-row"
                    :class="{ active: selected?.jobId === job.jobId }"
                    @click="select(job)"
                  >
                    <div>
                      <strong>{{ job.title }}</strong
                      ><small
                        >{{ formatTime(job.createdAt)
                        }}<span class="row-id">
                          · {{ job.jobId.slice(0, 8) }}</span
                        ></small
                      >
                    </div>
                    <span class="status" :data-status="job.status">{{
                      states[job.status] ?? job.status
                    }}</span>
                  </button>
                </div>
                <p class="list-footnote">
                  显示 {{ visibleJobs.length }} / {{ jobs.length }} 条已载入任务
                  · 最多载入最近 50 条
                  <button class="quiet" :disabled="busy || !!pending || !!commandPending" @click="navigate('history')">查询完整任务历史</button>
                </p>
              </section>
              <section v-if="selected" class="panel detail">
                <div class="section-heading">
                  <button class="quiet detail-back" @click="backToList">
                    <AppIcon name="back" />返回任务列表
                  </button>
                  <h2>任务详情</h2>
                  <span class="status" :data-status="selected.status">{{
                    states[selected.status]
                  }}</span>
                </div>
                <h3>{{ selected.title }}</h3>
                <div class="execution-track" aria-label="处理进度">
                  <span :class="{ complete: true }">已受理</span
                  ><span :class="{ complete: selected.modelCalls > 0 }">{{
                    selected.status === "CANCELLED" ? "已取消" : "模型处理"
                  }}</span
                  ><span
                    :class="{ complete: selected.status === 'SUCCEEDED' }"
                    >{{
                      selected.status === "UNKNOWN" ? "结果未知" : "结果验证"
                    }}</span
                  >
                </div>
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
                <section
                  v-if="selected.errorCode"
                  class="job-problem"
                  aria-label="任务错误说明"
                >
                  <h3>{{ jobError(selected.errorCode).title }}</h3>
                  <p>{{ jobError(selected.errorCode).message }}</p>
                  <code>{{ selected.errorCode }}</code>
                </section>
                <p
                  v-if="!terminal(selected.status)"
                  class="muted"
                  role="status"
                >
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
                    ['FAILED', 'UNKNOWN'].includes(selected.status) && !viewer && selected.errorCode !== 'PAYLOAD_EXPIRED'
                  "
                  class="rerun-form"
                  @submit.prevent="startCommand('retry')"
                >
                  <h3>明确重跑</h3>
                  <p>
                    创建新的关联任务，保留原任务和原用量记录。沿用来源回调目的地并重新校验启用状态。
                  </p>
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
                  ><div class="result-actions"><button
                    class="secondary"
                    :disabled="resultBusy"
                    @click="readResult()"
                  >
                    {{
                      resultBusy
                        ? "读取中…"
                        : result
                          ? "重新授权读取结果"
                          : "查看结果与原文依据"
                    }}
                  </button>
                  <button class="quiet" :disabled="resultBusy" @click="readResult(true)">{{ exporting ? '导出中…' : '下载分析报告' }}</button></div>
                  <p class="muted">读取与导出均重新检查权限并留下正文读取审计。</p>
                  <p class="muted">报告含原文摘录；下载副本不受平台后续撤权或自动清理控制。</p>
                  <p v-if="reportNotice" role="status" class="muted">{{ reportNotice }}</p></template
                >
                <DeliveryPanel
                  :workspace-id="workspaceId"
                  :job-id="selected.jobId"
                  :job-status="selected.status"
                  :actor="me.username"
                  :can-replay="!viewer"
                />
                <ResultView v-if="result" :result="result" />
              </section>
              <section v-if="!selected" class="selection-hint">
                <div class="selection-symbol"><AppIcon name="tasks" /></div>
                <h2>任务详情</h2>
                <p>选择一个任务，查看处理状态、<br />调用记录与原文依据。</p>
                <span>结果需要当前空间的正文读取权限。</span>
              </section>
            </div>
          </template>
        </template>
      </main>
      <footer>
        RelayOps <span>文档任务与原文依据</span
        ><span class="footer-mode">{{
          profile?.billable ? "供应商模型" : "开发预览"
        }}</span>
      </footer>
    </div>
  </div>
</template>
