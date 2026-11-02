# Proposal

## Why
1.0缺少模型与异步交付的阶段级观测。用户提供本机Langfuse项目连接，要求接入tracing。

## What Changes
- 默认关闭、显式启用的元数据观测，覆盖队列、模型、校验、保存及回调。
- 回调尝试单独trace，通过任务session关联，区分执行尝试。
- 独立有界后台导出，无正文、凭据或异常原文；有效供应商用量才记录。
- 本机实际摄入及读回，验证观测失败不改变任务结果。

## Capabilities
### New Capabilities
- `task-observability`: 安全、非阻塞的任务及模型阶段观测。
### Modified Capabilities
无。

## Impact
后端worker/model/delivery、配置、测试与运维文档，新增OpenTelemetry依赖。不改变迁移、授权、SENT/UNKNOWN或模型重试。本批不实施质量评测集与LLM裁判。
