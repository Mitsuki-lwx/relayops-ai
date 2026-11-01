<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { api } from "./api";
import OrganizationAuditPanel from "./OrganizationAuditPanel.vue";

const props = defineProps<{ disabled: boolean; tenantId?: string; active: boolean }>();
const emit = defineEmits<{ changed: []; available: [value: boolean] }>();
type Company = { id: string; name: string; role: string };
type Unit = { id: string; name: string; kind: string; parentId: string | null };
type Detail = {
  units: Unit[];
  members: { id: string; username: string; role: string; active: boolean }[];
  memberships: { unitId: string; userId: string }[];
  workspaces: { id: string; name: string; canGrant: boolean }[];
  grants: { workspaceId: string; unitId: string; role: string }[];
  audit: { operation: string; createdAt: string }[];
};
const companies = ref<Company[]>([]),
  companyId = ref(""),
  detail = ref<Detail | null>(null);
const busy = ref(false),
  error = ref(""),
  notice = ref("");
const unitName = ref(""),
  kind = ref("DEPARTMENT"),
  parentId = ref("");
const memberUnit = ref(""),
  memberUser = ref("");
const spaceName = ref(""),
  grantSpace = ref(""),
  grantUnit = ref(""),
  grantRole = ref("OPERATOR");
const admins = computed(() =>
  companies.value.filter((c) => c.role === "ADMIN"),
);
const departments = computed(
  () => detail.value?.units.filter((u) => u.kind === "DEPARTMENT") ?? [],
);
const grantSpaces = computed(
  () => detail.value?.workspaces.filter((w) => w.canGrant) ?? [],
);
const disabled = computed(() => busy.value || props.disabled);
let generation = 0;
function label(id: string) {
  return detail.value?.units.find((u) => u.id === id)?.name ?? "无";
}
function members(unitId: string) {
  return (
    detail.value?.members
      .filter((m) =>
        detail.value!.memberships.some(
          (link) => link.userId === m.id && link.unitId === unitId,
        ),
      )
      .map((m) => m.username)
      .join("、") || "暂无成员"
  );
}
function grants(unitId: string) {
  return (
    detail.value?.grants
      .filter((g) => g.unitId === unitId)
      .map(
        (g) =>
          `${detail.value!.workspaces.find((w) => w.id === g.workspaceId)?.name ?? "空间"} · ${g.role}`,
      )
      .join("；") || "未授权空间"
  );
}
async function load() {
  const current = ++generation,
    id = companyId.value;
  detail.value = null;
  if (!id) return;
  try {
    const value = await api<Detail>(`/api/v1/organizations/${id}`);
    if (current !== generation) return;
    detail.value = value;
    memberUnit.value = value.units.some((u) => u.id === memberUnit.value)
      ? memberUnit.value
      : (value.units[0]?.id ?? "");
    grantUnit.value = value.units.some((u) => u.id === grantUnit.value)
      ? grantUnit.value
      : (value.units[0]?.id ?? "");
    memberUser.value = value.members.some((m) => m.id === memberUser.value)
      ? memberUser.value
      : (value.members[0]?.id ?? "");
    grantSpace.value = value.workspaces.some(
      (w) => w.id === grantSpace.value && w.canGrant,
    )
      ? grantSpace.value
      : (grantSpaces.value[0]?.id ?? "");
    if (
      !value.units.some(
        (u) => u.id === parentId.value && u.kind === "DEPARTMENT",
      )
    )
      parentId.value = "";
  } catch (ex) {
    if (current === generation)
      error.value = ex instanceof Error ? ex.message : "组织读取失败";
  }
}
async function run(action: () => Promise<unknown>) {
  if (disabled.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await action();
    notice.value = "已保存";
    await load();
    emit("changed");
  } catch (ex) {
    error.value = `${ex instanceof Error ? ex.message : "操作未确认"}。请先刷新列表核对，再决定是否重新提交。`;
  } finally {
    busy.value = false;
  }
}
function post(path: string, body: unknown) {
  return api(`/api/v1/organizations/${companyId.value}/${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}
function put(path: string, body: unknown) {
  return api(`/api/v1/organizations/${companyId.value}/${path}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}
function createUnit() {
  void run(async () => {
    await post("units", {
      name: unitName.value,
      kind: kind.value,
      parentId: parentId.value || null,
    });
    unitName.value = "";
  });
}
function membership(active: boolean) {
  void run(() =>
    put(`units/${memberUnit.value}/members/${memberUser.value}`, { active }),
  );
}
function createSpace() {
  void run(async () => {
    await post("workspaces", { name: spaceName.value });
    spaceName.value = "";
  });
}
function saveGrant() {
  void run(() =>
    put(`workspaces/${grantSpace.value}/grants/${grantUnit.value}`, {
      role: grantRole.value,
    }),
  );
}
watch(
  () => props.tenantId,
  (id) => {
    if (id && admins.value.some((c) => c.id === id) && id !== companyId.value) {
      companyId.value = id;
      error.value = "";
      void load();
    }
  },
);
onMounted(async () => {
  try {
    companies.value = await api<Company[]>("/api/v1/organizations");
    emit("available", admins.value.length > 0);
    companyId.value =
      admins.value.find((c) => c.id === props.tenantId)?.id ??
      admins.value[0]?.id ??
      "";
    await load();
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : "公司读取失败";
  }
});
</script>

<template>
  <section v-if="admins.length || error" class="panel organization">
    <h2>部门、小组与空间授权</h2>
    <p class="muted">
      空间内共享。部门和小组只有获得明确授权后才能访问空间，父部门不自动继承权限。已有直接成员权限保留。
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <label for="organization-company">管理公司</label>
    <select
      id="organization-company"
      v-model="companyId"
      :disabled="disabled"
      @change="
        error = '';
        notice = '';
        load();
      "
    >
      <option v-for="c in admins" :key="c.id" :value="c.id">
        {{ c.name }}
      </option>
    </select>
    <template v-if="detail">
      <div class="organization-layout">
        <div class="organization-forms">
          <form @submit.prevent="createUnit">
            <h3>新建部门或小组</h3>
            <fieldset :disabled="disabled">
              <label for="unit-name">组织名称</label
              ><input
                id="unit-name"
                v-model="unitName"
                maxlength="100"
                required
              />
              <label for="unit-kind">组织类型</label
              ><select id="unit-kind" v-model="kind">
                <option value="DEPARTMENT">部门</option>
                <option value="TEAM">小组</option>
              </select>
              <label for="unit-parent">所属部门</label
              ><select id="unit-parent" v-model="parentId">
                <option value="">无上级部门</option>
                <option v-for="d in departments" :key="d.id" :value="d.id">
                  {{ d.name }}
                </option>
              </select>
              <button class="quiet" :disabled="!unitName.trim()">
                创建组织
              </button>
            </fieldset>
          </form>
          <form @submit.prevent="membership(true)">
            <h3>组织成员</h3>
            <fieldset :disabled="disabled">
              <label for="member-unit">成员所在组织</label
              ><select id="member-unit" v-model="memberUnit">
                <option v-for="u in detail.units" :key="u.id" :value="u.id">
                  {{ u.name }}
                </option>
              </select>
              <label for="member-user">公司成员</label
              ><select id="member-user" v-model="memberUser">
                <option v-for="m in detail.members" :key="m.id" :value="m.id">
                  {{ m.username }}{{ m.active ? "" : "（已停用）" }}
                </option>
              </select>
              <button class="quiet" :disabled="!memberUnit || !memberUser">
                加入组织
              </button>
              <button
                type="button"
                class="quiet"
                :disabled="!memberUnit || !memberUser"
                @click="membership(false)"
              >
                移出组织
              </button>
            </fieldset>
          </form>
          <form @submit.prevent="createSpace">
            <h3>新建工作空间</h3>
            <p class="muted">创建者成为空间所有者，其他公司成员需另行授权。</p>
            <fieldset :disabled="disabled">
              <label for="new-space-name">新空间名称</label
              ><input
                id="new-space-name"
                v-model="spaceName"
                maxlength="100"
                required
              /><button class="quiet" :disabled="!spaceName.trim()">
                创建空间
              </button>
            </fieldset>
          </form>
          <form @submit.prevent="saveGrant">
            <h3>显式授权</h3>
            <p class="muted">
              需要公司管理员及目标空间所有者权限。移出组织仅撤销该来源，其他组或直接成员授权仍有效。
            </p>
            <fieldset :disabled="disabled">
              <label for="grant-space">授权空间</label
              ><select id="grant-space" v-model="grantSpace">
                <option v-for="w in grantSpaces" :key="w.id" :value="w.id">
                  {{ w.name }}
                </option>
              </select>
              <label for="grant-unit">授权组织</label
              ><select id="grant-unit" v-model="grantUnit">
                <option v-for="u in detail.units" :key="u.id" :value="u.id">
                  {{ u.name }}
                </option>
              </select>
              <label for="grant-role">授权角色</label
              ><select id="grant-role" v-model="grantRole">
                <option value="OPERATOR">操作者 · 可提交及读取正文</option>
                <option value="VIEWER">观察者 · 仅安全元数据</option>
                <option value="NONE">撤销此组织授权</option>
              </select>
              <button class="quiet" :disabled="!grantSpace || !grantUnit">
                保存授权
              </button>
            </fieldset>
          </form>
        </div>
        <aside class="organization-roster">
          <div class="section-heading">
            <h3>当前成员与授权</h3>
            <button class="quiet" :disabled="disabled" @click="load">
              刷新组织列表
            </button>
          </div>
          <ul class="organization-list">
            <li v-for="u in detail.units" :key="u.id">
              <strong>{{ u.name }}</strong> ·
              {{ u.kind === "TEAM" ? "小组" : "部门" }} · 上级：{{
                label(u.parentId ?? "")
              }}
              <p>{{ members(u.id) }}</p>
              <p class="muted">{{ grants(u.id) }}</p>
            </li>
          </ul>
          <p v-if="!detail.units.length" class="muted">尚未创建部门或小组。</p>
          <p class="muted">
            最近管理记录
            {{ detail.audit.length }}
            条。组织管理员可通过明确的成员变更影响已有授权，操作均留有审计。
          </p>
        </aside>
      </div>
      <OrganizationAuditPanel v-if="active" :tenant-id="companyId" :disabled="disabled" />
    </template>
  </section>
</template>
