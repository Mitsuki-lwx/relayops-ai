# 第四阶段任务

前三阶段及初次发布完成。本批8项任务已实施并验证，接入用户选择的 OpenAI 兼容接口；证据见 verification.md。

| 编号 | 任务 | 影响文件 | 依赖 | 参考 |
| --- | --- | --- | --- | --- |
| 01 | 协议、边界与验证设计 | docs/spec.md、checklist.md、adr/0004* | 无 | Chat Completions/JSON 官方文档 |
| 02 | 提取公共结果与验证器 | job/DocumentResult.java、FakeModel.java | 01 | 原引用精确匹配规则 |
| 03 | 模型配置和配置派生标识 | job/ModelGateway.java、application.yml | 02 | 已有 model_profile/prompt_version/schema_version |
| 04 | HTTP 调用和有界响应 | job/ModelGateway.java | 03 | JDK HttpClient、单次应用调用与总时限 |
| 05 | 任务路由与心跳 | job/JobWorker.java、LeaseHeartbeat.java、JobService.java | 04 | SENT 边界、旧 token/代次/到期保护 |
| 06 | 配置加载与隔离测试模式 | scripts/*backend.ps1、ops/.env.example | 05 | 测试脚本强制 Fake，防止使用真实密钥 |
| 07 | 接入主流程 | frontend/src/App.vue、README.md | 06 | 当前配置、外发确认、结果和重跑 |
| 08 | 端到端验证 | backend/src/test/*、frontend/tests/*、docs/verification.md | 07 | 本机供应商故障、真实 MySQL/MQ、浏览器、无重复调用 |

后续增加供应商专项效果评估、组织隔离、交付回调与补投；当前不声明完整 P0 已完成。
