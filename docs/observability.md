# Langfuse任务观测

源码增量通过OpenTelemetry Java SDK 1.67.0向Langfuse OTLP/HTTP导出。默认关闭，本机已在忽略的ops/.env配置项目密钥；重启时由load-config.ps1加载。已发布v1.0.0附件保持原样，本功能在后续源码构建中提供。

```dotenv
RELAYOPS_TRACING_ENABLED=true
RELAYOPS_TRACING_ENVIRONMENT=local
LANGFUSE_BASE_URL=http://localhost:3050
LANGFUSE_PUBLIC_KEY=填写本地项目PublicKey
LANGFUSE_SECRET_KEY=填写本地项目SecretKey
```

密钥只放本地配置，不提交或显示在工作台。HTTP仅允许本机回环地址，远程要求HTTPS。配置错误会记录固定警告并关闭导出；设置RELAYOPS_TRACING_ENABLED=false可停用，无需修改模型配置。

## 数据与排查

- 每个已claim的任务创建job.execute根观测，包括queue.wait、model.extract、result.validate与result.persist。根耗时自当前Outbox入队起算，不包含人工提交前的时间；不是持久化业务状态。
- model.extract标为generation，记录冻结profile、provider、model、prompt/schema版本与模型参数。仅读取usage白名单有效整数；缺失不填假Token。兼容网关需返回标准usage字段。
- result.persist的SETTLE_RETURNED只表示原结算函数返回；租约/TTL竞争可能使保存无操作，最终任务状态仍以数据库为准。
- 每次回调为独立delivery.attempt，记录run/attempt ID及固定结果码。以job ID作为Langfuse Session关联模型和异步回调，不伪造一个跨进程持续打开的span。
- 默认只发送元数据输入输出，不发送标题、全文、引用、模型回答、请求端点、异常message或凭据。Langfuse属于可信运维项目，访问权不继承工作台租户/空间授权，须由运维人员单独控制。
- 导出队列最多256条，后台批量发送，单次网络超时2秒。关闭、拒绝、超时、队列满或退出时可能丢观测，不重试模型或改变任务/预算；trace不是业务审计账本。

供应商usage不等于实际账单。cost_source为unknown；Langfuse可能显示自动计算的0，不能解释为已确认免费。自定义模型需另行核对定价和网关计费。

## 本机验证

常规scripts/test-backend.ps1及test-browser.ps1强制关闭导出。专门验收可在工具进程设置RELAYOPS_LANGFUSE_VERIFY=true，再执行test-backend.ps1；只有两项专用案例发送到本机项目，其余测试仍关闭。专用测试库使用本地兼容协议桩及Fake，不调用真实供应商，覆盖一次模型/重复消息和500→200回调。

通过langfuse-cli@1.2.4读取观测，从本地配置加载认证。当前自托管3.225.15处于v3写模式，指定--api-version 3.225.3，使用observations list及traces list；默认v4读取返回404。未来迁到v4后使用当前observations API。应用始终写OTLP端点及v4格式属性，未使用旧ingestion事件接口，未更改Langfuse部署或项目写模式。

2026-11-02接入验收：127后端测试全通过，零失败/错误/跳过。真实Langfuse读回五阶段及两次回调；模型桩23/11/34用量、Fake未知用量、环境、父子关系、任务session、非公开标记和脱敏均断言通过。随后按用户授权完成[真实模型三样本端到端验收](real-model-verification.md)，与本段协议桩记录分开。固定评测集（20样本/确定性评分器）已建立，尚未在固定真实profile上逐样本运行；重复采样稳定性及真实金额评测仍未实施。

参考：[Java接入建议](https://github.com/langfuse/langfuse-java)、[OTLP映射](https://langfuse.com/integrations/native/opentelemetry)、[版本兼容](https://langfuse.com/docs/compatibility)。
