<script setup lang="ts">
import { ref, watch, onUnmounted } from "vue";
import { api } from "./api";
export type Destination = {
  id: string;
  name: string;
  catalogId: string;
  endpoint: string;
  enabled: boolean;
  version: number;
};
const props = defineProps<{
  workspaceId: string;
  owner: boolean;
  disabled: boolean;
}>();
const emit = defineEmits<{ changed: [] }>();
const items = ref<Destination[]>([]),
  catalog = ref<{ id: string; name: string; endpoint: string }[]>([]),
  name = ref(""),
  catalogId = ref(""),
  externalEndpoint = ref("");
const busy = ref(false),
  error = ref(""),
  ack = ref(false),
  secret = ref<{ id: string; value: string } | null>(null);
let generation = 0;
onUnmounted(() => {
  generation++;
  secret.value = null;
});
const path = () => `/api/v1/workspaces/${props.workspaceId}`;
async function load() {
  const g = ++generation;
  secret.value = null;
  ack.value = false;
  error.value = "";
  items.value = [];
  try {
    const [list, directory] = await Promise.all([
      api<Destination[]>(path() + "/destinations"),
      api<typeof catalog.value>(path() + "/destination-catalog"),
    ]);
    if (g === generation) {
      items.value = list;
      catalog.value = directory;
      if (!directory.some(item => item.id === catalogId.value)) catalogId.value = directory[0]?.id ?? "";
    }
  } catch (ex) {
    if (g === generation)
      error.value = ex instanceof Error ? ex.message : "加载失败";
  }
}
watch(() => [props.workspaceId, props.owner], load, { immediate: true });
async function action(operation: () => Promise<unknown>) {
  if (busy.value || props.disabled) return;
  busy.value = true;
  error.value = "";
  secret.value = null;
  try {
    await operation();
    await load();
    emit("changed");
  } catch (ex) {
    error.value =
      (ex instanceof Error ? ex.message : "操作未确认") +
      "；请先刷新核对当前状态。";
  } finally {
    busy.value = false;
  }
}
async function create() {
  await action(() =>
    api(path() + "/destinations", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        name: name.value,
        catalogId: catalogId.value,
        ...(catalogId.value === "https-allowlist-v1" ? { endpoint: externalEndpoint.value } : {}),
      }),
    }),
  );
  name.value = "";
}
async function rotate(item: Destination) {
  if (!ack.value || busy.value || props.disabled) return;
  const g = generation;
  busy.value = true;
  error.value = "";
  secret.value = null;
  try {
    const value = await api<{ keyId: string; secret: string; version: number }>(path() + `/destinations/${item.id}/rotate-key`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ acknowledgeSecretRead: true, expectedVersion: item.version }),
    });
    if (g === generation) {
      secret.value = { id: value.keyId, value: value.secret };
      const target = items.value.find(entry => entry.id === item.id);
      if (target) target.version = value.version;
    }
    emit("changed");
  } catch (ex) {
    if (g === generation) error.value = ex instanceof Error ? ex.message : "轮换失败";
  } finally {
    if (g === generation) busy.value = false;
    ack.value = false;
  }
}
async function toggle(item: Destination) {
  await action(() =>
    api(path() + `/destinations/${item.id}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        enabled: !item.enabled,
        expectedVersion: item.version,
      }),
    }),
  );
}
async function reveal(item: Destination) {
  if (!ack.value || busy.value || props.disabled) return;
  const g = generation;
  busy.value = true;
  error.value = "";
  secret.value = null;
  try {
    const value = await api<{ keyId: string; secret: string }>(
      path() + `/destinations/${item.id}/reveal-key`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ acknowledgeSecretRead: true }),
      },
    );
    if (g === generation)
      secret.value = { id: value.keyId, value: value.secret };
  } catch (ex) {
    if (g === generation)
      error.value = ex instanceof Error ? ex.message : "读取失败";
  } finally {
    busy.value = false;
    ack.value = false;
  }
}
</script>
<template>
  <section class="panel destination-panel">
    <div class="section-heading">
      <h2>回调目的地</h2>
      <button class="quiet" :disabled="busy || disabled" @click="load">
        刷新目的地
      </button>
    </div>
    <p class="muted">
      当前目录提供本机独立演示接收端。任务受理会冻结目标；停用将阻止后续领取发送，已发出的请求可能仍在处理。
    </p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <form v-if="owner" class="destination-create" @submit.prevent="create">
      <label for="destination-name">目的地名称</label
      ><input
        id="destination-name"
        v-model="name"
        maxlength="80"
        required
        :disabled="busy || disabled"
      />
      <label for="destination-catalog">接收端目录</label
      ><select id="destination-catalog" v-model="catalogId" :disabled="busy || disabled">
        <option v-for="entry in catalog" :key="entry.id" :value="entry.id">{{ entry.name }}</option>
      </select>
      <p v-if="catalogId === 'https-allowlist-v1'" class="muted">HTTPS端点必须使用部署白名单主机和 /relayops/events 路径。</p>
      <label v-if="catalogId === 'https-allowlist-v1'" for="destination-endpoint">HTTPS端点</label
      ><input
        v-if="catalogId === 'https-allowlist-v1'"
        id="destination-endpoint"
        v-model="externalEndpoint"
        maxlength="200"
        placeholder="https://hooks.example.com/relayops/events"
        :disabled="busy || disabled"
      />
      <p v-else class="muted">{{ catalog.find(entry => entry.id === catalogId)?.endpoint }}</p>
      <button
        class="primary"
        :disabled="busy || disabled || !name.trim() || !catalog.length"
      >
        创建目的地
      </button>
    </form>
    <p v-if="!items.length" class="muted">
      尚未配置回调目的地。任务仍可只在工作台查看结果。
    </p>
    <ul class="destination-list">
      <li v-for="item in items" :key="item.id">
        <div>
          <strong>{{ item.name }}</strong
          ><span class="muted">{{ item.enabled ? "已启用" : "已停用" }}</span>
        </div>
        <p class="muted">{{ item.endpoint }}</p>
        <div v-if="owner" class="lifecycle-actions">
          <button
            class="secondary"
            :disabled="busy || disabled"
            @click="toggle(item)"
          >
            {{ item.enabled ? "停用" : "启用" }} {{ item.name }}</button
          ><button
            class="quiet"
            :disabled="busy || disabled || !ack"
            @click="reveal(item)"
          >
            读取 {{ item.name }} 签名密钥
          </button>
          <button
            class="quiet"
            :disabled="busy || disabled || !ack"
            @click="rotate(item)"
          >
            轮换 {{ item.name }} 签名密钥
          </button>
        </div>
        <div v-if="secret?.id === item.id" data-secret class="secret-box">
          <p>仅供配置接收端。离开此页后清除；读取已记录审计。</p>
          <code>{{ secret.value }}</code
          ><button class="quiet" @click="secret = null">隐藏密钥</button>
        </div>
      </li>
    </ul>
    <label v-if="owner" class="risk-check"
      ><input
        v-model="ack"
        type="checkbox"
        :disabled="busy || disabled"
      />我确认需要读取签名密钥，并将只交给受控接收端；此次读取会记录审计。</label
    >
    <p class="muted">
      本机接收端注册和启动方式见项目
      README。回调失败只重试交付，模型不会重新执行。
    </p>
  </section>
</template>
