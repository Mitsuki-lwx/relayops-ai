<script setup lang="ts">
import { ref, watch, onUnmounted } from "vue";
import { api } from "./api";
const props = defineProps<{
  workspaceId: string; jobId: string; deliveryId: string;
  version: number; status: string; actor: string;
}>();
const emit = defineEmits<{ changed: [] }>();
type Plan = { planId: string; destinationName: string; endpoint: string; payloadHash: string; nextRunNo: number; reason: string; expiresAt: string };
type Intent = { planId: string; key: string };
const reason = ref(""), error = ref(""), busy = ref(false), acknowledged = ref(false);
const plan = ref<Plan | null>(null), pending = ref<Intent | null>(null);
let generation = 0;
const storageKey = () => `relayops.replay.${props.actor}.${props.workspaceId}.${props.jobId}.${props.deliveryId}`;
watch(() => [props.actor, props.workspaceId, props.jobId, props.deliveryId], () => {
  generation++; plan.value = null; acknowledged.value = false; reason.value = ""; error.value = "";
  try {
    const value = JSON.parse(sessionStorage.getItem(storageKey()) ?? "null");
    pending.value = value && typeof value.planId === "string" && typeof value.key === "string" ? value : null;
  } catch { pending.value = null; error.value = "本地确认记录无法读取，请刷新核对交付。"; }
}, { immediate: true });
watch(() => props.version, () => { if (!pending.value) { plan.value = null; acknowledged.value = false; } });
onUnmounted(() => { generation++; });
async function preview() {
  if (busy.value || pending.value) return;
  const g = generation; busy.value = true; error.value = ""; plan.value = null; acknowledged.value = false;
  try {
    const value = await api<Plan>(`/api/v1/workspaces/${props.workspaceId}/deliveries/${props.deliveryId}/replay-plans`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ expectedVersion: props.version, reason: reason.value }),
    });
    if (g === generation) plan.value = value;
  } catch (ex) { if (g === generation) error.value = ex instanceof Error ? ex.message : "预览未确认，请刷新交付后重新预览。"; }
  finally { if (g === generation) busy.value = false; }
}
async function confirm() {
  if (busy.value || (!pending.value && (!plan.value || !acknowledged.value))) return;
  const g = generation, key = storageKey();
  busy.value = true; error.value = "";
  try {
    if (!pending.value) {
      const intent = { planId: plan.value!.planId, key: crypto.randomUUID() };
      sessionStorage.setItem(key, JSON.stringify(intent)); // Persist before sending; never mint a replacement on uncertainty.
      pending.value = intent;
    }
    const intent = pending.value;
    await api(`/api/v1/workspaces/${props.workspaceId}/replay-plans/${intent.planId}/confirm`, {
      method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": intent.key },
      body: JSON.stringify({ acknowledgeDuplicateDelivery: true }),
    });
    sessionStorage.removeItem(key);
    if (g === generation) { pending.value = null; plan.value = null; acknowledged.value = false; reason.value = ""; emit("changed"); }
  } catch (ex) {
    if (g === generation) error.value = (ex instanceof Error ? ex.message : "确认响应未知") + "；先刷新核对，重试会沿用原确认键。";
  } finally { if (g === generation) busy.value = false; }
}
function discard() {
  sessionStorage.removeItem(storageKey()); pending.value = null; plan.value = null; acknowledged.value = false; error.value = "";
}
</script>
<template>
  <div v-if="status === 'DEAD' || pending" class="replay-controls">
    <h4>单条补投</h4>
    <p class="muted">补投新建交付轮次，沿用原交付 ID 与结果；模型和原轮次不重跑。接收端仍须去重。</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <template v-if="pending">
      <p class="notice">有待核对的补投确认。刷新后仍保留原计划与幂等键，不会自动发送。</p>
      <button class="secondary" :disabled="busy" @click="confirm">沿用原确认重试</button>
      <button class="quiet" :disabled="busy" @click="discard">放弃本地确认记录</button>
      <p class="muted">放弃记录不会撤销服务端已接受的补投，操作前先刷新核对。</p>
    </template>
    <form v-else @submit.prevent="preview">
      <label :for="`replay-reason-${deliveryId}`">补投原因</label>
      <textarea :id="`replay-reason-${deliveryId}`" v-model="reason" maxlength="300" rows="2" required :disabled="busy || !!plan"></textarea>
      <button class="secondary" :disabled="busy || !reason.trim()">预览补投</button>
      <div v-if="plan" class="replay-preview">
        <p>第 {{ plan.nextRunNo }} 轮 · {{ plan.destinationName }}</p>
        <p class="muted">{{ plan.endpoint }}</p>
        <p>原因：{{ plan.reason }}</p>
        <p class="muted">预览有效至 {{ new Date(plan.expiresAt).toLocaleTimeString('zh-CN', { timeZone: 'Asia/Shanghai' }) }}（两分钟）；确认时重新校验。</p>
        <label class="risk-check"><input v-model="acknowledged" type="checkbox" :disabled="busy" />我确认接收端可能已处理过此结果，接受再次投递风险。</label>
        <button type="button" class="primary" :disabled="busy || !acknowledged" @click="confirm">确认补投</button>
        <button type="button" class="quiet" :disabled="busy" @click="plan = null; acknowledged = false">取消预览</button>
      </div>
    </form>
  </div>
</template>
