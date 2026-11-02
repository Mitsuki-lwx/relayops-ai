# Spec Delta

## Purpose
提供可选择启用的任务执行和模型观测，使可信运维人员能够定位队列、模型、校验、保存与回调的耗时和失败，同时保持原有任务责任、数据保留及权限边界。

## ADDED Requirements

### Requirement: Metadata-only task tracing
系统SHALL仅在显式启用时向配置的Langfuse导出任务阶段观测，包含任务关联、模型冻结配置和固定结果标签；正文与凭据MUST不进入观测。

#### Scenario: Successful extraction
- **WHEN** 已claim任务完成模型、引用校验与结果保存
- **THEN** 观测包含执行父子阶段及模型版本，输入输出为元数据，供应商缺少用量时不伪造token或成本

#### Scenario: Asynchronous delivery
- **WHEN** 任务触发回调尝试
- **THEN** 回调trace以任务session关联，并区分每次尝试，不包含回调正文、地址或签名密钥

### Requirement: Telemetry must not change task responsibility
观测MUST通过有界后台队列导出，禁用、失败、超时或队列满SHALL不新增模型调用、不改变SENT/UNKNOWN、预算、授权或持久化结果。

#### Scenario: Observation backend unavailable
- **WHEN** Langfuse拒绝或无法接收观测
- **THEN** 任务按原状态机执行，重复消息仍只调用一次模型，观测不会将任务重试或卡住
