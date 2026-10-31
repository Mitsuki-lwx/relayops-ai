# 0009 空间运行概览与安全审计

2026-10-31，已实现并验证。依据原设计工作台概览/审计和十五秒概览轮询，新增只读可观测能力，不冒充完整生产监控或告警。

GET /api/v1/workspaces/{workspaceId}/operations面向当前所有有效空间成员，按全部保留元数据统计Job状态、每个Delivery最新Run状态；不累计历史DEAD轮次。分别显示AI/交付Outbox的NEW+CLAIMED数量、最早创建时间、已有发布尝试数量以及共享受理水位。它们是数据库待发布责任，不是Broker实时队列长度，不纳入无空间归属的全局隔离表。

返回观察时间、模型/交付失效租约数量，清理开关、已到期未清Job/Delivery数量和已清数量。使用readOnly REPEATABLE_READ事务与固定DB观察时间，跨查询读取同一数据快照；不存在载荷时最早积压时间为null，实际零计数保留0。只查询安全计数及时间，不读取正文、目的地密钥、供应商响应或全局主机信息。

GET /audit-events仅当前空间OWNER可读，Tenant ADMIN不自动获得入口；OPERATOR/VIEWER为403，跨空间及撤权404。按id倒序、beforeId游标、limit默认25且1–100，支持精确operation及resourceId过滤。游标/事件ID用十进制字符串传输，避免前端整数精度丢失。仅读取id/operation/resourceId/actorId/actorName/createdAt白名单，不返回detail_json中的原因、原文、请求或敏感参数。审计读取不写入新的审计事件，防止自增循环。

页面沿用taste framed-sheet，运行概览为独立视图、单一十五秒轮询，隐藏时暂停、重新可见刷新；审计手动刷新与分页。切空间、权限变化、卸载或拒绝读取时清除旧数据，迟到响应不能覆盖新上下文。只读操作不建立Job、补投、模型请求或额度分录。概览数字标明全部保留记录，区别于任务页最多50条的本地统计。

本批不新增状态表或迁移（已有空间索引足够支撑首版查询），不提供审计正文、跨空间/公司全局视图、Broker队列深度、时序指标、告警发送、Prometheus/Grafana、生产SLO或容量性能结论。
