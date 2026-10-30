# 第三阶段任务

登录到结果、Outbox/RabbitMQ、Docker F 盘迁移与本批排队取消/明确重跑已完成。下列 8 项任务均已实施并验收，证据见 verification.md。

| 编号 | 任务 | 影响文件 | 依赖 | 参考 |
| --- | --- | --- | --- | --- |
| 01 | 确定状态、幂等与锁顺序 | docs/spec.md、checklist.md、adr/0003* | 无 | BR05/08/14，FR05/07 |
| 02 | 追加来源和成功响应迁移 | db/migration/V3* | 01 | INV01/06/08，V1/V2 不改 |
| 03 | 提取事务内公共受理步骤 | job/JobService.java | 02 | 当前 create、OutboxStore.enqueue |
| 04 | 实现取消与明确重跑命令 | job/JobService.java、JobController.java | 03 | ADR 0003，预算→Job 顺序 |
| 05 | 查询当前配置与来源元数据 | job/JobService.java、JobController.java | 04 | BR03 冻结配置，BR08 新 Job |
| 06 | 实现界面与原意图恢复 | frontend/src/App.vue、style.css | 05 | 设计 §10，UNKNOWN 风险确认 |
| 07 | 接入主流程 | 后端控制器、工作台、README.md | 06 | 排队→取消；未知→确认→新结果→来源 |
| 08 | 端到端验证 | backend/src/test/*、frontend/tests/*、docs/verification.md | 07 | MySQL/MQ 竞争、撤权、TTL、旧消息与响应丢失 |

后续继续真实模型的受控接入和长请求心跳，再做回调与补投；当前不声明完整 P0 已完成。
