# ADR 0014：空间所有者可配置每日调用额度

## 状态
已接受（2026-11-03）。

## 背景
每日额度是防止共享模型密钥被过度使用的成本/滥用护栏，不是供应商账单。原实现将 `budget_account.daily_limit` 固定为默认20，空间 OWNER 无法为团队调整；额度仍按空间，部署方也没有统一的硬上限。

## 决策
- 新增 V8：`workspace.daily_limit`（默认20）作为空间策略，`workspace.budget_version` 用于乐观并发；不修改 V1–V7。
- `budget_account.daily_limit` 保留为每日快照。新一天按 workspace 策略创建；当天策略变更同步更新当天行。
- `PATCH /api/v1/workspaces/{id}/budget-policy` 仅 OWNER；请求 `{dailyLimit, expectedVersion}`。GET 可供空间成员读取，包含 `canEdit`、`maxDailyLimit` 和当天占用。
- 部署级硬上限 `RELAYOPS_MAX_DAILY_LIMIT`（默认100，应用校验1–10000）；超过拒绝。
- 降低额度不得低于当天 `reserved + spent + uncertain`；否则 422 `LIMIT_BELOW_USAGE`。历史日期不改写。
- 策略变更写 `BUDGET_POLICY_UPDATED` 审计（old/new），不写正文/密钥。
- 锁序：受理和策略变更都以 tenant 锁串行化；策略变更再锁当天预算行并 upsert 当天额度行，避免 workspace 排他锁与 `Access.require` 的 workspace SHARE→tenant 锁形成死锁。
- 公司总额度、按用户额度、密钥级成本上限和供应商账单对账仍未实现；当前不宣称额度等于实际费用。

## 后果
- OWNER 可以在平台硬上限内为团队扩容，但无法突破部署护栏。
- 当天降低到已占用以下会被拒绝；需要先等待/取消/结算，再调整。
- 策略版本冲突返回409，前端提示刷新；重复提交不引入新的幂等键语义。
- 额度只控制平台受理次数，不限制供应商侧并发、金额或异常重试成本。
