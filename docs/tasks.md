# 第十一阶段任务

| 编号 | 任务 | 文件 | 依赖 | 参考 |
| --- | --- | --- | --- | --- |
| 01 | 权限与统计契约 | spec/checklist、ADR0009 | 无 | 全保留记录、最新Run、OWNER审计 |
| 02 | 概览查询 | OperationsService/Controller | 01 | 一致快照、安全字段、零与空时间 |
| 03 | 审计查询 | OperationsService/Controller | 02 | beforeId/limit、精确过滤、白名单 |
| 04 | 真实库负例与统计 | OperationsIntegrationTest | 03 | 隔离、撤权、最新轮次、分页 |
| 05 | 工作台概览页 | OperationsPanel、App | 03 | taste、单轮询、隐藏/切空间清除 |
| 06 | 审计筛选与分页 | OperationsPanel | 05 | OWNER入口、手动刷新、游标 |
| 07 | 接入主流程 | 导航、README、既有回归 | 04/06 | 登录→切空间→概览→审计 |
| 08 | 验收与发布 | 浏览器、verification | 07 | 三档/拒绝清除、只读对账及推送 |