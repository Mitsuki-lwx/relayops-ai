# OpenAI 兼容文档分析

日期：2026-10-30。仅实现非流式 Chat Completions，单一当前服务端模型配置；不宣称支持全部兼容供应商。

配置 mode=fake 默认，mode=compatible 必须显式填写完整 HTTPS /chat/completions 地址、模型名和密钥；拒绝 URL 凭据、查询和片段。HTTP 仅允许测试通过显式内部属性连接 127.0.0.1。浏览器不选择任意地址，密钥不入库、不返回、不记录；仅环境配置持有。测试脚本强制 Fake，兼容测试使用自建本机 HTTP 服务与假密钥。

当前配置标识由地址、模型、超时、输出上限、参数模式和提示内容派生，最长不超过既有列。受理保存该标识、提示与 Schema。旧 Fake 配置始终可执行；当前配置不匹配的兼容排队任务在 SENT 前失败并释放额度，不静默切换模型。密钥不参与配置标识，支持同一地址/模型的密钥轮换；不持久化旧供应商配置档案。

请求使用 system/user 消息，正文是带段落 ID 的 JSON 数据。要求 JSON 结构，供应商可配置是否支持 json_object 和 max_tokens/max_completion_tokens。输出标题、模型标识和 Schema 由服务端赋值，模型仅生成有依据的摘要/要点/约束和未知项。输出引用仍需精确定位；这不保证语义真实性或完整性。

SENT 在 HTTP 调用前提交，模型调用在事务外，应用不自动重试或自动切换供应商。完整未来响应体受总超时和 128 KiB 上限约束，禁止跟随重定向。确定的 400/401/403/404/405/413/415/422/429 拒绝记录 FAILED/MODEL_REJECTED；有效响应的拒答、截断和非法输出分别记录安全错误码，原供应商响应不存日志。其他状态、断连、总时限或不明异常保留 SENT，由租约恢复 UNKNOWN。跨 SENT 的调用保留平台 SPENT/UNCERTAIN 用量，不等同于供应商实际账单。

心跳仅续期尚未到期、仍 RUNNING 且 token/代次匹配的执行权；不复活过期租约，不改变任务版本。每次心跳 SQL 有查询时限；续租最多持续调用时限加三十秒，退出执行后关闭。结果提交仍检查执行权。恢复和迟到响应不能产生第二次模型调用。进程故障后心跳停止，数据库恢复负责分类。

原有数据库模式够用，不新增迁移；V1/V2/V3 不改。真实供应商请求/价格/效果和大规模调度仍需后续专项验收。

首次兼容提交须 acknowledgeExternalProcessing=true，已有受理意图重放先于新意图确认校验；缺确认不创建任务或额度。未提供/false 的确认字段不参与旧 CREATE_JOB 序列化指纹，保留升级前幂等格式。明确重跑已有风险确认同时覆盖新配置的文档外发。

协议依据：[官方 Chat Completions API](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)。兼容性由明确的请求子集和本机协议用例验证，不据此宣称所有模型/供应商均可直接使用。
