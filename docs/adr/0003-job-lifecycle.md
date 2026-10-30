# 排队取消与明确重跑

日期：2026-10-29。对应 BR05、BR08、BR14 和 FR05/FR07。保持第二阶段 Outbox 和 SENT 边界不变。

取消接口为 POST /api/v1/workspaces/{workspaceId}/jobs/{jobId}/cancel，要求 Idempotency-Key 和 expectedVersion。仅 OWNER/OPERATOR 可操作，只有 QUEUED 转 CANCELLED。原受理日的 RESERVED 转 RELEASED、预算计数、任务版本、审计与成功响应在同一事务提交。已发布或迟到信封仍可到达，消费者读到终态后确认，不创建 Attempt、不调用模型。保留 Outbox 事实，不伪造已发布状态。

明确重跑接口为同资源下 POST /retry，要求 expectedVersion、reason 和 acknowledgeDuplicateCost=true。仅 FAILED/UNKNOWN 且来源正文未过期可重跑。原因规范化后 1–300 个 Unicode 字符。新 Job 的 parentJobId 指向来源，使用来源文本、应用和语言，以及当前允许的模型、提示和 Schema；任务、配额、Outbox、审计和幂等响应一起提交。原 Job 状态与 SPENT/UNCERTAIN 不变。当前仍是免费假模型，费用确认语义保留。

命令按主体、空间、操作与键分别去重，指纹包含资源 ID。成功响应只含元数据并保存快照，不含正文或秘密。重放重新校验成员和角色；重跑重放仍检查来源 TTL。改变版本、原因或风险确认属于不同请求，不复用原键。

锁顺序：取消为成员→原受理日预算→Job；重跑为成员→当前应用→新受理日预算→来源 Job。重跑不改来源预算，也不先锁来源再等待预算，避免与旧执行器的预算→Job 顺序形成环。外部 public 命令通过事务代理进入，内部共用 private 受理步骤。

V3 追加同空间 parentJobId 复合外键、幂等成功响应快照和审计详情。已有 CREATE_JOB 响应从固定受理元数据补齐，V1/V2 不改。未来删除父历史前先解除仍存活子任务引用并写审计，本次不实现清理器。

前端保存待确认命令的操作、来源、版本、原请求和键。刷新或响应丢失后只重试原意图，成功后清除。放弃本地记录不撤销服务端已提交责任。切换登录身份清除内存中的旧正文和命令表单。
