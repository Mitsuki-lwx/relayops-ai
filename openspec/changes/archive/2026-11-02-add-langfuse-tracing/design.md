# Design

## Context
模型采用Java HttpClient手工兼容适配，JobWorker在SENT提交后调用一次。交付worker独立异步执行。Langfuse本机3.225.15/3050可用，无现有埋点。

## Goals / Non-Goals
Goals: 通过阶段耗时、固定错误码和真实用量定位问题；观测故障不影响业务。
Non-Goals: 正文存储、自动模型重试、质量评分、修改已发布1.0附件。

## Decisions
- 使用原生OpenTelemetry SDK/OTLP HTTP，独立provider及BatchSpanProcessor，有界256队列、短导出超时；默认关闭。版本使用Maven Central确认的1.67.0。
- 每个成功claim建立一个执行trace及队列/model/validation/persistence子span；回调独立trace，用相同job session关联。不用线程上下文跨MQ推断父子关系，不持久化新观测表。
- 元数据只用任务/空间ID、冻结profile/模型/提示词版本、状态、数字计数。输入输出仅元数据摘要，绝不传标题、段落、引用、正文、签名密钥、端点、异常message。
- 仅从有效供应商usage整数读取input/output token；缺失不填零。Fake明确标记，不提供假成本。
- 固定阶段/错误标签；所有span关闭释放线程上下文，导出后台执行。Langfuse是可信运维项目，不继承工作台租户权限；不能开放给普通空间成员。

## Risks / Trade-offs
后台队列满、关机或后端故障可能丢trace；数据库状态仍为权威。回调在任务session下独立trace，暂不提供单一跨进程trace树。真实质量评测另批进行。
